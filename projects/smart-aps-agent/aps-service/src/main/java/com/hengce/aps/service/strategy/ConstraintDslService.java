package com.hengce.aps.service.strategy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.strategy.ConstraintDsl;
import com.hengce.aps.infrastructure.persistence.strategy.ConstraintDslMapper;
import com.hengce.aps.solver.api.ConstraintSpec;
import com.hengce.aps.solver.dsl.ConstraintDslCompiler;
import com.hengce.aps.solver.dsl.ConstraintDslDocument;
import com.hengce.aps.solver.dsl.ConstraintDslEngineMapping;
import com.hengce.aps.solver.dsl.ConstraintDslParser;
import com.hengce.aps.solver.dsl.ConstraintDslSerializer;
import com.hengce.aps.solver.dsl.DslValidationException;
import com.hengce.aps.solver.dsl.DslValidationIssue;
import com.hengce.aps.solver.dsl.DslValidationReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 约束 DSL 服务：白名单校验、规范化、升版、激活与回滚。
 *
 * <p>契约（C19）：DSL 只允许 8 条白名单 rule_code 与白名单参数，YAML 使用 safe load，
 * 序列化永远输出 {@code constraints} 数组；单数 {@code constraint:} 仅在
 * {@code aps.dsl.legacy-singular=true}（默认）时作为兼容别名接受，解析后转单元素数组。</p>
 *
 * <p>激活不做整段事务包裹：先停用旧激活版本，再激活目标版本；任一步失败即恢复旧版本，
 * 从而保证“激活失败自动回滚”可观测、可恢复。</p>
 */
@Service
public class ConstraintDslService {

    private final ConstraintDslMapper constraintDslMapper;

    private final ConstraintDslParser parser;

    private final ConstraintDslSerializer serializer;

    private final ConstraintDslCompiler compiler;

    /**
     * 构造函数。
     *
     * @param constraintDslMapper DSL Mapper
     * @param legacySingularAlias 是否启用单数 {@code constraint:} 兼容别名
     */
    public ConstraintDslService(
            ConstraintDslMapper constraintDslMapper,
            @Value("${aps.dsl.legacy-singular:true}") boolean legacySingularAlias) {
        this.constraintDslMapper = constraintDslMapper;
        this.parser = new ConstraintDslParser(legacySingularAlias);
        this.serializer = new ConstraintDslSerializer();
        this.compiler = new ConstraintDslCompiler();
    }

    /**
     * 只校验不落库。
     *
     * @param yamlText YAML 文本
     * @return 422xx 校验报告
     */
    public DslValidationReport validate(String yamlText) {
        return parser.validate(yamlText);
    }

