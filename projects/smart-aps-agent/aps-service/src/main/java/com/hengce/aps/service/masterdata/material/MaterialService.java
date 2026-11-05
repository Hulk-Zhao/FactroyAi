package com.hengce.aps.service.masterdata.material;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.masterdata.material.MaterialType;
import com.hengce.aps.infrastructure.persistence.material.Material;
import com.hengce.aps.infrastructure.persistence.material.MaterialMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 物料主数据服务：CRUD、分页筛选、编码唯一性、软删除与引用校验。
 *
 * <p>筛选语义对齐 contract-freeze C10：{@code code}/{@code name} 之间 OR（模糊、忽略大小写），
 * 与 {@code type}/{@code categoryId} 之间 AND。空白关键字视为未传。</p>
 */
@Service
public class MaterialService {

    /**
     * code 模糊匹配 SQL，{0} 为转义后的关键字，参数化绑定避免注入。
     */
    private static final String CODE_LIKE_SQL = "LOWER(code) LIKE LOWER(CONCAT('%', {0}, '%'))";

    /**
     * name 模糊匹配 SQL。
     */
    private static final String NAME_LIKE_SQL = "LOWER(name) LIKE LOWER(CONCAT('%', {0}, '%'))";

    private static final String DUPLICATE_CODE_MESSAGE = "物料编码已存在: ";

    private final MaterialMapper materialMapper;

    public MaterialService(MaterialMapper materialMapper) {
        this.materialMapper = materialMapper;
    }

    /**
     * 分页查询物料。
     *
     * @param query 分页参数，可为 null；内部会 clamp
     * @param code 编码关键字（模糊、忽略大小写、与 name OR）
     * @param name 名称关键字（模糊、忽略大小写、与 code OR）
     * @param type 物料类型（精确、AND）
     * @param categoryId 分类主键（精确、AND）
     * @return 分页结果
     */
    public PageResult<MaterialView> list(PageQuery query, String code, String name,
                                         MaterialType type, UUID categoryId) {
        PageQuery normalized = query == null ? new PageQuery() : query;
        normalized.normalize();
        LambdaQueryWrapper<Material> wrapper = buildListWrapper(code, name, type, categoryId);
        applySort(wrapper, normalized.getSort());
        Page<Material> page = new Page<>(normalized.getPage(), normalized.getSize());
        Page<Material> result = materialMapper.selectPage(page, wrapper);
        List<MaterialView> items = result.getRecords().stream().map(MaterialView::from).toList();
        return new PageResult<>(items, normalized.getPage(), normalized.getSize(), result.getTotal(), 0);
    }

    /**
     * 按编码查询物料详情。
     *
     * @param code 物料编码
     * @return 物料视图
     */
    public MaterialView get(String code) {
        return MaterialView.from(findByCodeOrThrow(code));
    }

