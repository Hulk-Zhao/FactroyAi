package com.hengce.aps.service.process.template;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.mapper.process.template.OperationTemplateMapper;
import com.hengce.aps.infrastructure.persistence.mapper.process.template.RoutingNodeReferenceMapper;
import com.hengce.aps.infrastructure.persistence.process.OperationTemplate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 工序模版服务：CRUD、分页过滤、编码唯一、逻辑删除与引用删除拦截。
 *
 * <p>约定：</p>
 * <ul>
 *   <li>唯一键与 {@code uq_operation_template_code_version} 对齐：{@code tenantId + code + templateVersion}。</li>
 *   <li>重复编码返回 {@link ErrorCode#STATE_CONFLICT}（40901）。</li>
 *   <li>被 routing_node 引用时禁止删除，返回 {@link ErrorCode#UNPROCESSABLE_ENTITY}（42200）。</li>
 *   <li>更新携带乐观锁 version，版本不一致返回 {@link ErrorCode#STATE_CONFLICT}。</li>
 *   <li>分页 size 按 {@link PageQuery#normalize()} 夹取到 1–200，排序字段走白名单。</li>
 * </ul>
 */
@Service
public class OperationTemplateService {

    private static final int DEFAULT_TEMPLATE_VERSION = 1;

    private static final int MAX_CODE_LENGTH = 64;

    private static final int MAX_NAME_LENGTH = 256;

    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("code", "t.code"),
            Map.entry("name", "t.name"),
            Map.entry("templateVersion", "t.template_version"),
            Map.entry("standardSeconds", "t.standard_seconds"),
            Map.entry("setupSeconds", "t.setup_seconds"),
            Map.entry("teardownSeconds", "t.teardown_seconds"),
            Map.entry("createdAt", "t.created_at"),
            Map.entry("updatedAt", "t.updated_at"),
            Map.entry("version", "t.version"));

    private static final Set<String> RESOURCE_TYPES = Set.of("PRIMARY", "SECONDARY", "HUMAN", "TOOL");

    private final OperationTemplateMapper operationTemplateMapper;

    private final RoutingNodeReferenceMapper routingNodeReferenceMapper;

    /**
     * 构造服务。
     *
     * @param operationTemplateMapper 工序模版 Mapper
     * @param routingNodeReferenceMapper 路线节点引用只读 Mapper
     */
    public OperationTemplateService(OperationTemplateMapper operationTemplateMapper,
                                    RoutingNodeReferenceMapper routingNodeReferenceMapper) {
        this.operationTemplateMapper = operationTemplateMapper;
        this.routingNodeReferenceMapper = routingNodeReferenceMapper;
    }

    /**
     * 分页查询工序模版。
     *
     * @param query 分页与排序参数
     * @param code 编码模糊条件
     * @param name 名称模糊条件
     * @param resourceType 主资源类型 PRIMARY/SECONDARY/HUMAN/TOOL
     * @return 分页结果
     */
    public PageResult<OperationTemplate> list(PageQuery query, String code, String name, String resourceType) {
        PageQuery normalized = (query == null ? new PageQuery() : query).normalize();
        SortSpec sort = resolveSort(normalized.getSort());
        Page<OperationTemplate> page = new Page<>(normalized.getPage(), normalized.getSize());
        IPage<OperationTemplate> result = operationTemplateMapper.selectPageByFilter(page,
                blankToNull(code), blankToNull(name), normalizeResourceType(resourceType),
                sort.column(), sort.direction());
        return PageResult.of(result.getRecords(), normalized, result.getTotal());
    }

    /**
     * 查询工序模版详情。
     *
     * @param id 主键
     * @return 工序模版
     * @throws BizException 记录不存在时返回 40400
     */
    public OperationTemplate get(UUID id) {
        OperationTemplate entity = operationTemplateMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工序模版不存在: " + id);
        }
        return entity;
    }

    /**
     * 新增工序模版。
     *
     * @param command 写入命令
     * @return 新建后的工序模版
     * @throws BizException 参数不合法返回 40000；编码重复返回 40901
     */
    public OperationTemplate create(OperationTemplateCommand command) {
        validate(command);
        Integer templateVersion = command.templateVersion() == null
                ? DEFAULT_TEMPLATE_VERSION : command.templateVersion();
        ensureCodeAvailable(command.code(), templateVersion, null);
        OperationTemplate entity = new OperationTemplate();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TenantContext.getTenantId());
        entity.setVersion(BaseEntity.DEFAULT_VERSION);
        entity.setDeleted(BaseEntity.DEFAULT_DELETED);
        applyFields(entity, command, templateVersion);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        operationTemplateMapper.insert(entity);
        return entity;
    }

    /**
     * 更新工序模版，使用乐观锁 version 防并发覆盖。
     *
     * @param id 主键
     * @param command 写入命令，version 必填
     * @return 更新后的工序模版
     * @throws BizException 参数不合法 40000、记录不存在 40400、编码重复或版本冲突 40901
     */
    public OperationTemplate update(UUID id, OperationTemplateCommand command) {
        validate(command);
        if (command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        OperationTemplate existing = get(id);
        Integer templateVersion = command.templateVersion() == null
                ? existing.getTemplateVersion() : command.templateVersion();
        ensureCodeAvailable(command.code(), templateVersion, id);
        OperationTemplate entity = new OperationTemplate();
        entity.setId(id);
        entity.setTenantId(existing.getTenantId());
        entity.setVersion(command.version());
        applyFields(entity, command, templateVersion);
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        int rows = operationTemplateMapper.update(entity);
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "工序模版已被其他请求修改，请刷新后重试");
        }
        entity.setVersion(command.version() + 1);
        return entity;
    }

    /**
     * 逻辑删除工序模版；被工艺路线节点引用时拒绝删除。
     *
     * @param id 主键
     * @throws BizException 记录不存在 40400、被引用 42200
     */
    public void delete(UUID id) {
        get(id);
        if (routingNodeReferenceMapper.countActiveReferences(id) > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "工序模版已被工艺路线节点引用，禁止删除: " + id);
        }
        int rows = operationTemplateMapper.deleteById(id);
        if (rows == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "工序模版不存在: " + id);
        }
    }

    private void validate(OperationTemplateCommand command) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        if (isBlank(command.code())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 不能为空");
        }
        if (command.code().length() > MAX_CODE_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 长度不能超过 64");
        }
        if (isBlank(command.name())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 不能为空");
        }
        if (command.name().length() > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 长度不能超过 256");
        }
        if (isNegative(command.standardSeconds()) || isNegative(command.setupSeconds())
                || isNegative(command.teardownSeconds())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "工时不能为负数");
        }
        if (command.templateVersion() != null && command.templateVersion() < DEFAULT_TEMPLATE_VERSION) {
            throw new BizException(ErrorCode.BAD_REQUEST, "templateVersion 最小为 1");
        }
    }

    private void ensureCodeAvailable(String code, Integer templateVersion, UUID excludeId) {
        long count = operationTemplateMapper.countByCodeVersion(code, templateVersion, excludeId);
        if (count > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "工序模版编码与版本已存在: " + code + "/v" + templateVersion);
        }
    }

    private void applyFields(OperationTemplate entity, OperationTemplateCommand command, Integer templateVersion) {
        entity.setCode(command.code());
        entity.setName(command.name());
        entity.setPrimaryResourceId(command.primaryResourceId());
        entity.setPrimaryResourceGroupId(command.primaryResourceGroupId());
        entity.setSecondaryResourceIds(command.secondaryResourceIds() == null
                ? List.of() : List.copyOf(command.secondaryResourceIds()));
        entity.setInputMaterialIds(command.inputMaterialIds() == null
                ? List.of() : List.copyOf(command.inputMaterialIds()));
        entity.setOutputMaterialId(command.outputMaterialId());
        entity.setStandardSeconds(command.standardSeconds() == null ? 0 : command.standardSeconds());
        entity.setSetupSeconds(command.setupSeconds() == null ? 0 : command.setupSeconds());
        entity.setTeardownSeconds(command.teardownSeconds() == null ? 0 : command.teardownSeconds());
        entity.setTemplateVersion(templateVersion);
    }

    private SortSpec resolveSort(String sort) {
        if (isBlank(sort)) {
            return new SortSpec(SORT_COLUMNS.get("createdAt"), "DESC");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        String column = SORT_COLUMNS.get(field);
        if (column == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sort 字段不支持: " + field);
        }
        String direction = "DESC";
        if (parts.length > 1 && !parts[1].isBlank()) {
            direction = parts[1].trim().toUpperCase(Locale.ROOT);
        }
        if (!"ASC".equals(direction) && !"DESC".equals(direction)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sort 方向仅支持 asc/desc");
        }
        return new SortSpec(column, direction);
    }

    private String normalizeResourceType(String resourceType) {
        if (isBlank(resourceType)) {
            return null;
        }
        String normalized = resourceType.trim().toUpperCase(Locale.ROOT);
        if (!RESOURCE_TYPES.contains(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资源类型不支持: " + resourceType);
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isNegative(Integer value) {
        return value != null && value < 0;
    }

    /**
     * 排序列与方向。
     *
     * @param column 数据库列名（含表别名）
     * @param direction ASC/DESC
     */
    private record SortSpec(String column, String direction) {
    }
}
