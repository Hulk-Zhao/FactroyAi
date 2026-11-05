package com.hengce.aps.service.masterdata.resource;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.masterdata.resource.CapacityMode;
import com.hengce.aps.domain.masterdata.resource.ResourceType;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroup;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroupMapper;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import com.hengce.aps.service.masterdata.ConstraintViolations;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 资源主数据服务：CRUD、分页筛选、编码唯一性、产能校验、软删除与引用校验。
 *
 * <p>筛选语义对齐 contract-freeze C10/C14：{@code code}/{@code name} 之间 OR（模糊、忽略大小写），
 * 与 {@code type}/{@code groupId} 之间 AND；M1 不提供 {@code status} 字段或参数。</p>
 *
 * <p>产能合法性对齐 C2：{@code efficiency} 默认 1.00、范围 0.5–1.5；{@code batch_capacity}
 * 在 BATCH_SECONDS 模式空按 1；UNIT_PER_HOUR 要求 {@code capacity_value × efficiency > 0}，
 * BATCH_SECONDS 要求 {@code capacity_value > 0}，非法组合返回 42200。</p>
 */
@Service
public class ResourceService {

    /**
     * code 模糊匹配 SQL，{0} 为转义后的关键字，参数化绑定避免注入。
     */
    private static final String CODE_LIKE_SQL = "LOWER(code) LIKE LOWER(CONCAT('%', {0}, '%'))";

    /**
     * name 模糊匹配 SQL。
     */
    private static final String NAME_LIKE_SQL = "LOWER(name) LIKE LOWER(CONCAT('%', {0}, '%'))";

    private static final String DUPLICATE_CODE_MESSAGE = "资源编码已存在: ";

    private static final BigDecimal DEFAULT_CAPACITY_VALUE = BigDecimal.ZERO;

    private static final BigDecimal DEFAULT_EFFICIENCY = BigDecimal.ONE;

    private static final BigDecimal MIN_EFFICIENCY = new BigDecimal("0.5");

    private static final BigDecimal MAX_EFFICIENCY = new BigDecimal("1.5");

    private static final int DEFAULT_BATCH_CAPACITY = 1;

    private static final int DEFAULT_SECONDS = 0;

    private static final int NO_AFFECTED_ROWS = 0;

    private static final String FK_RESOURCE_GROUP = "fk_resource_group";

    private static final String FK_RESOURCE_CALENDAR = "fk_resource_calendar";

    private final ResourceMapper resourceMapper;

    private final ResourceGroupMapper resourceGroupMapper;

    private final CalendarMapper calendarMapper;

    /**
     * 构造服务。
     *
     * @param resourceMapper 资源 Mapper
     * @param resourceGroupMapper 资源组 Mapper
     * @param calendarMapper 日历 Mapper，仅用于只读引用校验
     */
    public ResourceService(ResourceMapper resourceMapper, ResourceGroupMapper resourceGroupMapper,
                           CalendarMapper calendarMapper) {
        this.resourceMapper = resourceMapper;
        this.resourceGroupMapper = resourceGroupMapper;
        this.calendarMapper = calendarMapper;
    }

    /**
     * 分页查询资源。
     *
     * @param query 分页参数，可为 null；内部会 clamp
     * @param code 编码关键字（模糊、忽略大小写、与 name OR）
     * @param name 名称关键字（模糊、忽略大小写、与 code OR）
     * @param type 资源类型（精确、AND）
     * @param groupId 资源组主键（精确、AND）
     * @return 分页结果
     */
    public PageResult<ResourceView> list(PageQuery query, String code, String name,
                                         ResourceType type, UUID groupId) {
        PageQuery normalized = query == null ? new PageQuery() : query;
        normalized.normalize();
        LambdaQueryWrapper<Resource> wrapper = buildListWrapper(code, name, type, groupId);
        applySort(wrapper, normalized.getSort());
        Page<Resource> page = new Page<>(normalized.getPage(), normalized.getSize());
        Page<Resource> result = resourceMapper.selectPage(page, wrapper);
        List<ResourceView> items = result.getRecords().stream().map(ResourceView::from).toList();
        return new PageResult<>(items, normalized.getPage(), normalized.getSize(), result.getTotal(), 0);
    }