    /**
     * 创建物料。
     *
     * @param command 创建命令
     * @return 创建后的物料视图
     */
    public MaterialView create(MaterialCreateCommand command) {
        if (existsByCode(command.code())) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, DUPLICATE_CODE_MESSAGE + command.code());
        }
        Material material = new Material();
        // BaseEntity 使用 IdType.INPUT，UUID 由应用层生成，与 V1 的 gen_random_uuid() 默认值等价
        material.setId(UUID.randomUUID());
        applyCreate(material, command);
        try {
            materialMapper.insert(material);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.IDEMPOTENCY_CONFLICT, DUPLICATE_CODE_MESSAGE + command.code());
        }
        return MaterialView.from(material);
    }

    /**
     * 更新物料；物料编码不可修改。
     *
     * @param code 路径中的物料编码
     * @param command 更新命令
     * @return 更新后的物料视图
     */
    public MaterialView update(String code, MaterialUpdateCommand command) {
        Material existing = findByCodeOrThrow(code);
        if (command.code() != null && !command.code().equals(code)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "物料编码不允许修改");
        }
        if (command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (!Objects.equals(existing.getVersion(), command.version())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "物料已被其他请求修改，请刷新后重试");
        }
        applyUpdate(existing, command);
        int rows = materialMapper.updateById(existing);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "物料已被其他请求修改，请刷新后重试");
        }
        Material latest = findByCode(code);
        return MaterialView.from(latest == null ? existing : latest);
    }

    /**
     * 逻辑删除物料；被 BOM、订单或工艺路线引用时拒绝。
     *
     * @param code 物料编码
     */
    public void delete(String code) {
        Material existing = findByCodeOrThrow(code);
        UUID materialId = (UUID) existing.getId();
        List<String> references = new ArrayList<>();
        if (materialMapper.countBomReferences(materialId) > 0) {
            references.add("BOM");
        }
        if (materialMapper.countBomItemReferences(materialId) > 0) {
            references.add("BOM 子件");
        }
        if (materialMapper.countOrderReferences(materialId) > 0) {
            references.add("订单");
        }
        if (materialMapper.countRoutingReferences(materialId) > 0) {
            references.add("工艺路线");
        }
        if (!references.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "物料已被 " + String.join("、", references) + " 引用，无法删除: " + code);
        }
        materialMapper.deleteById(existing.getId());
    }

    /**
     * 构造列表查询条件，供单测直接校验 OR/AND 语义。
     *
     * @param code 编码关键字
     * @param name 名称关键字
     * @param type 物料类型
     * @param categoryId 分类主键
     * @return 查询条件
     */
    LambdaQueryWrapper<Material> buildListWrapper(String code, String name, MaterialType type, UUID categoryId) {
        LambdaQueryWrapper<Material> wrapper = Wrappers.lambdaQuery();
        String codeKeyword = trimToNull(code);
        String nameKeyword = trimToNull(name);
        if (codeKeyword != null && nameKeyword != null) {
            wrapper.and(nested -> nested
                    .apply(CODE_LIKE_SQL, escapeLike(codeKeyword))
                    .or()
                    .apply(NAME_LIKE_SQL, escapeLike(nameKeyword)));
        } else if (codeKeyword != null) {
            wrapper.apply(CODE_LIKE_SQL, escapeLike(codeKeyword));
        } else if (nameKeyword != null) {
            wrapper.apply(NAME_LIKE_SQL, escapeLike(nameKeyword));
        }
        if (type != null) {
            wrapper.eq(Material::getType, type);
        }
        if (categoryId != null) {
            wrapper.eq(Material::getCategoryId, categoryId);
        }
        return wrapper;
    }

    private Material findByCodeOrThrow(String code) {
        Material material = findByCode(code);
        if (material == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "物料不存在: " + code);
        }
        return material;
    }

    private Material findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return materialMapper.selectOne(Wrappers.<Material>lambdaQuery().eq(Material::getCode, code.trim()));
    }

    private boolean existsByCode(String code) {
        return materialMapper.selectCount(Wrappers.<Material>lambdaQuery().eq(Material::getCode, code)) > 0;
    }

    private void applyCreate(Material material, MaterialCreateCommand command) {
        material.setCode(command.code());
        material.setName(command.name());
        material.setType(command.type());
        material.setSpec(command.spec());
        material.setUnit(command.unit());
        material.setCategoryId(command.categoryId());
        material.setSafetyStock(command.safetyStock());
        material.setMoq(command.moq());
        material.setLeadTimeDays(command.leadTimeDays());
        material.setDefaultSupplierId(command.defaultSupplierId());
        material.setExtAttrs(copyAttributes(command.extAttrs()));
        material.setIndustryAttrs(copyAttributes(command.industryAttrs()));
    }

    private void applyUpdate(Material material, MaterialUpdateCommand command) {
        if (command.name() != null) {
            material.setName(command.name());
        }
        if (command.type() != null) {
            material.setType(command.type());
        }
        if (command.spec() != null) {
            material.setSpec(command.spec());
        }
        if (command.unit() != null) {
            material.setUnit(command.unit());
        }
        if (command.categoryId() != null) {
            material.setCategoryId(command.categoryId());
        }
        if (command.safetyStock() != null) {
            material.setSafetyStock(command.safetyStock());
        }
        if (command.moq() != null) {
            material.setMoq(command.moq());
        }
        if (command.leadTimeDays() != null) {
            material.setLeadTimeDays(command.leadTimeDays());
        }
        if (command.defaultSupplierId() != null) {
            material.setDefaultSupplierId(command.defaultSupplierId());
        }
        if (command.extAttrs() != null) {
            material.setExtAttrs(copyAttributes(command.extAttrs()));
        }
        if (command.industryAttrs() != null) {
            material.setIndustryAttrs(copyAttributes(command.industryAttrs()));
        }
    }

    private Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        return attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    private void applySort(LambdaQueryWrapper<Material> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByAsc(Material::getCode);
            return;
        }
        String[] tokens = sort.split(",");
        boolean applied = false;
        for (int i = 0; i + 1 < tokens.length; i += 2) {
            if (applySortField(wrapper, tokens[i].trim(), tokens[i + 1].trim())) {
                applied = true;
            }
        }
        if (!applied) {
            wrapper.orderByAsc(Material::getCode);
        }
    }

    private boolean applySortField(LambdaQueryWrapper<Material> wrapper, String field, String direction) {
        boolean ascending = !"desc".equalsIgnoreCase(direction);
        switch (field) {
            case "code":
                wrapper.orderBy(true, ascending, Material::getCode);
                return true;
            case "name":
                wrapper.orderBy(true, ascending, Material::getName);
                return true;
            case "type":
                wrapper.orderBy(true, ascending, Material::getType);
                return true;
            case "categoryId":
                wrapper.orderBy(true, ascending, Material::getCategoryId);
                return true;
            case "createdAt":
                wrapper.orderBy(true, ascending, Material::getCreatedAt);
                return true;
            case "updatedAt":
                wrapper.orderBy(true, ascending, Material::getUpdatedAt);
                return true;
            default:
                return false;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
