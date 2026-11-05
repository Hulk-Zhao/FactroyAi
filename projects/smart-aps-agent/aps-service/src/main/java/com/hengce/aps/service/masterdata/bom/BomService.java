package com.hengce.aps.service.masterdata.bom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomItem;
import com.hengce.aps.infrastructure.persistence.bom.BomItemMapper;
import com.hengce.aps.infrastructure.persistence.bom.BomMapper;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import com.hengce.aps.service.mrp.MrpSuggestionCache;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BOM 主数据服务：列表筛选、详情、新建、激活、多级树与删除引用校验。
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li>C6：BOM 对外一律用 UUID 寻址。</li>
 *   <li>C10：{@code parentMaterialCode} 与 {@code status} 均为精确匹配且 AND。</li>
 *   <li>C8：同一父件最多一个 ACTIVE；激活时事务内收敛旧 ACTIVE，并由部分唯一索引
 *       {@code uq_bom_one_active_per_parent} 兜底，冲突返回 40901。</li>
 *   <li>schema.md 5.4：{@code bom_item} 为明细事实来源，{@code bom.items} 为渲染快照，写入时同步刷新。</li>
 * </ul>
 */
@Service
public class BomService {

    private static final int MAX_TREE_DEPTH = 5;

    private static final BigDecimal MAX_SCRAP_RATE = new BigDecimal("100");

    private static final int FIRST_SEQ_NO = 1;

    /**
     * PostgreSQL 唯一约束冲突的 SQLSTATE（23505）。
     *
     * <p>MyBatis-Spring 正常会翻译成 {@link DuplicateKeyException}，但 MyBatis-Plus 的
     * SqlSessionTemplate 在部分装配下直接抛出 {@link PersistenceException} / 未分类
     * {@link DataAccessException}，因此需要按 SQLSTATE 兜底识别，避免唯一冲突漏成 500。</p>
     */
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final BomMapper bomMapper;

    private final BomItemMapper bomItemMapper;

    private final MaterialMapper materialMapper;

    private final MrpSuggestionCache suggestionCache;

    /**
     * 构造服务。
     *
     * @param bomMapper BOM 主表 Mapper
     * @param bomItemMapper BOM 子件 Mapper
     * @param materialMapper 物料 Mapper（只读，用于编码寻址与响应补全）
     * @param suggestionCache 采购建议缓存（BOM 变化后主动失效）
     */
    public BomService(BomMapper bomMapper, BomItemMapper bomItemMapper, MaterialMapper materialMapper,
                      MrpSuggestionCache suggestionCache) {
        this.bomMapper = bomMapper;
        this.bomItemMapper = bomItemMapper;
        this.materialMapper = materialMapper;
        this.suggestionCache = suggestionCache;
    }

    /**
     * 分页查询 BOM。
     *
     * @param query 分页参数，可为 null
     * @param parentMaterialCode 父件物料编码（精确，AND）
     * @param status BOM 状态（精确，AND）
     * @return 分页结果，每行包含 items
     */
    public PageResult<BomView> list(PageQuery query, String parentMaterialCode, BomStatus status) {
        PageQuery normalized = (query == null ? new PageQuery() : query).normalize();
        UUID parentMaterialId = null;
        if (parentMaterialCode != null && !parentMaterialCode.isBlank()) {
            Material parent = findMaterialByCode(parentMaterialCode.trim());
            if (parent == null) {
                return new PageResult<>(List.of(), normalized.getPage(), normalized.getSize(), 0, 0);
            }
            parentMaterialId = (UUID) parent.getId();
        }
        LambdaQueryWrapper<Bom> wrapper = buildListWrapper(parentMaterialId, status);
        applySort(wrapper, normalized.getSort());
        Page<Bom> page = bomMapper.selectPage(new Page<>(normalized.getPage(), normalized.getSize()), wrapper);
        List<Bom> records = page.getRecords();
        List<BomView> views = buildViews(records);
        return new PageResult<>(views, normalized.getPage(), normalized.getSize(), page.getTotal(), 0);
    }

    /**
     * 按 UUID 查询 BOM 详情；items 为该 BOM 的稳定来源。
     *
     * @param bomId BOM 主键
     * @return BOM 详情
     */
    public BomView get(UUID bomId) {
        Bom bom = requireExisting(bomId);
        List<BomItem> items = findItems(bomId);
        return buildView(bom, items, loadCodes(List.of(bom), items));
    }

