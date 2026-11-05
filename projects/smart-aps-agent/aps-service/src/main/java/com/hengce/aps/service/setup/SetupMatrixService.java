package com.hengce.aps.service.setup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.mapper.setup.SetupMatrixEntryMapper;
import com.hengce.aps.infrastructure.persistence.resource.Resource;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroup;
import com.hengce.aps.infrastructure.persistence.resource.ResourceGroupMapper;
import com.hengce.aps.infrastructure.persistence.resource.ResourceMapper;
import com.hengce.aps.infrastructure.persistence.setup.SetupMatrixEntryEntity;
import com.hengce.aps.solver.api.SetupMatrixEntry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 换型矩阵服务（V05-08 / C44）：CRUD、生效范围校验、求解装载。
 *
 * <p>唯一业务键：{@code (keyType, fromKey, toKey, scopeType, resourceId, resourceGroupId)}，
 * 由 V14 的唯一索引兜底；重复时返回 409/40901。</p>
 *
 * <p>范围推导：资源非空为 RESOURCE，仅资源组非空为 RESOURCE_GROUP，两者皆空为 TENANT；
 * 同时传资源与资源组视为自相矛盾，返回 422/42200。</p>
 */
@Service
public class SetupMatrixService {

    /**
     * 维度别名：产品。
     */
    private static final String KEY_TYPE_PRODUCT = "MATERIAL_CODE";

    /**
     * 维度别名：程序/工序。
     */
    private static final String KEY_TYPE_PROGRAM = "OPERATION_CODE";

    private static final String SCOPE_TENANT = "TENANT";

    private static final String SCOPE_RESOURCE_GROUP = "RESOURCE_GROUP";

    private static final String SCOPE_RESOURCE = "RESOURCE";

    private static final int MAX_KEY_LENGTH = 64;

    private static final int MAX_REMARK_LENGTH = 255;

    private final SetupMatrixEntryMapper setupMatrixEntryMapper;

    private final ResourceMapper resourceMapper;

    private final ResourceGroupMapper resourceGroupMapper;

    /**
     * 构造服务。
     *
     * @param setupMatrixEntryMapper 矩阵 Mapper
     * @param resourceMapper 资源 Mapper，仅用于范围存在性校验
     * @param resourceGroupMapper 资源组 Mapper，仅用于范围存在性校验
     */
    public SetupMatrixService(SetupMatrixEntryMapper setupMatrixEntryMapper,
                              ResourceMapper resourceMapper,
                              ResourceGroupMapper resourceGroupMapper) {
        this.setupMatrixEntryMapper = setupMatrixEntryMapper;
        this.resourceMapper = resourceMapper;
        this.resourceGroupMapper = resourceGroupMapper;
    }

    /**
     * 分页查询换型矩阵。
     *
     * @param query 分页参数，可为 null
     * @param keyType 维度过滤，可空
     * @param scopeType 范围过滤，可空
     * @param resourceId 资源过滤，可空
     * @param resourceGroupId 资源组过滤，可空
     * @param fromKey FROM 键精确过滤，可空
     * @param toKey TO 键精确过滤，可空
     * @return 分页结果
     */
    public PageResult<SetupMatrixView> list(PageQuery query, String keyType, String scopeType,
                                            UUID resourceId, UUID resourceGroupId,
                                            String fromKey, String toKey) {
        PageQuery normalized = query == null ? new PageQuery() : query;
        normalized.normalize();
        LambdaQueryWrapper<SetupMatrixEntryEntity> wrapper = Wrappers.lambdaQuery();
        if (keyType != null && !keyType.isBlank()) {
            wrapper.eq(SetupMatrixEntryEntity::getKeyType, normalizeKeyType(keyType));
        }
        if (scopeType != null && !scopeType.isBlank()) {
            wrapper.eq(SetupMatrixEntryEntity::getScopeType,
                    scopeType.trim().toUpperCase(Locale.ROOT));
        }
        if (resourceId != null) {
            wrapper.eq(SetupMatrixEntryEntity::getResourceId, resourceId);
        }
        if (resourceGroupId != null) {
            wrapper.eq(SetupMatrixEntryEntity::getResourceGroupId, resourceGroupId);
        }
        if (fromKey != null && !fromKey.isBlank()) {
            wrapper.eq(SetupMatrixEntryEntity::getFromKey, fromKey.trim());
        }
        if (toKey != null && !toKey.isBlank()) {
            wrapper.eq(SetupMatrixEntryEntity::getToKey, toKey.trim());
        }
        wrapper.orderByAsc(SetupMatrixEntryEntity::getKeyType)
                .orderByAsc(SetupMatrixEntryEntity::getScopeType)
                .orderByDesc(SetupMatrixEntryEntity::getCreatedAt);
        Page<SetupMatrixEntryEntity> page = new Page<>(normalized.getPage(), normalized.getSize());
        Page<SetupMatrixEntryEntity> result = setupMatrixEntryMapper.selectPage(page, wrapper);
        List<SetupMatrixView> items = result.getRecords().stream()
                .map(SetupMatrixView::from).toList();
        return new PageResult<>(items, normalized.getPage(), normalized.getSize(),
                result.getTotal(), 0);
    }

