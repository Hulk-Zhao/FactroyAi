package com.hengce.aps.service.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.strategy.Strategy;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyMapper;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyRule;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyRuleMapper;
import com.hengce.aps.solver.dsl.RuleCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 排产策略服务：CRUD、克隆、导入导出与 8 条软约束权重校验。
 *
 * <p>权重契约（C19）：策略的优化规则必须恰好是 8 条白名单规则，权重 0..100 且合计 100；
 * 权重为 0 表示该规则在求解期关闭。规则语义白名单复用 {@link RuleCode}，避免策略与 DSL 两处口径漂移。</p>
 */
@Service
public class StrategyService {

    /**
     * 规则阶段：优化阶段。
     */
    public static final String PHASE_OPTIMIZATION = "OPTIMIZATION";

    /**
     * 规则类型：软约束。
     */
    public static final String TYPE_SOFT = "SOFT";

    /**
     * 权限方向白名单。
     */
    private static final Set<String> DIRECTIONS = Set.of("FORWARD", "BACKWARD", "BOTTLENECK");

    /**
     * 排序字段白名单（对外字段名到数据库列名）。
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "direction", "direction",
            "strategyVersion", "strategy_version",
            "effectiveFrom", "effective_from",
            "createdAt", "created_at");

    private static final String TOTAL_WEIGHT_MESSAGE = "8 条规则权重之和必须为 100";

    /**
     * checksum 格式：64 位小写十六进制 SHA-256（C19）。
     */
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile("[0-9a-f]{64}");

    /**
     * 契约中的导入错误码（C19 只为导入端点定义 42200，不另造细分码）。
     *
     * <p>{@code errors[].code} 在 OpenAPI 中声明为 string，这里是其字符串形态；
     * 它只是端点层错误码的投影，字段级差异走 {@code field}/{@code message}。</p>
     */
    private static final String IMPORT_ERROR_CODE =
            Integer.toString(ErrorCode.UNPROCESSABLE_ENTITY.getCode());

    private final StrategyMapper strategyMapper;

    private final StrategyRuleMapper strategyRuleMapper;

    /**
     * 构造函数。
     *
     * @param strategyMapper 策略 Mapper
     * @param strategyRuleMapper 策略规则 Mapper
     */
    public StrategyService(StrategyMapper strategyMapper, StrategyRuleMapper strategyRuleMapper) {
        this.strategyMapper = strategyMapper;
        this.strategyRuleMapper = strategyRuleMapper;
    }

    /**
     * 分页查询策略。
     *
     * @param query 分页参数
     * @param name 名称关键字（模糊）
     * @param direction 排产方向（精确）
     * @param effectiveOn 生效日期（闭区间命中 {@code effectiveFrom..effectiveTo}），可空
     * @return 分页结果
     */
    public PageResult<StrategyView> list(PageQuery query, String name, String direction, LocalDate effectiveOn) {
        PageQuery normalized = query == null ? new PageQuery() : query;
        normalized.normalize();
        LambdaQueryWrapper<Strategy> wrapper = Wrappers.lambdaQuery(Strategy.class);
        if (name != null && !name.isBlank()) {
            wrapper.like(Strategy::getName, name.trim());
        }
        if (direction != null && !direction.isBlank()) {
            wrapper.eq(Strategy::getDirection, direction.trim());
        }
        applyEffectiveOn(wrapper, effectiveOn);
        applySort(wrapper, normalized.getSort());
        Page<Strategy> page = new Page<>(normalized.getPage(), normalized.getSize());
        Page<Strategy> result = strategyMapper.selectPage(page, wrapper);
        List<UUID> strategyIds = new ArrayList<>();
        for (Strategy strategy : result.getRecords()) {
            strategyIds.add((UUID) strategy.getId());
        }
        Map<UUID, List<StrategyRule>> rulesByStrategy = findRulesByStrategyIds(strategyIds);
        List<StrategyView> items = new ArrayList<>();
        for (Strategy strategy : result.getRecords()) {
            UUID strategyId = (UUID) strategy.getId();
            items.add(toView(strategy, rulesByStrategy.getOrDefault(strategyId, List.of())));
        }
        return new PageResult<>(items, normalized.getPage(), normalized.getSize(), result.getTotal(), 0);
    }

    /**
     * 查询策略详情。
     *
     * @param id 策略主键
     * @return 策略视图
     */
    public StrategyView get(UUID id) {
        Strategy strategy = findOrThrow(id);
        return toView(strategy, findRules(id));
    }

