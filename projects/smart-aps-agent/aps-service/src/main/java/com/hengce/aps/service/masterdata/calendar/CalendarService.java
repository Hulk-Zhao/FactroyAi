package com.hengce.aps.service.masterdata.calendar;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.domain.common.BaseEntity;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import com.hengce.aps.infrastructure.persistence.mapper.calendar.CalendarMapper;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroup;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroupMapper;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import com.hengce.aps.service.masterdata.ConstraintViolations;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarCreateRequest;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarResponse;
import com.hengce.aps.service.masterdata.calendar.dto.CalendarUpdateRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 工作日历服务：CRUD、分页过滤、版本递增、历史日历只读与引用删除拦截。
 *
 * <p>C14 规则：calendarVersion 为 INTEGER，同一租户同名日历新建版本时取
 * {@code max(calendar_version) + 1}；历史日历（{@code effectiveTo < 今天}）只读，
 * 更新返回 40300；{@code effectiveTo} 为空视为长期有效、可更新；更新必须携带
 * {@code version} 做乐观锁，冲突返回 40901。</p>
 *
 * <p>可用时段端点 M1 不实现（由求解流内部展开）。</p>
 */
@Service
public class CalendarService {

    private static final Set<String> CALENDAR_TYPES = Set.of("FACTORY", "RESOURCE", "SHIFT");

    private static final String TYPE_FACTORY = "FACTORY";

    private static final String TYPE_RESOURCE = "RESOURCE";

    private static final String FK_CALENDAR_RESOURCE = "fk_calendar_resource";

    private static final String FK_CALENDAR_RESOURCE_GROUP = "fk_calendar_resource_group";

    private static final int MIN_VERSION = 1;

    private static final int NO_AFFECTED_ROWS = 0;

    private final CalendarMapper calendarMapper;

    private final ResourceMapper resourceMapper;

    private final ResourceGroupMapper resourceGroupMapper;

    private final String timezone;

    /**
     * 构造服务。
     *
     * @param calendarMapper 工作日历 Mapper
     * @param resourceMapper 资源 Mapper，仅用于只读引用校验
     * @param resourceGroupMapper 资源组 Mapper，仅用于只读引用校验
     * @param timezone IANA 时区；复用既有 {@code aps.business.time-zone} 口径
     */
    public CalendarService(CalendarMapper calendarMapper, ResourceMapper resourceMapper,
                           ResourceGroupMapper resourceGroupMapper,
                           @Value("${aps.calendar.timezone:${aps.business.time-zone:Asia/Shanghai}}")
                           String timezone) {
        this.calendarMapper = calendarMapper;
        this.resourceMapper = resourceMapper;
        this.resourceGroupMapper = resourceGroupMapper;
        this.timezone = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone;
    }

    /**
     * 分页查询日历。
     *
     * @param name 名称模糊过滤，可空
     * @param type 类型精确过滤，可空
     * @param resourceId 资源精确过滤，可空
     * @param resourceGroupId 资源组精确过滤，可空
     * @param query 分页参数
     * @return 分页结果
     */
    public PageResult<CalendarResponse> list(String name, String type, UUID resourceId,
                                             UUID resourceGroupId, PageQuery query) {
        validateTypeFilter(type);
        PageQuery normalized = (query == null ? new PageQuery() : query).normalize();
        Page<Calendar> page = new Page<>(normalized.getPage(), normalized.getSize());
        List<Calendar> records = calendarMapper.selectPage(page, name, type,
                resourceId, resourceGroupId);
        List<CalendarResponse> items = records.stream().map(this::toResponse).toList();
        return PageResult.of(items, normalized, page.getTotal());
    }

    /**
     * 查询日历详情。
     *
     * @param id 日历主键
     * @return 日历详情
     */
    public CalendarResponse get(UUID id) {
        return toResponse(requireExisting(id));
    }