    /**
     * 查询矩阵详情。
     *
     * @param id 主键
     * @return 视图
     */
    public SetupMatrixView get(UUID id) {
        return SetupMatrixView.from(findOrThrow(id));
    }

    /**
     * 创建矩阵条目。
     *
     * @param command 命令
     * @return 创建后的视图
     */
    public SetupMatrixView create(SetupMatrixCommand command) {
        SetupMatrixEntryEntity entity = apply(new SetupMatrixEntryEntity(), command);
        entity.setId(UUID.randomUUID());
        try {
            setupMatrixEntryMapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "换型矩阵条目已存在（业务键重复）");
        }
        return findByBusinessKey(
                entity.getKeyType(), entity.getFromKey(), entity.getToKey(),
                entity.getResourceId(), entity.getResourceGroupId());
    }

    /**
     * 更新矩阵条目（不改变业务键）。
     *
     * @param id 主键
     * @param command 命令
     * @return 更新后的视图
     */
    public SetupMatrixView update(UUID id, SetupMatrixCommand command) {
        SetupMatrixEntryEntity existing = findOrThrow(id);
        if (command.version() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空");
        }
        if (!Objects.equals(existing.getVersion(), command.version())) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "换型矩阵条目已被其他请求修改，请刷新后重试");
        }
        apply(existing, command);
        int rows;
        try {
            rows = setupMatrixEntryMapper.updateById(existing);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "换型矩阵条目已存在（业务键重复）");
        }
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "换型矩阵条目已被其他请求修改，请刷新后重试");
        }
        return SetupMatrixView.from(findOrThrow(id));
    }

    /**
     * 删除矩阵条目（软删除）。
     *
     * @param id 主键
     */
    public void delete(UUID id) {
        SetupMatrixEntryEntity existing = findOrThrow(id);
        setupMatrixEntryMapper.deleteById(existing.getId());
    }

    /**
     * 装载全部生效条目，供求解装配使用。
     *
     * @return 求解器中立条目
     */
    public List<SetupMatrixEntry> loadForSolver() {
        return setupMatrixEntryMapper.selectList(Wrappers.lambdaQuery()).stream()
                .map(SetupMatrixService::toSolverEntry)
                .toList();
    }

    /**
     * 按业务键查找，供 Excel 导入幂等更新使用。
     *
     * @param keyType 维度
     * @param fromKey FROM 键，可空
     * @param toKey TO 键，可空
     * @param resourceId 资源，可空
     * @param resourceGroupId 资源组，可空
     * @return 视图；不存在返回 {@code null}
     */
    public SetupMatrixView findByBusinessKey(String keyType, String fromKey, String toKey,
                                             UUID resourceId, UUID resourceGroupId) {
        SetupMatrixEntryEntity entity = findEntityByBusinessKey(
                normalizeKeyType(keyType), trimToNull(fromKey), trimToNull(toKey),
                resourceId, resourceGroupId);
        return entity == null ? null : SetupMatrixView.from(entity);
    }

    /**
     * 校验并应用命令到实体。
     *
     * @param entity 目标实体
     * @param command 命令
     * @return 同一实体
     */
    private SetupMatrixEntryEntity apply(SetupMatrixEntryEntity entity, SetupMatrixCommand command) {
        String keyType = normalizeKeyType(command == null ? null : command.keyType());
        String fromKey = trimToNull(command.fromKey());
        String toKey = trimToNull(command.toKey());
        validateKeyLength(fromKey, "fromKey");
        validateKeyLength(toKey, "toKey");
        if (command.resourceId() != null && command.resourceGroupId() != null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "resourceId 与 resourceGroupId 不能同时指定");
        }
        Integer setupSeconds = command.setupSeconds();
        if (setupSeconds == null || setupSeconds < 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "setupSeconds 必填且不能小于 0");
        }
        validateOptionalSeconds(command.sameSeriesSeconds(), "sameSeriesSeconds");
        validateOptionalSeconds(command.crossSeriesSeconds(), "crossSeriesSeconds");
        if (command.remark() != null && command.remark().trim().length() > MAX_REMARK_LENGTH) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "remark 长度不能超过 " + MAX_REMARK_LENGTH);
        }
        String scopeType = deriveScope(command.resourceId(), command.resourceGroupId());
        ensureScopeExists(scopeType, command.resourceId(), command.resourceGroupId());
        entity.setKeyType(keyType);
        entity.setFromKey(fromKey);
        entity.setToKey(toKey);
        entity.setScopeType(scopeType);
        entity.setResourceId(command.resourceId());
        entity.setResourceGroupId(command.resourceGroupId());
        entity.setSetupSeconds(setupSeconds);
        entity.setSameSeriesSetupSeconds(command.sameSeriesSeconds());
        entity.setCrossSeriesSetupSeconds(command.crossSeriesSeconds());
        entity.setRemark(trimToNull(command.remark()));
        return entity;
    }

    private SetupMatrixEntryEntity findEntityByBusinessKey(String keyType, String fromKey,
                                                           String toKey, UUID resourceId,
                                                           UUID resourceGroupId) {
        LambdaQueryWrapper<SetupMatrixEntryEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(SetupMatrixEntryEntity::getKeyType, keyType);
        wrapper.eq(SetupMatrixEntryEntity::getScopeType,
                deriveScope(resourceId, resourceGroupId));
        if (resourceId == null) {
            wrapper.isNull(SetupMatrixEntryEntity::getResourceId);
        } else {
            wrapper.eq(SetupMatrixEntryEntity::getResourceId, resourceId);
        }
        if (resourceGroupId == null) {
            wrapper.isNull(SetupMatrixEntryEntity::getResourceGroupId);
        } else {
            wrapper.eq(SetupMatrixEntryEntity::getResourceGroupId, resourceGroupId);
        }
        if (fromKey == null) {
            wrapper.isNull(SetupMatrixEntryEntity::getFromKey);
        } else {
            wrapper.eq(SetupMatrixEntryEntity::getFromKey, fromKey);
        }
        if (toKey == null) {
            wrapper.isNull(SetupMatrixEntryEntity::getToKey);
        } else {
            wrapper.eq(SetupMatrixEntryEntity::getToKey, toKey);
        }
        return setupMatrixEntryMapper.selectList(wrapper).stream().findFirst().orElse(null);
    }

    private SetupMatrixEntryEntity findOrThrow(UUID id) {
        SetupMatrixEntryEntity entity = setupMatrixEntryMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "换型矩阵条目不存在: " + id);
        }
        return entity;
    }

    private static SetupMatrixEntry toSolverEntry(SetupMatrixEntryEntity entity) {
        return new SetupMatrixEntry(
                entity.getResourceId(),
                entity.getResourceGroupId(),
                entity.getKeyType(),
                entity.getFromKey(),
                entity.getToKey(),
                entity.getSetupSeconds() == null ? 0 : entity.getSetupSeconds(),
                entity.getSameSeriesSetupSeconds(),
                entity.getCrossSeriesSetupSeconds());
    }

    private static String normalizeKeyType(String keyType) {
        if (keyType == null || keyType.isBlank()) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "keyType 必填，取值 PRODUCT/PROGRAM");
        }
        String normalized = keyType.trim().toUpperCase(Locale.ROOT);
        if ("PRODUCT".equals(normalized)) {
            return KEY_TYPE_PRODUCT;
        }
        if ("PROGRAM".equals(normalized)) {
            return KEY_TYPE_PROGRAM;
        }
        if (KEY_TYPE_PRODUCT.equals(normalized) || KEY_TYPE_PROGRAM.equals(normalized)) {
            return normalized;
        }
        throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                "keyType 仅支持 PRODUCT/PROGRAM（或 MATERIAL_CODE/OPERATION_CODE）: " + keyType);
    }

    private static String deriveScope(UUID resourceId, UUID resourceGroupId) {
        if (resourceId != null && resourceGroupId != null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "resourceId 与 resourceGroupId 不能同时指定");
        }
        if (resourceId != null) {
            return SCOPE_RESOURCE;
        }
        if (resourceGroupId != null) {
            return SCOPE_RESOURCE_GROUP;
        }
        return SCOPE_TENANT;
    }

    private void ensureScopeExists(String scopeType, UUID resourceId, UUID resourceGroupId) {
        if (SCOPE_RESOURCE.equals(scopeType)) {
            Resource resource = resourceMapper.selectById(resourceId);
            if (resource == null) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "资源不存在: " + resourceId);
            }
        } else if (SCOPE_RESOURCE_GROUP.equals(scopeType)) {
            ResourceGroup group = resourceGroupMapper.selectById(resourceGroupId);
            if (group == null) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                        "资源组不存在: " + resourceGroupId);
            }
        }
    }

    private static void validateKeyLength(String value, String field) {
        if (value != null && value.length() > MAX_KEY_LENGTH) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    field + " 长度不能超过 " + MAX_KEY_LENGTH);
        }
    }

    private static void validateOptionalSeconds(Integer value, String field) {
        if (value != null && value < 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    field + " 不能小于 0");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