    /**
     * 创建策略。
     *
     * @param command 创建命令
     * @return 创建后的策略视图
     */
    @Transactional
    public StrategyView create(StrategyCreateCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "策略名称不能为空");
        }
        List<StrategyRuleCommand> rules = normalizeRules(command.rules());
        Strategy strategy = new Strategy();
        strategy.setId(UUID.randomUUID());
        strategy.setName(command.name().trim());
        strategy.setDirection(normalizeDirection(command.direction()));
        strategy.setStrategyVersion(command.strategyVersion() == null ? 1 : command.strategyVersion());
        strategy.setEffectiveFrom(command.effectiveFrom() == null ? LocalDate.now() : command.effectiveFrom());
        strategy.setEffectiveTo(command.effectiveTo());
        validateEffectiveRange(strategy.getEffectiveFrom(), strategy.getEffectiveTo());
        strategy.setInitialRules(command.initialRules() == null ? Map.of() : command.initialRules());
        strategy.setApplyCondition(command.applyCondition() == null ? Map.of() : command.applyCondition());
        strategy.setOptimizationRules(weightSummary(rules));
        if (existsByNameAndVersion(strategy.getName(), strategy.getStrategyVersion(), null)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "同名策略版本已存在: " + strategy.getName() + " v" + strategy.getStrategyVersion());
        }
        try {
            strategyMapper.insert(strategy);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "同名策略版本已存在: " + strategy.getName() + " v" + strategy.getStrategyVersion());
        }
        insertRules((UUID) strategy.getId(), rules);
        return toView(strategy, findRules((UUID) strategy.getId()));
    }

    /**
     * 更新策略；版本不一致返回 409/40901。
     *
     * @param id 策略主键
     * @param command 更新命令
     * @return 更新后的策略视图
     */
    @Transactional
    public StrategyView update(UUID id, StrategyUpdateCommand command) {
        Strategy existing = findOrThrow(id);
        if (command == null || command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (!Objects.equals(existing.getVersion(), command.version())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "策略已被其他请求修改，请刷新后重试");
        }
        if (command.name() != null && !command.name().isBlank()) {
            existing.setName(command.name().trim());
        }
        if (command.direction() != null) {
            existing.setDirection(normalizeDirection(command.direction()));
        }
        if (command.effectiveFrom() != null) {
            existing.setEffectiveFrom(command.effectiveFrom());
        }
        if (command.effectiveTo() != null) {
            existing.setEffectiveTo(command.effectiveTo());
        }
        validateEffectiveRange(existing.getEffectiveFrom(), existing.getEffectiveTo());
        if (command.initialRules() != null) {
            existing.setInitialRules(command.initialRules());
        }
        if (command.applyCondition() != null) {
            existing.setApplyCondition(command.applyCondition());
        }
        if (existsByNameAndVersion(existing.getName(), existing.getStrategyVersion(), id)) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "同名策略版本已存在: " + existing.getName() + " v" + existing.getStrategyVersion());
        }
        if (command.rules() == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "[42207] PUT 为全量替换，rules 不能为空（C19）");
        }
        List<StrategyRuleCommand> rules = normalizeRules(command.rules());
        existing.setOptimizationRules(weightSummary(rules));
        int rows = strategyMapper.updateById(existing);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "策略已被其他请求修改，请刷新后重试");
        }
        replaceRules(id, rules);
        Strategy latest = findOrThrow(id);
        return toView(latest, findRules(id));
    }

    /**
     * 逻辑删除策略及其规则。
     *
     * @param id 策略主键
     */
    @Transactional
    public void delete(UUID id) {
        Strategy existing = findOrThrow(id);
        for (StrategyRule rule : findRules(id)) {
            strategyRuleMapper.deleteById(rule.getId());
        }
        strategyMapper.deleteById(existing.getId());
    }

    /**
     * 克隆策略：生成同内容的新版本。
     *
     * @param id 源策略主键
     * @param command 克隆命令
     * @return 克隆后的策略视图
     */
    @Transactional
    public StrategyView clone(UUID id, StrategyCloneCommand command) {
        Strategy source = findOrThrow(id);
        List<StrategyRule> sourceRules = findRules(id);
        StrategyCloneCommand safeCommand = command == null
                ? new StrategyCloneCommand(null, null, null) : command;
        String name = safeCommand.name() == null || safeCommand.name().isBlank()
                ? source.getName() : safeCommand.name().trim();
        int nextVersion = safeCommand.strategyVersion() == null
                ? nextStrategyVersion(name) : safeCommand.strategyVersion();
        Strategy clone = new Strategy();
        clone.setId(UUID.randomUUID());
        clone.setName(name);
        clone.setDirection(source.getDirection());
        clone.setStrategyVersion(nextVersion);
        clone.setEffectiveFrom(safeCommand.effectiveFrom() == null
                ? source.getEffectiveFrom() : safeCommand.effectiveFrom());
        clone.setEffectiveTo(source.getEffectiveTo());
        clone.setInitialRules(copyMap(source.getInitialRules()));
        clone.setOptimizationRules(copyMap(source.getOptimizationRules()));
        clone.setApplyCondition(copyMap(source.getApplyCondition()));
        if (existsByNameAndVersion(name, nextVersion, null)) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "同名策略版本已存在: " + name + " v" + nextVersion);
        }
        strategyMapper.insert(clone);
        UUID cloneId = (UUID) clone.getId();
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (StrategyRule rule : sourceRules) {
            rules.add(new StrategyRuleCommand(rule.getRuleCode(), rule.getWeight(),
                    copyMap(rule.getParams()), rule.getEnabled()));
        }
        if (!rules.isEmpty()) {
            replaceRules(cloneId, rules);
        }
        return toView(clone, findRules(cloneId));
    }

    /**
     * 导出策略（C19）：返回 {@code {strategy, rules, checksum}}。
     *
     * <p>{@code checksum} = 规范化 {@code {strategy, rules}} 的 SHA-256（64 位小写十六进制），
     * 规范化规则集中在 {@link StrategyCanonicalJson} 类注释中写死（字段字典序、null 省略、
     * 数字去尾零、无空白、日期 {@code yyyy-MM-dd}）。同一份数据两次导出必得同一 checksum。</p>
     *
     * @param id 策略主键
     * @return 导出载荷（含 checksum）
     */
    public StrategyExportBundle export(UUID id) {
        Strategy strategy = findOrThrow(id);
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (StrategyRule rule : findRules(id)) {
            rules.add(new StrategyRuleCommand(rule.getRuleCode(), rule.getWeight(),
                    copyMap(rule.getParams()), rule.getEnabled()));
        }
        Map<String, Object> strategyNode = strategyNodeOf(strategy);
        String checksum = StrategyCanonicalJson.checksum(strategyNode, ruleNodes(rules));
        return new StrategyExportBundle(strategyNode, rules, checksum);
    }

    /**
     * 导入策略（C19）：请求 {@code {payload, dryRun}}，响应 {@code {valid, errors[], strategyId, existing}}。
     *
     * <p>checksum 校验先于业务校验，三种失败用同一契约码 42200、不同 message 区分：
     * 缺失、格式非法、内容被改动。checksum 通过后，{@code dryRun=true} 与真正导入
     * <b>共用同一个校验器</b>（{@link #parseImport} + {@link #normalizeRules}），
     * 试运行不落库且 {@code strategyId=null}。</p>
     *
     * <p><b>同名同版本判定（M2 收口口径）</b>：以 {@code name + strategyVersion} 定位既有记录，
     * 再用 <b>内容 checksum</b> 判定是否同一份内容。内容 checksum 完全复用
     * {@link StrategyCanonicalJson} 的 canonical JSON + SHA-256，只剔除 {@code strategy.id} 与
     * {@code strategy.version} 两个“记录身份/乐观锁”元数据（新库主键必然不同，不剔除则重导必然误判冲突）。
     * <b>checksum 相同即视为同一份内容，忽略 SHA-256 哈希碰撞，不做逐字段比对</b>：
     * <ul>
     *   <li>内容相同 → 幂等命中，200 返回既有 {@code strategyId}、{@code existing=true}，不新建、不升版本、不写 rules；</li>
     *   <li>内容不同 → 409/40901，message 点名冲突的 {@code name} 与 {@code version}，<b>绝不自动升版本</b>。</li>
     * </ul>
     * dryRun 也执行同一判定：内容相同返回 {@code existing=true}（仍不落库、strategyId=null）；
     * 同名同版本但内容不同则同样抛 40901。</p>
     *
     * @param request 导入请求
     * @return 校验/落库/幂等结果
     */
    @Transactional
    public StrategyImportResult importStrategy(StrategyImportRequest request) {
        if (request == null || request.payload() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "导入载荷不能为空");
        }
        Map<String, Object> payload = request.payload();
        Map<String, Object> strategyNode = asObject(payload.get("strategy"),
                "导入载荷缺少 strategy 对象（C19 导出形状为 {strategy, rules, checksum}）");
        List<?> ruleNodes = asArray(payload.get("rules"),
                "导入载荷缺少 rules 数组（C19 导出形状为 {strategy, rules, checksum}）");
        verifyChecksum(payload, strategyNode, ruleNodes);

        List<StrategyImportError> errors = new ArrayList<>();
        StrategyCreateCommand command = parseImport(strategyNode, ruleNodes, errors);
        if (!errors.isEmpty()) {
            return new StrategyImportResult(false, errors, null, false);
        }
        int strategyVersion = command.strategyVersion() == null ? 1 : command.strategyVersion();
        Strategy existing = findByNameAndVersion(command.name(), strategyVersion);
        if (existing != null) {
            if (!contentChecksum(strategyNode, ruleNodes)
                    .equals(contentChecksumOf(existing))) {
                throw new BizException(ErrorCode.STATE_CONFLICT,
                        "同名同版本策略已存在且内容不同: " + command.name() + " v" + strategyVersion
                                + "（checksum 不同，拒绝导入，不自动升版本）");
            }
            return new StrategyImportResult(true, List.of(),
                    request.isDryRun() ? null : (UUID) existing.getId(), true);
        }
        if (request.isDryRun()) {
            return new StrategyImportResult(true, List.of(), null, false);
        }
        StrategyCreateCommand persisted = new StrategyCreateCommand(
                command.name(),
                command.direction(),
                strategyVersion,
                command.effectiveFrom(),
                command.effectiveTo(),
                command.initialRules(),
                command.applyCondition(),
                command.rules());
        StrategyView created = create(persisted);
        return new StrategyImportResult(true, List.of(), created.id(), false);
    }

    /**
     * 校验 checksum；失败即拒绝（42200），不进入业务校验、不落库。
     *
     * @param payload 导入载荷
     * @param strategy 策略对象
     * @param rules 规则数组
     */
    private static void verifyChecksum(Map<String, Object> payload,
                                       Map<String, Object> strategy, List<?> rules) {
        Object rawChecksum = payload.get("checksum");
        if (rawChecksum == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "checksum 缺失：缺 checksum 的载荷不得视为合格导出（C19）");
        }
        if (!(rawChecksum instanceof String checksum)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "checksum 格式非法：必须为 64 位小写十六进制 SHA-256，实际类型为 "
                            + rawChecksum.getClass().getSimpleName());
        }
        if (!CHECKSUM_PATTERN.matcher(checksum).matches()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "checksum 格式非法：必须为 64 位小写十六进制 SHA-256，实际为 " + checksum);
        }
        String actual = StrategyCanonicalJson.checksum(strategy, rules);
        if (!actual.equals(checksum)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "checksum 不匹配：载荷内容与导出时的 checksum 不一致，文件已被改动（C19）");
        }
    }

    /**
     * dryRun 与真正导入共用的校验器：解析字段并收集全部错误。
     *
     * @param strategy 策略对象
     * @param rules 规则数组
     * @param errors 错误收集器
     * @return 解析后的创建命令（仅在 errors 为空时可用于落库）
     */
    private StrategyCreateCommand parseImport(Map<String, Object> strategy, List<?> rules,
                                              List<StrategyImportError> errors) {
        String formatVersion = readString(strategy, "formatVersion", "strategy.formatVersion", errors);
        if (formatVersion != null && !StrategyExportPayload.FORMAT_VERSION.equals(formatVersion)) {
            errors.add(new StrategyImportError("strategy.formatVersion", IMPORT_ERROR_CODE,
                    "不支持的导入格式版本: " + formatVersion));
        }
        String name = readString(strategy, "name", "strategy.name", errors);
        if (name == null || name.isBlank()) {
            errors.add(new StrategyImportError("strategy.name", IMPORT_ERROR_CODE, "策略名称不能为空"));
        }
        String direction = readString(strategy, "direction", "strategy.direction", errors);
        if (direction != null && !direction.isBlank()
                && !DIRECTIONS.contains(direction.trim().toUpperCase(java.util.Locale.ROOT))) {
            errors.add(new StrategyImportError("strategy.direction", IMPORT_ERROR_CODE,
                    "排产方向必须是 FORWARD/BACKWARD/BOTTLENECK: " + direction));
        }
        Integer strategyVersion = readInteger(strategy, "strategyVersion",
                "strategy.strategyVersion", errors);
        LocalDate effectiveFrom = readDate(strategy, "effectiveFrom", "strategy.effectiveFrom", errors);
        LocalDate effectiveTo = readDate(strategy, "effectiveTo", "strategy.effectiveTo", errors);
        if (effectiveFrom != null && effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            errors.add(new StrategyImportError("strategy.effectiveTo", IMPORT_ERROR_CODE,
                    "失效日期不能早于生效日期"));
        }
        Map<String, Object> initialRules = readObject(strategy, "initialRules",
                "strategy.initialRules", errors);
        Map<String, Object> applyCondition = readObject(strategy, "applyCondition",
                "strategy.applyCondition", errors);
        List<StrategyRuleCommand> ruleCommands = readRules(rules, errors);
        if (errors.isEmpty()) {
            try {
                ruleCommands = normalizeRules(ruleCommands);
            } catch (BizException exception) {
                errors.add(new StrategyImportError("rules",
                        Integer.toString(exception.getErrorCode().getCode()), exception.getMessage()));
            }
        }
        return new StrategyCreateCommand(
                name == null ? null : name.trim(),
                direction,
                strategyVersion,
                effectiveFrom,
                effectiveTo,
                initialRules,
                applyCondition,
                ruleCommands);
    }

    private static List<StrategyRuleCommand> readRules(List<?> rules,
                                                       List<StrategyImportError> errors) {
        List<StrategyRuleCommand> commands = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            String field = "rules[" + index + "]";
            Map<String, Object> item = readObjectValue(rules.get(index), field, errors);
            if (item == null) {
                continue;
            }
            String code = readString(item, "code", field + ".code", errors);
            BigDecimal weight = readDecimal(item, "weight", field + ".weight", errors);
            Boolean enabled = readBoolean(item, "enabled", field + ".enabled", errors);
            Map<String, Object> params = readObject(item, "params", field + ".params", errors);
            commands.add(new StrategyRuleCommand(code, weight, params, enabled));
        }
        return commands;
    }

    private static String readString(Map<String, Object> node, String key, String field,
                                     List<StrategyImportError> errors) {
        Object value = node.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        errors.add(new StrategyImportError(field, IMPORT_ERROR_CODE, "字段必须是字符串"));
        return null;
    }

    private static Integer readInteger(Map<String, Object> node, String key, String field,
                                       List<StrategyImportError> errors) {
        Object value = node.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).intValueExact();
            } catch (ArithmeticException exception) {
                errors.add(new StrategyImportError(field, IMPORT_ERROR_CODE, "字段必须是整数"));
                return null;
            }
        }
        errors.add(new StrategyImportError(field, IMPORT_ERROR_CODE, "字段必须是整数"));
        return null;
    }

    private static BigDecimal readDecimal(Map<String, Object> node, String key, String field,
                                          List<StrategyImportError> errors) {
        Object value = node.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        errors.add(new StrategyImportError(field, IMPORT_ERROR_CODE, "字段必须是数值"));
        return null;
    }

    private static Boolean readBoolean(Map<String, Object> node, String key, String field,
                                       List<StrategyImportError> errors) {
        Object value = node.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        errors.add(new StrategyImportError(field, IMPORT_ERROR_CODE, "字段必须是布尔值"));
        return null;
    }

    private static LocalDate readDate(Map<String, Object> node, String key, String field,
                                      List<StrategyImportError> errors) {
        Object value = node.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException exception) {
                errors.add(new StrategyImportError(field, IMPORT_ERROR_CODE,
                        "日期格式必须为 yyyy-MM-dd: " + text));
                return null;
            }
        }
        errors.add(new StrategyImportError(field, IMPORT_ERROR_CODE, "日期必须是 yyyy-MM-dd 字符串"));
        return null;
    }

    private static Map<String, Object> readObject(Map<String, Object> node, String key, String field,
                                                  List<StrategyImportError> errors) {
        return readObjectValue(node.get(key), field, errors);
    }

    private static Map<String, Object> readObjectValue(Object value, String field,
                                                       List<StrategyImportError> errors) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        errors.add(new StrategyImportError(field, IMPORT_ERROR_CODE, "字段必须是对象"));
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String message) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, message);
        }
        return (Map<String, Object>) map;
    }

    private static List<?> asArray(Object value, String message) {
        if (!(value instanceof List<?> list)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, message);
        }
        return list;
    }

    /**
     * 按 {@code name + strategyVersion} 定位既有策略。
     *
     * @param name 策略名称
     * @param strategyVersion 业务版本
     * @return 既有策略；不存在返回 {@code null}
     */
    private Strategy findByNameAndVersion(String name, int strategyVersion) {
        return strategyMapper.selectOne(Wrappers.lambdaQuery(Strategy.class)
                .eq(Strategy::getName, name)
                .eq(Strategy::getStrategyVersion, strategyVersion));
    }

    /**
     * 幂等判定用的内容 checksum：剔除 {@code id}/{@code version} 两个记录身份与乐观锁元数据。
     *
     * <p>口径：checksum 相同即视为同一份内容，忽略 SHA-256 碰撞，不做逐字段比对。</p>
     *
     * @param strategyNode 策略对象
     * @param rules 规则 canonical 视图
     * @return 内容 checksum（64 位小写十六进制）
     */
    private static String contentChecksum(Map<String, Object> strategyNode, List<?> rules) {
        Map<String, Object> content = new LinkedHashMap<>(strategyNode);
        content.remove("id");
        content.remove("version");
        return StrategyCanonicalJson.checksum(content, rules);
    }

    /**
     * 既有记录的导出形态内容 checksum（与导入载荷按同一 canonical 规则计算）。
     *
     * @param strategy 既有策略
     * @return 内容 checksum
     */
    private String contentChecksumOf(Strategy strategy) {
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (StrategyRule rule : findRules((UUID) strategy.getId())) {
            rules.add(new StrategyRuleCommand(rule.getRuleCode(), rule.getWeight(),
                    copyMap(rule.getParams()), rule.getEnabled()));
        }
        return contentChecksum(strategyNodeOf(strategy), ruleNodes(rules));
    }

    /**
     * 策略对象的导出形态（与 {@link #export} 使用同一字段集合，保证 checksum 口径不分叉）。
     *
     * @param strategy 策略实体
     * @return 导出形态 Map
     */
    private static Map<String, Object> strategyNodeOf(Strategy strategy) {
        Map<String, Object> strategyNode = new LinkedHashMap<>();
        strategyNode.put("id", strategy.getId() == null ? null : strategy.getId().toString());
        strategyNode.put("formatVersion", StrategyExportPayload.FORMAT_VERSION);
        strategyNode.put("name", strategy.getName());
        strategyNode.put("direction", strategy.getDirection());
        strategyNode.put("strategyVersion", strategy.getStrategyVersion());
        strategyNode.put("effectiveFrom", formatDate(strategy.getEffectiveFrom()));
        strategyNode.put("effectiveTo", formatDate(strategy.getEffectiveTo()));
        strategyNode.put("initialRules", copyMap(strategy.getInitialRules()));
        strategyNode.put("applyCondition", copyMap(strategy.getApplyCondition()));
        strategyNode.put("version", strategy.getVersion());
        return strategyNode;
    }

    /**
     * 规则数组的 canonical 视图：字段名与响应 JSON 一致（code/weight/params/enabled）。
     *
     * @param rules 规则命令
     * @return canonical 视图
     */
    private static List<Map<String, Object>> ruleNodes(List<StrategyRuleCommand> rules) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (StrategyRuleCommand rule : rules) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("code", rule.code());
            node.put("weight", rule.weight());
            node.put("params", copyMap(rule.params()));
            node.put("enabled", rule.enabled());
            nodes.add(node);
        }
        return nodes;
    }

    private static String formatDate(LocalDate date) {
        return date == null ? null : date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private Strategy findOrThrow(UUID id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "策略主键不能为空");
        }
        Strategy strategy = strategyMapper.selectById(id);
        if (strategy == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "策略不存在: " + id);
        }
        return strategy;
    }

    private List<StrategyRule> findRules(UUID strategyId) {
        return strategyRuleMapper.selectList(Wrappers.lambdaQuery(StrategyRule.class)
                .eq(StrategyRule::getStrategyId, strategyId)
                .orderByAsc(StrategyRule::getSeqNo));
    }

    /**
     * 批量查询规则，避免列表页 N+1 查询。
     *
     * @param strategyIds 策略主键集合
     * @return 策略主键到规则列表
     */
    private Map<UUID, List<StrategyRule>> findRulesByStrategyIds(List<UUID> strategyIds) {
        Map<UUID, List<StrategyRule>> grouped = new LinkedHashMap<>();
        if (strategyIds.isEmpty()) {
            return grouped;
        }
        List<StrategyRule> rules = strategyRuleMapper.selectList(Wrappers.lambdaQuery(StrategyRule.class)
                .in(StrategyRule::getStrategyId, strategyIds)
                .orderByAsc(StrategyRule::getSeqNo));
        for (StrategyRule rule : rules) {
            grouped.computeIfAbsent(rule.getStrategyId(), key -> new ArrayList<>()).add(rule);
        }
        return grouped;
    }

    private boolean existsByNameAndVersion(String name, Integer strategyVersion, UUID excludeId) {
        LambdaQueryWrapper<Strategy> wrapper = Wrappers.lambdaQuery(Strategy.class)
                .eq(Strategy::getName, name)
                .eq(Strategy::getStrategyVersion, strategyVersion);
        if (excludeId != null) {
            wrapper.ne(Strategy::getId, excludeId);
        }
        return strategyMapper.selectCount(wrapper) > 0;
    }

    private int nextStrategyVersion(String name) {
        List<Strategy> existing = strategyMapper.selectList(Wrappers.lambdaQuery(Strategy.class)
                .eq(Strategy::getName, name));
        int max = 0;
        for (Strategy strategy : existing) {
            max = Math.max(max, strategy.getStrategyVersion());
        }
        return max + 1;
    }

    private void insertRules(UUID strategyId, List<StrategyRuleCommand> rules) {
        int seqNo = 0;
        for (StrategyRuleCommand command : rules) {
            StrategyRule rule = new StrategyRule();
            rule.setId(UUID.randomUUID());
            rule.setStrategyId(strategyId);
            applyRule(rule, command, seqNo++);
            strategyRuleMapper.insert(rule);
        }
    }

    private void replaceRules(UUID strategyId, List<StrategyRuleCommand> commands) {
        Map<String, StrategyRule> existing = new LinkedHashMap<>();
        for (StrategyRule rule : findRules(strategyId)) {
            existing.put(rule.getRuleCode(), rule);
        }
        int seqNo = 0;
        Set<String> kept = new LinkedHashSet<>();
        for (StrategyRuleCommand command : commands) {
            StrategyRule rule = existing.get(command.code());
            boolean created = rule == null;
            if (created) {
                rule = new StrategyRule();
                rule.setId(UUID.randomUUID());
                rule.setStrategyId(strategyId);
            }
            applyRule(rule, command, seqNo++);
            if (created) {
                strategyRuleMapper.insert(rule);
            } else {
                strategyRuleMapper.updateById(rule);
            }
            kept.add(command.code());
        }
        for (StrategyRule rule : existing.values()) {
            if (!kept.contains(rule.getRuleCode())) {
                strategyRuleMapper.deleteById(rule.getId());
            }
        }
    }

    private void applyRule(StrategyRule rule, StrategyRuleCommand command, int seqNo) {
        rule.setRuleCode(command.code());
        RuleCode ruleCode = RuleCode.fromCode(command.code()).orElseThrow();
        rule.setName(ruleCode.getDisplayName());
        rule.setRulePhase(PHASE_OPTIMIZATION);
        rule.setRuleType(TYPE_SOFT);
        rule.setWeight(command.weight());
        rule.setParams(command.params() == null ? Map.of() : command.params());
        rule.setEnabled(command.enabled() == null
                ? command.weight().compareTo(BigDecimal.ZERO) > 0 : command.enabled());
        rule.setSeqNo(seqNo);
    }

    /**
     * 校验并归一化规则集合：必须 8 条、白名单编码、权重 0..100 且合计 100。
     *
     * @param rules 入参规则；为空时使用默认权重
     * @return 按白名单顺序排列的规则
     */
    List<StrategyRuleCommand> normalizeRules(List<StrategyRuleCommand> rules) {
        List<StrategyRuleCommand> source = rules == null || rules.isEmpty() ? defaultRules() : rules;
        if (source.size() != RuleCode.CORE_RULE_COUNT) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "[42207] 规则必须恰好 " + RuleCode.CORE_RULE_COUNT + " 条，实际为 " + source.size());
        }
        Map<String, StrategyRuleCommand> byCode = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (StrategyRuleCommand command : source) {
            if (command == null || command.code() == null) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "[42207] 规则编码不能为空");
            }
            RuleCode ruleCode = RuleCode.fromCode(command.code()).orElse(null);
            if (ruleCode == null) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "[42202] 未知规则编码: " + command.code());
            }
            if (byCode.containsKey(ruleCode.name())) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "[42206] 规则重复: " + ruleCode.name());
            }
            BigDecimal weight = command.weight();
            if (weight == null || weight.compareTo(BigDecimal.ZERO) < 0
                    || weight.compareTo(BigDecimal.valueOf(RuleCode.MAX_WEIGHT)) > 0) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "[42203] 规则权重必须是 0..100: " + ruleCode.name());
            }
            if (Boolean.FALSE.equals(command.enabled()) && weight.compareTo(BigDecimal.ZERO) > 0) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "[42203] 规则 " + ruleCode.name() + " 在 enabled=false 时权重必须为 0");
            }
            if (command.params() != null) {
                for (String key : command.params().keySet()) {
                    if (!ruleCode.getAllowedParams().contains(key)) {
                        throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                                "[42204] 规则 " + ruleCode.name() + " 不支持参数 " + key);
                    }
                }
            }
            BigDecimal scaled = weight.scale() <= 4 ? weight : weight.setScale(4, java.math.RoundingMode.HALF_UP);
            byCode.put(ruleCode.name(), new StrategyRuleCommand(ruleCode.name(), scaled,
                    command.params(), command.enabled()));
            total = total.add(scaled);
        }
        if (total.compareTo(BigDecimal.valueOf(RuleCode.TOTAL_WEIGHT)) != 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "[42203] " + TOTAL_WEIGHT_MESSAGE + "，实际为 " + total.stripTrailingZeros().toPlainString());
        }
        List<StrategyRuleCommand> ordered = new ArrayList<>();
        for (RuleCode ruleCode : RuleCode.values()) {
            ordered.add(byCode.get(ruleCode.name()));
        }
        ordered.sort(Comparator.comparingInt(command -> RuleCode.fromCode(command.code()).orElseThrow().ordinal()));
        return ordered;
    }

    private static List<StrategyRuleCommand> defaultRules() {
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (RuleCode ruleCode : RuleCode.values()) {
            rules.add(new StrategyRuleCommand(ruleCode.name(),
                    BigDecimal.valueOf(ruleCode.getDefaultWeight()), Map.of(), true));
        }
        return rules;
    }

    private static Map<String, Object> weightSummary(List<StrategyRuleCommand> rules) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (StrategyRuleCommand rule : rules) {
            summary.put(rule.code(), rule.weight());
        }
        return summary;
    }

    private static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return "FORWARD";
        }
        String normalized = direction.trim().toUpperCase(java.util.Locale.ROOT);
        if (!DIRECTIONS.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "排产方向必须是 FORWARD/BACKWARD/BOTTLENECK");
        }
        return normalized;
    }

    private static void validateEffectiveRange(LocalDate from, LocalDate to) {
        if (to != null && from != null && to.isBefore(from)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "失效日期不能早于生效日期");
        }
    }

    /**
     * 生效路径过滤：{@code effectiveFrom <= on} 且 {@code effectiveTo 为空或 >= on}（闭区间，C19）。
     *
     * @param wrapper 查询条件
     * @param effectiveOn 生效日期，可空
     */
    private static void applyEffectiveOn(LambdaQueryWrapper<Strategy> wrapper, LocalDate effectiveOn) {
        if (effectiveOn == null) {
            return;
        }
        wrapper.le(Strategy::getEffectiveFrom, effectiveOn)
                .and(nested -> nested.isNull(Strategy::getEffectiveTo)
                        .or()
                        .ge(Strategy::getEffectiveTo, effectiveOn));
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> copyMap(Map<String, V> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static void applySort(LambdaQueryWrapper<Strategy> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByDesc(Strategy::getCreatedAt);
            return;
        }
        String[] parts = sort.split(",");
        String column = SORT_COLUMNS.get(parts[0].trim());
        if (column == null) {
            wrapper.orderByDesc(Strategy::getCreatedAt);
            return;
        }
        boolean ascending = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim());
        wrapper.last("ORDER BY " + column + (ascending ? " ASC" : " DESC"));
    }

    private static StrategyView toView(Strategy strategy, List<StrategyRule> rules) {
        List<StrategyRuleView> ruleViews = new ArrayList<>();
        BigDecimal weightSum = BigDecimal.ZERO;
        for (StrategyRule rule : rules) {
            ruleViews.add(new StrategyRuleView(rule.getRuleCode(), rule.getName(), rule.getRulePhase(),
                    rule.getRuleType(), rule.getWeight(), copyMap(rule.getParams()),
                    rule.getEnabled(), rule.getSeqNo()));
            if (rule.getWeight() != null) {
                weightSum = weightSum.add(rule.getWeight());
            }
        }
        return new StrategyView((UUID) strategy.getId(), strategy.getName(), strategy.getDirection(),
                strategy.getStrategyVersion(), copyMap(strategy.getInitialRules()),
                copyMap(strategy.getOptimizationRules()), strategy.getEffectiveFrom(), strategy.getEffectiveTo(),
                copyMap(strategy.getApplyCondition()), ruleViews, ruleViews.size(), weightSum,
                strategy.getVersion(), strategy.getCreatedAt(), strategy.getUpdatedAt());
    }
}