    /**
     * 新建日历版本；同名日历的业务版本号自动取 max+1。
     *
     * @param request 日历字段
     * @return 新建后的日历
     */
    public CalendarResponse create(CalendarCreateRequest request) {
        validateCommon(request.name(), request.type(), request.resourceId(),
                request.effectiveFrom(), request.effectiveTo(), request.calendarVersion());
        validateResourceEffectiveFrom(request.type(), request.effectiveFrom());
        validateResourceReference(request.resourceId());
        validateResourceGroupReference(request.resourceGroupId());
        int expectedVersion = calendarMapper.maxCalendarVersion(request.name()) + 1;
        if (request.calendarVersion().intValue() != expectedVersion) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "calendarVersion 必须为同名 max+1: " + expectedVersion);
        }
        if (calendarMapper.countByNameAndVersion(request.name(), expectedVersion, null) > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "同名同版本日历已存在: " + request.name() + " v" + expectedVersion);
        }
        List<Map<String, Object>> shifts = CalendarShiftRules.shiftsToJson(request.shifts());
        List<Map<String, Object>> holidays = CalendarShiftRules.holidaysToJson(request.holidays());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID operator = AuditContext.getCurrentUserId();
        Calendar entity = new Calendar();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TenantContext.getTenantId());
        entity.setName(request.name());
        entity.setType(request.type());
        entity.setResourceId(request.resourceId());
        entity.setResourceGroupId(request.resourceGroupId());
        entity.setEffectiveFrom(request.effectiveFrom());
        entity.setEffectiveTo(request.effectiveTo());
        entity.setShifts(shifts);
        entity.setHolidays(holidays);
        entity.setCalendarVersion(expectedVersion);
        entity.setVersion(BaseEntity.DEFAULT_VERSION);
        entity.setDeleted(BaseEntity.DEFAULT_DELETED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(operator);
        entity.setUpdatedBy(operator);
        try {
            calendarMapper.insert(entity);
        } catch (PersistenceException | DataAccessException exception) {
            if (!ConstraintViolations.isForeignKeyViolation(exception)) {
                throw exception;
            }
            throw foreignKeyConflict(entity.getResourceId(), entity.getResourceGroupId(), exception);
        }
        return toResponse(entity);
    }

    /**
     * 更新日历；历史日历只读，calendarVersion 不可修改。
     *
     * @param id 日历主键
     * @param request 更新字段与当前 version
     * @return 更新后的日历
     */
    public CalendarResponse update(UUID id, CalendarUpdateRequest request) {
        Calendar entity = requireExisting(id);
        ensureMutable(entity);
        if (request.version() == null || !request.version().equals(entity.getVersion())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "日历版本冲突，请刷新后重试");
        }
        String name = request.name() == null ? entity.getName() : request.name();
        String type = request.type() == null ? entity.getType() : request.type();
        UUID resourceId = request.resourceId() == null ? entity.getResourceId() : request.resourceId();
        UUID resourceGroupId = request.resourceGroupId() == null
                ? entity.getResourceGroupId() : request.resourceGroupId();
        LocalDate effectiveFrom = request.effectiveFrom() == null
                ? entity.getEffectiveFrom() : request.effectiveFrom();
        LocalDate effectiveTo = request.effectiveTo() == null
                ? entity.getEffectiveTo() : request.effectiveTo();
        List<Map<String, Object>> shifts = request.shifts() == null
                ? entity.getShifts() : CalendarShiftRules.shiftsToJson(request.shifts());
        List<Map<String, Object>> holidays = request.holidays() == null
                ? entity.getHolidays() : CalendarShiftRules.holidaysToJson(request.holidays());
        validateCommon(name, type, resourceId, effectiveFrom, effectiveTo, entity.getCalendarVersion());
        if (request.resourceId() != null) {
            validateResourceReference(request.resourceId());
        }
        if (request.resourceGroupId() != null) {
            validateResourceGroupReference(request.resourceGroupId());
        }

        entity.setName(name);
        entity.setType(type);
        entity.setResourceId(resourceId);
        entity.setResourceGroupId(resourceGroupId);
        entity.setEffectiveFrom(effectiveFrom);
        entity.setEffectiveTo(effectiveTo);
        entity.setShifts(shifts);
        entity.setHolidays(holidays);
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedBy(AuditContext.getCurrentUserId());
        int affected;
        try {
            affected = calendarMapper.update(entity);
        } catch (PersistenceException | DataAccessException exception) {
            if (!ConstraintViolations.isForeignKeyViolation(exception)) {
                throw exception;
            }
            throw foreignKeyConflict(entity.getResourceId(), entity.getResourceGroupId(), exception);
        }
        if (affected == NO_AFFECTED_ROWS) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "日历版本冲突，请刷新后重试");
        }
        entity.setVersion(entity.getVersion() + 1);
        return toResponse(entity);
    }

    /**
     * 逻辑删除日历；历史日历与被引用日历禁止删除。
     *
     * @param id 日历主键
     */
    public void delete(UUID id) {
        Calendar entity = requireExisting(id);
        ensureMutable(entity);
        long resourceReferences = calendarMapper.countResourceReferences(id);
        long resourceGroupReferences = calendarMapper.countResourceGroupReferences(id);
        if (resourceReferences > 0 || resourceGroupReferences > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "日历被 " + referenceDetail(resourceReferences, resourceGroupReferences)
                            + "引用，无法删除: " + id);
        }
        int affected = calendarMapper.logicDelete(id, entity.getVersion(),
                OffsetDateTime.now(ZoneOffset.UTC), AuditContext.getCurrentUserId());
        if (affected == NO_AFFECTED_ROWS) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "日历已被其他操作修改，请刷新后重试");
        }
    }

    private Calendar requireExisting(UUID id) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "日历 id 不能为空");
        }
        Calendar entity = calendarMapper.findById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "日历不存在: " + id);
        }
        return entity;
    }

    private void ensureMutable(Calendar entity) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (entity.getEffectiveTo() != null && entity.getEffectiveTo().isBefore(today)) {
            throw new BizException(ErrorCode.FORBIDDEN, "历史日历只读：effectiveTo 早于今天");
        }
    }

    private String referenceDetail(long resourceReferences, long resourceGroupReferences) {
        List<String> parts = new java.util.ArrayList<>();
        if (resourceReferences > 0) {
            parts.add(resourceReferences + " 个资源");
        }
        if (resourceGroupReferences > 0) {
            parts.add(resourceGroupReferences + " 个资源组");
        }
        return String.join("、", parts);
    }

    private void validateCommon(String name, String type, UUID resourceId,
                                LocalDate effectiveFrom, LocalDate effectiveTo, Integer calendarVersion) {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 不能为空");
        }
        if (effectiveFrom == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "effectiveFrom 不能为空");
        }
        if (calendarVersion == null || calendarVersion < MIN_VERSION) {
            throw new BizException(ErrorCode.BAD_REQUEST, "calendarVersion 必须大于 0");
        }
        validateTypeFilter(type);
        if (TYPE_FACTORY.equals(type) && resourceId != null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "FACTORY 类型日历不能绑定资源");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "effectiveTo 不能早于 effectiveFrom");
        }
    }

    private void validateTypeFilter(String type) {
        if (type == null || type.isBlank()) {
            return;
        }
        if (!CALENDAR_TYPES.contains(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 取值必须为 FACTORY/RESOURCE/SHIFT");
        }
    }

    /**
     * RESOURCE 类型日历不允许以早于今天的日期生效，返回 40000 并给出字段级原因。
     *
     * @param type 日历类型
     * @param effectiveFrom 生效起始日期
     */
    private void validateResourceEffectiveFrom(String type, LocalDate effectiveFrom) {
        if (TYPE_RESOURCE.equals(type) && effectiveFrom.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "RESOURCE 类型日历的 effectiveFrom 不能早于今天");
        }
    }

    /**
     * 校验日历绑定的资源存在且未删除。
     *
     * @param resourceId 资源主键，可空
     */
    private void validateResourceReference(UUID resourceId) {
        if (resourceId == null) {
            return;
        }
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "引用的资源不存在或已删除: " + resourceId);
        }
    }

    /**
     * 校验日历绑定的资源组存在且未删除。
     *
     * @param resourceGroupId 资源组主键，可空
     */
    private void validateResourceGroupReference(UUID resourceGroupId) {
        if (resourceGroupId == null) {
            return;
        }
        ResourceGroup group = resourceGroupMapper.selectById(resourceGroupId);
        if (group == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "引用的资源组不存在或已删除: " + resourceGroupId);
        }
    }

    /**
     * 把外键约束失败翻译成 42200；无法从约束名判定时给出资源/资源组通用文案。
     *
     * @param resourceId 绑定的资源，可空
     * @param resourceGroupId 绑定的资源组，可空
     * @param throwable 捕获到的数据访问异常
     * @return 业务异常
     */
    private BizException foreignKeyConflict(UUID resourceId, UUID resourceGroupId, Throwable throwable) {
        if (resourceId != null
                && ConstraintViolations.mentionsConstraint(throwable, FK_CALENDAR_RESOURCE)) {
            return new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "引用的资源不存在或已删除: " + resourceId);
        }
        if (resourceGroupId != null
                && ConstraintViolations.mentionsConstraint(throwable, FK_CALENDAR_RESOURCE_GROUP)) {
            return new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "引用的资源组不存在或已删除: " + resourceGroupId);
        }
        return new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                "引用的资源或资源组不存在或已删除");
    }

    private CalendarResponse toResponse(Calendar entity) {
        return new CalendarResponse(
                entity.getId() == null ? null : (UUID) entity.getId(),
                entity.getName(),
                entity.getCalendarVersion(),
                entity.getType(),
                entity.getResourceId(),
                entity.getResourceGroupId(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                timezone,
                entity.getShifts(),
                entity.getHolidays(),
                entity.getVersion());
    }
}