    /**
     * 新建 BOM：默认 DRAFT，同父件同 bomVersion 冲突返回 40900。
     *
     * @param command 新建命令
     * @return 新建后的 BOM
     */
    @Transactional(rollbackFor = Exception.class)
    public BomView create(BomCreateCommand command) {
        validateHeader(command);
        Material parent = requireParentMaterial(command.parentMaterialCode());
        Map<String, Material> components = loadComponents(command.items());
        UUID parentMaterialId = (UUID) parent.getId();
        long duplicated = bomMapper.selectCount(Wrappers.<Bom>lambdaQuery()
                .eq(Bom::getParentMaterialId, parentMaterialId)
                .eq(Bom::getBomVersion, command.bomVersion().trim()));
        if (duplicated > 0) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "同父件同版本 BOM 已存在: " + command.parentMaterialCode() + "/" + command.bomVersion());
        }
        UUID bomId = UUID.randomUUID();
        Map<UUID, String> codes = new HashMap<>();
        codes.put(parentMaterialId, parent.getCode());
        List<BomItem> itemEntities = new ArrayList<>();
        int seqNo = FIRST_SEQ_NO;
        for (BomItemCommand itemCommand : command.items()) {
            validateItem(itemCommand);
            Material child = components.get(itemCommand.materialCode().trim());
            BomItem item = new BomItem();
            item.setId(UUID.randomUUID());
            item.setBomId(bomId);
            item.setChildMaterialId((UUID) child.getId());
            item.setQtyPerUnit(itemCommand.qtyPerUnit());
            item.setScrapRate(itemCommand.scrapRate() == null ? BigDecimal.ZERO : itemCommand.scrapRate());
            item.setPosition(itemCommand.position());
            item.setSubstitutes(substitutesToJson(itemCommand.substitutes(), components));
            item.setIsPhantom(Boolean.TRUE.equals(itemCommand.isPhantom()));
            item.setLevel(1);
            item.setSeqNo(seqNo);
            seqNo++;
            codes.put((UUID) child.getId(), child.getCode());
            itemEntities.add(item);
        }
        Bom bom = new Bom();
        bom.setId(bomId);
        bom.setParentMaterialId(parentMaterialId);
        bom.setBomVersion(command.bomVersion().trim());
        bom.setEffectiveDate(command.effectiveDate());
        bom.setExpiryDate(command.expiryDate());
        bom.setStatus(BomStatus.DRAFT.name());
        bom.setIsPanel(Boolean.TRUE.equals(command.isPanel()));
        bom.setPanelSize(command.panelSize());
        bom.setItems(buildSnapshot(itemEntities, codes));
        try {
            bomMapper.insert(bom);
        } catch (DuplicateKeyException exception) {
            throw duplicateVersionConflict(command);
        } catch (PersistenceException | DataAccessException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            throw duplicateVersionConflict(command);
        }
        for (BomItem item : itemEntities) {
            bomItemMapper.insert(item);
        }
        suggestionCache.invalidate(TenantContext.getTenantId());
        return buildView(bom, itemEntities, codes);
    }

    /**
     * 激活 BOM：自身至少一条明细；同父件旧 ACTIVE 收敛为 INACTIVE，冲突返回 40901。
     *
     * @param bomId BOM 主键
     * @return 激活后的 BOM
     */
    @Transactional(rollbackFor = Exception.class)
    public BomView activate(UUID bomId) {
        Bom target = requireExisting(bomId);
        if (BomStatus.ACTIVE.name().equals(target.getStatus())) {
            return get(bomId);
        }
        if (bomItemMapper.selectCount(Wrappers.<BomItem>lambdaQuery().eq(BomItem::getBomId, bomId)) == 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "BOM 至少需要一条明细才能激活: " + bomId);
        }
        try {
            LambdaUpdateWrapper<Bom> deactivate = Wrappers.lambdaUpdate();
            deactivate.set(Bom::getStatus, BomStatus.INACTIVE.name())
                    .set(Bom::getUpdatedAt, OffsetDateTime.now(ZoneOffset.UTC))
                    .eq(Bom::getParentMaterialId, target.getParentMaterialId())
                    .eq(Bom::getStatus, BomStatus.ACTIVE.name())
                    .ne(Bom::getId, target.getId());
            bomMapper.update(null, deactivate);
            target.setStatus(BomStatus.ACTIVE.name());
            int affected = bomMapper.updateById(target);
            if (affected == 0) {
                throw new BizException(ErrorCode.STATE_CONFLICT, "BOM 已被其他请求修改，请刷新后重试");
            }
        } catch (DuplicateKeyException exception) {
            throw activeConflict(bomId);
        } catch (PersistenceException | DataAccessException exception) {
            if (!isUniqueViolation(exception)) {
                throw exception;
            }
            throw activeConflict(bomId);
        }
        suggestionCache.invalidate(TenantContext.getTenantId());
        return get(bomId);
    }

    /**
     * 多级 BOM 树：默认最大 5 层，超过截断；检测到环返回 42200 并给出环路径。
     *
     * @param bomId 根 BOM 主键
     * @return 树根节点
     */
    public BomTreeNodeView tree(UUID bomId) {
        Bom root = requireExisting(bomId);
        Material rootMaterial = findMaterial(root.getParentMaterialId());
        String rootCode = codeOf(rootMaterial, root.getParentMaterialId());
        List<UUID> idPath = new ArrayList<>();
        idPath.add(root.getParentMaterialId());
        List<String> codePath = new ArrayList<>();
        codePath.add(rootCode);
        List<BomTreeNodeView> children = expand(root, 1, idPath, codePath);
        return new BomTreeNodeView(bomId, rootCode,
                rootMaterial == null ? null : rootMaterial.getName(), null, 1, children);
    }

    /**
     * 逻辑删除 BOM；被订单或其它 BOM 子件行引用时返回 42200。
     *
     * @param bomId BOM 主键
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID bomId) {
        Bom bom = requireExisting(bomId);
        UUID parentMaterialId = bom.getParentMaterialId();
        if (bomMapper.countOrderReferences(parentMaterialId) > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "BOM 父件已被订单引用，无法删除: " + bomId);
        }
        long childReferences = bomItemMapper.selectCount(Wrappers.<BomItem>lambdaQuery()
                .eq(BomItem::getChildMaterialId, parentMaterialId));
        if (childReferences > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "BOM 父件被其它 BOM 作为子件引用，无法删除: " + bomId);
        }
        bomItemMapper.delete(Wrappers.<BomItem>lambdaQuery().eq(BomItem::getBomId, bomId));
        bomMapper.deleteById(bomId);
        suggestionCache.invalidate(TenantContext.getTenantId());
    }

    /**
     * 构造列表查询条件，供单测直接校验 C10 的 AND 语义。
     *
     * @param parentMaterialId 父件物料主键，可空
     * @param status BOM 状态，可空
     * @return 查询条件
     */
    LambdaQueryWrapper<Bom> buildListWrapper(UUID parentMaterialId, BomStatus status) {
        LambdaQueryWrapper<Bom> wrapper = Wrappers.lambdaQuery();
        if (parentMaterialId != null) {
            wrapper.eq(Bom::getParentMaterialId, parentMaterialId);
        }
        if (status != null) {
            wrapper.eq(Bom::getStatus, status.name());
        }
        return wrapper;
    }

    private List<BomTreeNodeView> expand(Bom bom, int level, List<UUID> idPath, List<String> codePath) {
        List<BomTreeNodeView> nodes = new ArrayList<>();
        if (level >= MAX_TREE_DEPTH) {
            return nodes;
        }
        for (BomItem item : findItems((UUID) bom.getId())) {
            UUID childId = item.getChildMaterialId();
            Material childMaterial = findMaterial(childId);
            String childCode = codeOf(childMaterial, childId);
            if (idPath.contains(childId)) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "BOM 存在循环引用: " + String.join(" -> ", codePath) + " -> " + childCode);
            }
            Bom childBom = findActiveByParent(childId);
            List<BomTreeNodeView> grandchildren = List.of();
            if (childBom != null) {
                idPath.add(childId);
                codePath.add(childCode);
                grandchildren = expand(childBom, level + 1, idPath, codePath);
                idPath.remove(idPath.size() - 1);
                codePath.remove(codePath.size() - 1);
            }
            nodes.add(new BomTreeNodeView(childBom == null ? null : (UUID) childBom.getId(), childCode,
                    childMaterial == null ? null : childMaterial.getName(), item.getQtyPerUnit(),
                    level + 1, grandchildren));
        }
        return nodes;
    }

    private List<BomView> buildViews(List<Bom> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<UUID> bomIds = records.stream().map(record -> (UUID) record.getId()).toList();
        List<BomItem> allItems = bomItemMapper.selectList(Wrappers.<BomItem>lambdaQuery()
                .in(BomItem::getBomId, bomIds));
        Map<UUID, List<BomItem>> itemsByBom = new HashMap<>();
        for (BomItem item : allItems) {
            itemsByBom.computeIfAbsent(item.getBomId(), key -> new ArrayList<>()).add(item);
        }
        Map<UUID, String> codes = loadCodes(records, allItems);
        List<BomView> views = new ArrayList<>();
        for (Bom bom : records) {
            UUID bomId = (UUID) bom.getId();
            views.add(buildView(bom, itemsByBom.getOrDefault(bomId, List.of()), codes));
        }
        return views;
    }

    private BomView buildView(Bom bom, List<BomItem> items, Map<UUID, String> codes) {
        List<BomItemView> itemViews = items.stream()
                .sorted(Comparator.comparing(BomItem::getSeqNo, Comparator.nullsLast(Integer::compareTo)))
                .map(item -> new BomItemView(codes.get(item.getChildMaterialId()), item.getQtyPerUnit(),
                        item.getScrapRate(), item.getPosition(), substituteCodes(item.getSubstitutes()),
                        item.getIsPhantom()))
                .toList();
        return new BomView((UUID) bom.getId(), codes.get(bom.getParentMaterialId()), bom.getBomVersion(),
                bom.getEffectiveDate(), bom.getExpiryDate(), BomStatus.valueOf(bom.getStatus()),
                bom.getIsPanel(), bom.getPanelSize(), itemViews, bom.getVersion());
    }

    private Map<UUID, String> loadCodes(List<Bom> boms, List<BomItem> items) {
        Set<UUID> materialIds = new HashSet<>();
        for (Bom bom : boms) {
            materialIds.add(bom.getParentMaterialId());
        }
        for (BomItem item : items) {
            materialIds.add(item.getChildMaterialId());
        }
        materialIds.remove(null);
        if (materialIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> codes = new HashMap<>();
        for (Material material : materialMapper.selectBatchIds(materialIds)) {
            codes.put((UUID) material.getId(), material.getCode());
        }
        return codes;
    }

    private List<BomItem> findItems(UUID bomId) {
        return bomItemMapper.selectList(Wrappers.<BomItem>lambdaQuery()
                .eq(BomItem::getBomId, bomId)
                .orderByAsc(BomItem::getSeqNo));
    }

    private Bom findActiveByParent(UUID parentMaterialId) {
        List<Bom> boms = bomMapper.selectList(Wrappers.<Bom>lambdaQuery()
                .eq(Bom::getParentMaterialId, parentMaterialId)
                .eq(Bom::getStatus, BomStatus.ACTIVE.name()));
        if (boms.size() > 1) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "同一父件存在多个 ACTIVE BOM: " + parentMaterialId);
        }
        return boms.isEmpty() ? null : boms.get(0);
    }

    private Bom requireExisting(UUID bomId) {
        if (bomId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "BOM id 不能为空");
        }
        Bom bom = bomMapper.selectById(bomId);
        if (bom == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "BOM 不存在: " + bomId);
        }
        return bom;
    }

    private void validateHeader(BomCreateCommand command) {
        if (command.bomVersion() == null || command.bomVersion().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "bomVersion 不能为空");
        }
        if (command.effectiveDate() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "effectiveDate 不能为空");
        }
        if (command.expiryDate() != null && command.expiryDate().isBefore(command.effectiveDate())) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "expiryDate 不能早于 effectiveDate");
        }
        if (command.items() == null || command.items().isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "BOM 至少需要一条子件明细");
        }
    }

    private void validateItem(BomItemCommand item) {
        if (item.materialCode() == null || item.materialCode().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "子件物料编码不能为空");
        }
        if (item.qtyPerUnit() == null || item.qtyPerUnit().signum() <= 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "子件 qtyPerUnit 必须大于 0: " + item.materialCode());
        }
        if (item.scrapRate() != null
                && (item.scrapRate().signum() < 0 || item.scrapRate().compareTo(MAX_SCRAP_RATE) > 0)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "子件 scrapRate 必须在 0–100 之间: " + item.materialCode());
        }
    }

    private Material requireParentMaterial(String parentMaterialCode) {
        if (parentMaterialCode == null || parentMaterialCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "parentMaterialCode 不能为空");
        }
        Material parent = findMaterialByCode(parentMaterialCode.trim());
        if (parent == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "父件物料不存在: " + parentMaterialCode);
        }
        return parent;
    }

    private Map<String, Material> loadComponents(List<BomItemCommand> items) {
        Set<String> codes = new LinkedHashSet<>();
        for (BomItemCommand item : items) {
            if (item.materialCode() != null && !item.materialCode().isBlank()) {
                codes.add(item.materialCode().trim());
            }
            if (item.substitutes() != null) {
                for (String substitute : item.substitutes()) {
                    if (substitute != null && !substitute.isBlank()) {
                        codes.add(substitute.trim());
                    }
                }
            }
        }
        if (codes.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "BOM 至少需要一条有效子件明细");
        }
        Map<String, Material> byCode = new HashMap<>();
        for (Material material : materialMapper.selectList(Wrappers.<Material>lambdaQuery()
                .in(Material::getCode, codes))) {
            byCode.put(material.getCode(), material);
        }
        for (String code : codes) {
            if (!byCode.containsKey(code)) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "子件物料不存在: " + code);
            }
        }
        return byCode;
    }

    private List<Map<String, Object>> substitutesToJson(List<String> substituteCodes,
                                                        Map<String, Material> components) {
        List<Map<String, Object>> substitutes = new ArrayList<>();
        if (substituteCodes == null) {
            return substitutes;
        }
        int priority = FIRST_SEQ_NO;
        for (String code : substituteCodes) {
            if (code == null || code.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "替代料物料编码不能为空");
            }
            Material material = components.get(code.trim());
            if (material == null) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "替代料物料不存在: " + code);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("materialId", ((UUID) material.getId()).toString());
            entry.put("materialCode", material.getCode());
            entry.put("priority", priority);
            entry.put("ratio", "1:1");
            substitutes.add(entry);
            priority++;
        }
        return substitutes;
    }

    private List<Map<String, Object>> buildSnapshot(List<BomItem> items, Map<UUID, String> codes) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (BomItem item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("materialId", item.getChildMaterialId().toString());
            entry.put("materialCode", codes.get(item.getChildMaterialId()));
            entry.put("qtyPerUnit", item.getQtyPerUnit());
            entry.put("scrapRate", item.getScrapRate());
            entry.put("position", item.getPosition());
            entry.put("isPhantom", item.getIsPhantom());
            entry.put("level", item.getLevel());
            entry.put("seqNo", item.getSeqNo());
            entry.put("substitutes", item.getSubstitutes());
            snapshot.add(entry);
        }
        return snapshot;
    }

    private List<String> substituteCodes(List<Map<String, Object>> substitutes) {
        if (substitutes == null || substitutes.isEmpty()) {
            return List.of();
        }
        return substitutes.stream()
                .map(entry -> entry.get("materialCode"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    private Material findMaterialByCode(String code) {
        return materialMapper.selectOne(Wrappers.<Material>lambdaQuery().eq(Material::getCode, code));
    }

    private Material findMaterial(UUID materialId) {
        return materialId == null ? null : materialMapper.selectById(materialId);
    }

    private String codeOf(Material material, UUID fallbackId) {
        return material == null ? fallbackId.toString() : material.getCode();
    }

    private BizException duplicateVersionConflict(BomCreateCommand command) {
        return new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                "同父件同版本 BOM 已存在，请刷新后重试: "
                        + command.parentMaterialCode() + "/" + command.bomVersion());
    }

    private BizException activeConflict(UUID bomId) {
        return new BizException(ErrorCode.STATE_CONFLICT,
                "该父件已存在 ACTIVE BOM，请刷新后重试: " + bomId);
    }

    /**
     * 判断异常链中是否包含 PostgreSQL 唯一约束冲突（SQLSTATE 23505）。
     *
     * <p>仅用于把这一处可预期的唯一冲突翻译成业务冲突码；不为真的异常会原样抛回全局处理器。</p>
     *
     * @param throwable 捕获到的异常
     * @return 是否为唯一约束冲突
     */
    private boolean isUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void applySort(LambdaQueryWrapper<Bom> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByDesc(Bom::getEffectiveDate).orderByAsc(Bom::getBomVersion);
            return;
        }
        boolean applied = false;
        String[] tokens = sort.split(",");
        for (int index = 0; index + 1 < tokens.length; index += 2) {
            if (applySortField(wrapper, tokens[index].trim(), tokens[index + 1].trim())) {
                applied = true;
            }
        }
        if (!applied) {
            wrapper.orderByDesc(Bom::getEffectiveDate).orderByAsc(Bom::getBomVersion);
        }
    }

    private boolean applySortField(LambdaQueryWrapper<Bom> wrapper, String field, String direction) {
        boolean ascending = !"desc".equalsIgnoreCase(direction);
        switch (field) {
            case "bomVersion":
                wrapper.orderBy(true, ascending, Bom::getBomVersion);
                return true;
            case "effectiveDate":
                wrapper.orderBy(true, ascending, Bom::getEffectiveDate);
                return true;
            case "status":
                wrapper.orderBy(true, ascending, Bom::getStatus);
                return true;
            case "createdAt":
                wrapper.orderBy(true, ascending, Bom::getCreatedAt);
                return true;
            case "updatedAt":
                wrapper.orderBy(true, ascending, Bom::getUpdatedAt);
                return true;
            default:
                return false;
        }
    }
}