    /**
     * 创建新草稿版本。
     *
     * @param command 创建命令
     * @return 新版本视图
     */
    public ConstraintDslView create(ConstraintDslCreateCommand command) {
        if (command == null || command.code() == null || command.code().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 不能为空");
        }
        ConstraintDslDocument document = parseOrThrow(command.yamlText());
        String canonical = serializer.serialize(document);
        try {
            ConstraintDsl entity = new ConstraintDsl();
            entity.setId(UUID.randomUUID());
            entity.setCode(command.code().trim());
            entity.setVersionNo(constraintDslMapper.selectMaxVersionNo(command.code().trim()) + 1);
            entity.setYamlText(canonical);
            entity.setChecksum(checksum(canonical));
            entity.setStatus(ConstraintDslStatus.DRAFT.name());
            entity.setCompiledSpec(compiledSpec(document));
            constraintDslMapper.insert(entity);
            return toView(entity);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "DSL 版本并发创建冲突，请重试: " + command.code());
        }
    }

    /**
     * 查询单个版本。
     *
     * @param id 版本主键
     * @return 版本视图
     */
    public ConstraintDslView get(UUID id) {
        return toView(findOrThrow(id));
    }

    /**
     * 查询某标识的全部版本，按版本号倒序。
     *
     * @param code DSL 标识
     * @return 版本视图列表
     */
    public List<ConstraintDslView> versions(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 不能为空");
        }
        List<ConstraintDsl> entities = constraintDslMapper.selectList(
                Wrappers.lambdaQuery(ConstraintDsl.class)
                        .eq(ConstraintDsl::getCode, code.trim())
                        .orderByDesc(ConstraintDsl::getVersionNo));
        List<ConstraintDslView> views = new ArrayList<>();
        for (ConstraintDsl entity : entities) {
            views.add(toView(entity));
        }
        return views;
    }

    /**
     * 激活指定版本：先停用旧激活版本，再激活目标；失败自动恢复旧版本。
     *
     * @param id 目标版本主键
     * @return 激活后的版本视图
     */
    public ConstraintDslView activate(UUID id) {
        ConstraintDsl target = findOrThrow(id);
        if (ConstraintDslStatus.ACTIVE.name().equals(target.getStatus())) {
            return toView(target);
        }
        // 激活前重新校验库内 YAML，避免绕过写入时的校验
        ConstraintDslDocument document = parseOrThrow(target.getYamlText());
        requireEngineSupported(target, document);
        ConstraintDsl previous = constraintDslMapper.selectActiveByCode(target.getCode());
        try {
            deactivate(previous);
            target.setStatus(ConstraintDslStatus.ACTIVE.name());
            target.setEffectiveFrom(OffsetDateTime.now(ZoneOffset.UTC));
            if (constraintDslMapper.updateById(target) == 0) {
                throw new IllegalStateException("DSL 版本已被并发修改: " + target.getCode());
            }
        } catch (RuntimeException exception) {
            restore(previous);
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "DSL 激活失败，已回滚到上一激活版本: " + target.getCode()
                            + " v" + (previous == null ? "无" : previous.getVersionNo())
                            + "，激活目标 v" + target.getVersionNo() + "，原因: " + exception.getMessage());
        }
        return toView(findOrThrow(id));
    }

    /**
     * 回滚到指定历史版本：当前激活版本置 ROLLBACK，目标版本置 ACTIVE 并记录回滚来源。
     *
     * @param id 目标历史版本主键
     * @return 回滚后的版本视图
     */
    public ConstraintDslView rollback(UUID id) {
        ConstraintDsl target = findOrThrow(id);
        if (ConstraintDslStatus.ACTIVE.name().equals(target.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "目标版本已是激活版本，无需回滚");
        }
        ConstraintDslDocument document = parseOrThrow(target.getYamlText());
        requireEngineSupported(target, document);
        ConstraintDsl current = constraintDslMapper.selectActiveByCode(target.getCode());
        try {
            deactivate(current);
            target.setStatus(ConstraintDslStatus.ACTIVE.name());
            target.setRollbackOf(current == null ? null : (UUID) current.getId());
            target.setEffectiveFrom(OffsetDateTime.now(ZoneOffset.UTC));
            if (constraintDslMapper.updateById(target) == 0) {
                throw new IllegalStateException("DSL 版本已被并发修改: " + target.getCode());
            }
        } catch (RuntimeException exception) {
            restore(current);
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "DSL 回滚失败，已恢复原激活版本: " + target.getCode()
                            + " v" + (current == null ? "无" : current.getVersionNo())
                            + "，原因: " + exception.getMessage());
        }
        return toView(findOrThrow(id));
    }

    private void deactivate(ConstraintDsl active) {
        if (active == null) {
            return;
        }
        active.setStatus(ConstraintDslStatus.ROLLBACK.name());
        if (constraintDslMapper.update(null, Wrappers.<ConstraintDsl>lambdaUpdate()
                .eq(ConstraintDsl::getId, active.getId())
                .set(ConstraintDsl::getStatus, ConstraintDslStatus.ROLLBACK.name())) == 0) {
            throw new IllegalStateException("旧激活版本已被并发修改: " + active.getCode());
        }
    }

    private void restore(ConstraintDsl previous) {
        if (previous == null) {
            return;
        }
        previous.setStatus(ConstraintDslStatus.ACTIVE.name());
        constraintDslMapper.update(null, Wrappers.<ConstraintDsl>lambdaUpdate()
                .eq(ConstraintDsl::getId, previous.getId())
                .set(ConstraintDsl::getStatus, ConstraintDslStatus.ACTIVE.name()));
    }

    private ConstraintDsl findOrThrow(UUID id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "DSL 主键不能为空");
        }
        ConstraintDsl entity = constraintDslMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "DSL 版本不存在: " + id);
        }
        return entity;
    }

    private ConstraintDslDocument parseOrThrow(String yamlText) {
        try {
            return parser.load(yamlText);
        } catch (DslValidationException exception) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, formatIssues(exception.getReport()));
        }
    }

    /**
     * 激活/回滚前校验目标 DSL 是否能被求解引擎忠实执行。
     *
     * <p>引擎没有对应语义的参数（例如 unit=HOUR、groupBy=MATERIAL、metric=TASK_COUNT、
     * maxPriority）必须在这里以 42200 点名拒绝；静默忽略会让用户误以为约束已生效。</p>
     *
     * @param target 目标版本
     * @param document 已解析的 DSL 文档
     */
    private void requireEngineSupported(ConstraintDsl target, ConstraintDslDocument document) {
        ConstraintDslEngineMapping.Analysis analysis =
                ConstraintDslEngineMapping.analyze(compiler.compile(document));
        if (!analysis.supported()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "约束 DSL code=" + target.getCode() + " v" + target.getVersionNo()
                            + " 无法接入求解引擎: " + String.join("；", analysis.problems()));
        }
    }

    private static String formatIssues(DslValidationReport report) {
        StringBuilder builder = new StringBuilder("非法 DSL");
        for (DslValidationIssue issue : report.issues()) {
            builder.append("; ").append(issue.format());
        }
        return builder.toString();
    }

    private List<Map<String, Object>> compiledSpec(ConstraintDslDocument document) {
        List<Map<String, Object>> spec = new ArrayList<>();
        for (ConstraintSpec constraint : compiler.compile(document)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", constraint.code());
            item.put("type", constraint.type());
            item.put("weight", constraint.weight());
            item.put("hard", constraint.hard());
            item.put("phase", constraint.phase());
            item.put("params", constraint.params());
            spec.add(item);
        }
        return spec;
    }

    private static String checksum(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private static ConstraintDslView toView(ConstraintDsl entity) {
        return new ConstraintDslView((UUID) entity.getId(), entity.getCode(), entity.getVersionNo(),
                entity.getStatus(), entity.getChecksum(), entity.getRollbackOf(),
                entity.getEffectiveFrom(), entity.getYamlText(),
                entity.compiledSpecCopy(), entity.getCreatedAt());
    }
}
