package com.hengce.aps.domain.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 业务表统一基类，字段与 docs/db/schema.md 第 3.2 节「通用字段」一一对应。
 *
 * <p>约束：</p>
 * <ul>
 *   <li>id 同时承载 UUID（主数据）与 BIGSERIAL（task/solve_task），因此声明为 {@link Serializable}，
 *       具体主键生成策略由各实体在业务流中决定；基类默认按调用方传入值写入（{@link IdType#INPUT}）。</li>
 *   <li>version 为乐观锁字段（{@link Version}），由 OptimisticLockerInnerInterceptor 维护。</li>
 *   <li>deleted 为逻辑删除字段（{@link TableLogic}），值为 false/true，与 V1 迁移的 BOOLEAN 列一致。</li>
 *   <li>tenantId/审计字段由 ApsMetaObjectHandler 在 INSERT/UPDATE 时自动填充，显式赋值优先。</li>
 *   <li>时间统一使用 UTC {@link OffsetDateTime}，对应 PostgreSQL TIMESTAMPTZ（见 C7）。</li>
 * </ul>
 */
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 乐观锁版本号默认值，对应 DDL 的 version INTEGER NOT NULL DEFAULT 1。
     */
    public static final int DEFAULT_VERSION = 1;

    /**
     * 逻辑删除默认值，对应 DDL 的 deleted BOOLEAN NOT NULL DEFAULT false。
     */
    public static final boolean DEFAULT_DELETED = false;

    /**
     * 主键；UUID 表由调用方生成或依赖数据库默认值，BIGSERIAL 表由数据库回填。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Serializable id;

    /**
     * 租户标识；MVP 默认 00000000-0000-0000-0000-000000000001，显式赋值可覆盖。
     */
    @TableField(value = "tenant_id", fill = FieldFill.INSERT)
    private UUID tenantId;

    /**
     * 乐观锁版本号。
     */
    @Version
    @TableField("version")
    private Integer version = DEFAULT_VERSION;

    /**
     * 逻辑删除标记。
     */
    @TableLogic(value = "false", delval = "true")
    @TableField("deleted")
    private Boolean deleted = DEFAULT_DELETED;

    /**
     * 创建时间（UTC）。
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    /**
     * 更新时间（UTC）。
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    /**
     * 创建人；对应 users.id，无强外键，可为空。
     */
    @TableField(value = "created_by", fill = FieldFill.INSERT)
    private UUID createdBy;

    /**
     * 更新人；对应 users.id，无强外键，可为空。
     */
    @TableField(value = "updated_by", fill = FieldFill.INSERT_UPDATE)
    private UUID updatedBy;

    public Serializable getId() {
        return id;
    }

    public void setId(Serializable id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }
}