    /**
     * 按编码查询资源详情。
     *
     * @param code 资源编码
     * @return 资源视图
     */
    public ResourceView get(String code) {
        return ResourceView.from(findByCodeOrThrow(code));
    }

    /**
     * 创建资源。
     *
     * @param command 创建命令
     * @return 创建后的资源视图
     */
    public ResourceView create(ResourceCreateCommand command) {
        validateRequiredFields(command);
        if (existsByCode(command.code())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, DUPLICATE_CODE_MESSAGE + command.code());
        }
        ensureGroupExists(command.groupId());
        ensureCalendarExists(command.calendarId());
        Resource resource = new Resource();
        // BaseEntity 使用 IdType.INPUT，UUID 由应用层生成，与 V1 的 gen_random_uuid() 默认值等价
        resource.setId(UUID.randomUUID());
        applyCreate(resource, command);
        try {
            resourceMapper.insert(resource);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT, DUPLICATE_CODE_MESSAGE + command.code());
        } catch (PersistenceException | DataAccessException exception) {
            if (!ConstraintViolations.isForeignKeyViolation(exception)) {
                throw exception;
            }
            throw foreignKeyConflict(resource.getGroupId(), resource.getCalendarId(), exception);
        }
        return ResourceView.from(resource);
    }

    /**
     * 更新资源；资源编码不可修改。
     *
     * @param code 路径中的资源编码
     * @param command 更新命令
     * @return 更新后的资源视图
     */
    public ResourceView update(String code, ResourceUpdateCommand command) {
        Resource existing = findByCodeOrThrow(code);
        if (command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (!Objects.equals(existing.getVersion(), command.version())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "资源已被其他请求修改，请刷新后重试");
        }
        applyUpdate(existing, command);
        int rows;
        try {
            rows = resourceMapper.updateById(existing);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT, DUPLICATE_CODE_MESSAGE + code);
        } catch (PersistenceException | DataAccessException exception) {
            if (!ConstraintViolations.isForeignKeyViolation(exception)) {
                throw exception;
            }
            throw foreignKeyConflict(existing.getGroupId(), existing.getCalendarId(), exception);
        }
        if (rows == NO_AFFECTED_ROWS) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "资源已被其他请求修改，请刷新后重试");
        }
        Resource latest = findByCode(code);
        return ResourceView.from(latest == null ? existing : latest);
    }

    /**
     * 停用资源（软删除）；被日历或排产任务引用时拒绝，重复调用幂等返回成功。
     *
     * @param code 资源编码
     */
    public void delete(String code) {
        Resource existing = findByCode(code);
        if (existing == null) {
            if (resourceMapper.countDeletedByCode(code) > 0) {
                return;
            }
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在: " + code);
        }
        UUID resourceId = (UUID) existing.getId();
        List<String> references = new ArrayList<>();
        if (resourceMapper.countCalendarReferences(resourceId) > 0) {
            references.add("日历");
        }
        if (resourceMapper.countTaskReferences(resourceId) > 0) {
            references.add("排产任务");
        }
        if (!references.isEmpty()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "资源已被 " + String.join("、", references) + " 引用，无法删除: " + code);
        }
        resourceMapper.deleteById(existing.getId());
    }

    /**
     * 构造列表查询条件，供单测直接校验 OR/AND 语义。
     *
     * @param code 编码关键字
     * @param name 名称关键字
     * @param type 资源类型
     * @param groupId 资源组主键
     * @return 查询条件
     */
    LambdaQueryWrapper<Resource> buildListWrapper(String code, String name,
                                                  ResourceType type, UUID groupId) {
        LambdaQueryWrapper<Resource> wrapper = Wrappers.lambdaQuery();
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
            wrapper.eq(Resource::getType, type);
        }
        if (groupId != null) {
            wrapper.eq(Resource::getGroupId, groupId);
        }
        return wrapper;
    }

    private void validateRequiredFields(ResourceCreateCommand command) {
        if (command.code() == null || command.code().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 不能为空");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 不能为空");
        }
        if (command.type() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 不能为空");
        }
        if (command.capacityMode() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "capacityMode 不能为空");
        }
    }

    private void applyCreate(Resource resource, ResourceCreateCommand command) {
        BigDecimal capacityValue = command.capacityValue() == null
                ? DEFAULT_CAPACITY_VALUE : command.capacityValue();
        BigDecimal efficiency = command.efficiency() == null
                ? DEFAULT_EFFICIENCY : command.efficiency();
        Integer batchCapacity = command.batchCapacity();
        int setupSeconds = command.setupSeconds() == null ? DEFAULT_SECONDS : command.setupSeconds();
        int teardownSeconds = command.teardownSeconds() == null
                ? DEFAULT_SECONDS : command.teardownSeconds();
        CapacityMode mode = command.capacityMode();
        batchCapacity = defaultBatchCapacity(mode, batchCapacity);
        validateCapacity(mode, capacityValue, batchCapacity, efficiency);
        validateSeconds(setupSeconds, teardownSeconds);

        resource.setCode(command.code().trim());
        resource.setName(command.name());
        resource.setType(command.type());
        resource.setGroupId(command.groupId());
        resource.setCapacityMode(mode);
        resource.setCapacityValue(capacityValue);
        resource.setBatchCapacity(batchCapacity);
        resource.setEfficiency(efficiency);
        resource.setCalendarId(command.calendarId());
        resource.setSetupSeconds(setupSeconds);
        resource.setTeardownSeconds(teardownSeconds);
        resource.setIndustryAttrs(copyAttributes(command.industryAttrs()));
    }

    private void applyUpdate(Resource resource, ResourceUpdateCommand command) {
        if (command.name() != null) {
            resource.setName(command.name());
        }
        if (command.type() != null) {
            resource.setType(command.type());
        }
        if (command.groupId() != null) {
            ensureGroupExists(command.groupId());
            resource.setGroupId(command.groupId());
        }
        if (command.capacityMode() != null) {
            resource.setCapacityMode(command.capacityMode());
        }
        if (command.capacityValue() != null) {
            resource.setCapacityValue(command.capacityValue());
        }
        if (command.batchCapacity() != null) {
            resource.setBatchCapacity(command.batchCapacity());
        }
        if (command.efficiency() != null) {
            resource.setEfficiency(command.efficiency());
        }
        if (command.calendarId() != null) {
            ensureCalendarExists(command.calendarId());
            resource.setCalendarId(command.calendarId());
        }
        if (command.setupSeconds() != null) {
            resource.setSetupSeconds(command.setupSeconds());
        }
        if (command.teardownSeconds() != null) {
            resource.setTeardownSeconds(command.teardownSeconds());
        }
        if (command.industryAttrs() != null) {
            resource.setIndustryAttrs(copyAttributes(command.industryAttrs()));
        }
        resource.setBatchCapacity(defaultBatchCapacity(resource.getCapacityMode(), resource.getBatchCapacity()));
        validateCapacity(resource.getCapacityMode(), resource.getCapacityValue(),
                resource.getBatchCapacity(), resource.getEfficiency());
        validateSeconds(resource.getSetupSeconds(), resource.getTeardownSeconds());
    }

    private Integer defaultBatchCapacity(CapacityMode mode, Integer batchCapacity) {
        if (mode == CapacityMode.BATCH_SECONDS && batchCapacity == null) {
            return DEFAULT_BATCH_CAPACITY;
        }
        return batchCapacity;
    }

    private void validateCapacity(CapacityMode mode, BigDecimal capacityValue,
                                  Integer batchCapacity, BigDecimal efficiency) {
        if (mode == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "capacityMode 不能为空");
        }
        BigDecimal value = capacityValue == null ? DEFAULT_CAPACITY_VALUE : capacityValue;
        BigDecimal eff = efficiency == null ? DEFAULT_EFFICIENCY : efficiency;
        if (value.signum() < 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "capacityValue 不能为负");
        }
        if (eff.compareTo(MIN_EFFICIENCY) < 0 || eff.compareTo(MAX_EFFICIENCY) > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "efficiency 必须在 0.5 到 1.5 之间");
        }
        if (batchCapacity != null && batchCapacity <= 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "batchCapacity 必须大于 0");
        }
        switch (mode) {
            case UNIT_PER_HOUR:
                if (value.multiply(eff).signum() <= 0) {
                    throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                            "UNIT_PER_HOUR 模式要求 capacityValue × efficiency > 0");
                }
                break;
            case BATCH_SECONDS:
                if (value.signum() <= 0) {
                    throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                            "BATCH_SECONDS 模式要求 capacityValue > 0");
                }
                if (batchCapacity == null) {
                    throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                            "BATCH_SECONDS 模式要求 batchCapacity 非空");
                }
                break;
            case SECONDS_PER_UNIT:
            default:
                break;
        }
    }

    private void validateSeconds(Integer setupSeconds, Integer teardownSeconds) {
        if (setupSeconds != null && setupSeconds < 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "setupSeconds 不能为负");
        }
        if (teardownSeconds != null && teardownSeconds < 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "teardownSeconds 不能为负");
        }
    }

    private void ensureGroupExists(UUID groupId) {
        if (groupId == null) {
            return;
        }
        ResourceGroup group = resourceGroupMapper.selectById(groupId);
        if (group == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "资源组不存在: " + groupId);
        }
    }

    /**
     * 校验资源绑定的日历存在且未删除。
     *
     * @param calendarId 日历主键，可空
     */
    private void ensureCalendarExists(UUID calendarId) {
        if (calendarId == null) {
            return;
        }
        Calendar calendar = calendarMapper.findById(calendarId);
        if (calendar == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "日历不存在或已删除: " + calendarId);
        }
    }

    /**
     * 把外键约束失败翻译成 42200；无法从约束名判定时给出资源组/日历通用文案。
     *
     * @param groupId 资源组主键，可空
     * @param calendarId 日历主键，可空
     * @param throwable 捕获到的数据访问异常
     * @return 业务异常
     */
    private BizException foreignKeyConflict(UUID groupId, UUID calendarId, Throwable throwable) {
        if (groupId != null
                && ConstraintViolations.mentionsConstraint(throwable, FK_RESOURCE_GROUP)) {
            return new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "资源组不存在: " + groupId);
        }
        if (calendarId != null
                && ConstraintViolations.mentionsConstraint(throwable, FK_RESOURCE_CALENDAR)) {
            return new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "日历不存在或已删除: " + calendarId);
        }
        return new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                "引用的资源组或日历不存在或已删除");
    }

    private Resource findByCodeOrThrow(String code) {
        Resource resource = findByCode(code);
        if (resource == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "资源不存在: " + code);
        }
        return resource;
    }

    private Resource findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return resourceMapper.selectOne(Wrappers.<Resource>lambdaQuery().eq(Resource::getCode, code.trim()));
    }

    private boolean existsByCode(String code) {
        return resourceMapper.selectCount(Wrappers.<Resource>lambdaQuery().eq(Resource::getCode, code)) > 0;
    }

    private Map<String, Object> copyAttributes(Map<String, Object> attributes) {
        return attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    private void applySort(LambdaQueryWrapper<Resource> wrapper, String sort) {
        if (sort == null || sort.isBlank()) {
            wrapper.orderByAsc(Resource::getCode);
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
            wrapper.orderByAsc(Resource::getCode);
        }
    }

    private boolean applySortField(LambdaQueryWrapper<Resource> wrapper, String field, String direction) {
        boolean ascending = !"desc".equalsIgnoreCase(direction);
        switch (field) {
            case "code":
                wrapper.orderBy(true, ascending, Resource::getCode);
                return true;
            case "name":
                wrapper.orderBy(true, ascending, Resource::getName);
                return true;
            case "type":
                wrapper.orderBy(true, ascending, Resource::getType);
                return true;
            case "groupId":
                wrapper.orderBy(true, ascending, Resource::getGroupId);
                return true;
            case "capacityMode":
                wrapper.orderBy(true, ascending, Resource::getCapacityMode);
                return true;
            case "createdAt":
                wrapper.orderBy(true, ascending, Resource::getCreatedAt);
                return true;
            case "updatedAt":
                wrapper.orderBy(true, ascending, Resource::getUpdatedAt);
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
