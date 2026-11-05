-- =============================================================================
-- mfg-aps-platform（衡策 APS 开源版）数据库基线迁移 V1
-- 目标库：PostgreSQL 15+
-- 依据：mfg-aps-platform-PRD.md 第 4 章 / 第 8 章；aps-arch-page 第 4 节数据库设计指引
-- 说明：
--   1. 全部业务表统一使用 snake_case；UUID 主键由 pgcrypto 的 gen_random_uuid() 生成。
--   2. 高频大表 task / solve_task / audit_log / task_adjustment 使用 BIGSERIAL，
--      其余主数据与业务主表使用 UUID（与架构页 4.2 主键策略一致）。
--   3. 软删除统一为 deleted BOOLEAN NOT NULL DEFAULT false，
--      唯一约束一律使用 WHERE deleted = false 的部分唯一索引。
--   4. 枚举使用 VARCHAR + CHECK 约束（而非原生 ENUM 类型），
--      便于 MyBatis-Plus / JPA 映射与后续枚举值演进（见 docs/db/schema.md）。
--   5. JSONB 仅用于扩展属性、行业属性、规则、快照与明细；参与计算/高频过滤的字段全部结构化。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. 扩展
-- -----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- -----------------------------------------------------------------------------
-- 1. 租户（多租户预留；MVP 单租户使用固定默认 UUID）
-- -----------------------------------------------------------------------------
CREATE TABLE tenant (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(256) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    contact_name    VARCHAR(64),
    contact_email   VARCHAR(128),
    contact_phone   VARCHAR(32),
    -- 租户级配置：求解超时、存储配额、用户数上限、功能开关等
    config          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version         INTEGER      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT chk_tenant_version CHECK (version >= 1),
    CONSTRAINT chk_tenant_config_object CHECK (jsonb_typeof(config) = 'object')
);
CREATE UNIQUE INDEX uq_tenant_code ON tenant (code) WHERE deleted = false;
COMMENT ON TABLE tenant IS '租户；MVP 单租户，tenant_id 字段与过滤拦截器预留';

-- -----------------------------------------------------------------------------
-- 2. RBAC：用户 / 角色 / 权限
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL,
    username          VARCHAR(64)  NOT NULL,
    display_name      VARCHAR(128),
    password_hash     VARCHAR(255),                 -- 本地账号 bcrypt；LDAP/OAuth2 接入时可为空
    email             VARCHAR(128),
    phone             VARCHAR(32),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    locked_until      TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    ext_attrs         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version           INTEGER      NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID,
    updated_by        UUID,
    deleted           BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    CONSTRAINT chk_users_version CHECK (version >= 1),
    CONSTRAINT chk_users_ext_attrs_object CHECK (jsonb_typeof(ext_attrs) = 'object')
);
CREATE UNIQUE INDEX uq_users_tenant_username ON users (tenant_id, username) WHERE deleted = false;
CREATE INDEX idx_users_tenant_status ON users (tenant_id, status) WHERE deleted = false;
CREATE INDEX idx_users_email ON users (tenant_id, email) WHERE deleted = false AND email IS NOT NULL;
COMMENT ON TABLE users IS '用户（RBAC）；密码本地 bcrypt，支持后续 LDAP/AD 与 OAuth2 SSO';
COMMENT ON COLUMN users.password_hash IS 'bcrypt 哈希；LDAP/SSO 用户可为空。禁止在迁移脚本中写入默认口令';

CREATE TABLE role (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    code          VARCHAR(64) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(512),
    is_builtin    BOOLEAN     NOT NULL DEFAULT false,
    version       INTEGER     NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    deleted       BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_role_version CHECK (version >= 1)
);
CREATE UNIQUE INDEX uq_role_tenant_code ON role (tenant_id, code) WHERE deleted = false;
COMMENT ON TABLE role IS '角色；MVP 预置 ADMIN / PLANNER / PROCESS_ENGINEER / SALES / VIEWER';

CREATE TABLE permission (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    code          VARCHAR(64) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    module        VARCHAR(32) NOT NULL,
    description   VARCHAR(512),
    version       INTEGER     NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    deleted       BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_permission_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_permission_version CHECK (version >= 1)
);
CREATE UNIQUE INDEX uq_permission_tenant_code ON permission (tenant_id, code) WHERE deleted = false;
CREATE INDEX idx_permission_module ON permission (tenant_id, module) WHERE deleted = false;
COMMENT ON TABLE permission IS '权限点；模块级（MD_READ/MD_WRITE）+ 操作级（SCHEDULE_PUBLISH/ORDER_FREEZE）';

CREATE TABLE role_permission (
    role_id       UUID        NOT NULL,
    permission_id UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id) ON DELETE CASCADE
);
CREATE INDEX idx_role_permission_permission ON role_permission (permission_id);

CREATE TABLE user_role (
    user_id       UUID        NOT NULL,
    role_id       UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE
);
CREATE INDEX idx_user_role_role ON user_role (role_id);

-- -----------------------------------------------------------------------------
-- 3. 客户 / 供应商（订单与物料引用的主数据）
-- -----------------------------------------------------------------------------
CREATE TABLE customer (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(256) NOT NULL,
    priority            VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    lead_time_days      INTEGER,
    payment_terms       VARCHAR(128),
    blacklisted         BOOLEAN      NOT NULL DEFAULT false,
    ext_attrs           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version             INTEGER      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_customer_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_customer_priority CHECK (priority IN ('VIP', 'NORMAL', 'LOW')),
    CONSTRAINT chk_customer_version CHECK (version >= 1),
    CONSTRAINT chk_customer_ext_attrs_object CHECK (jsonb_typeof(ext_attrs) = 'object')
);
CREATE UNIQUE INDEX uq_customer_tenant_code ON customer (tenant_id, code) WHERE deleted = false;
CREATE INDEX idx_customer_tenant_priority ON customer (tenant_id, priority) WHERE deleted = false;

CREATE TABLE supplier (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(256) NOT NULL,
    lead_time_days      INTEGER,
    moq                 NUMERIC(18,4),
    quality_grade       VARCHAR(8),
    blacklisted         BOOLEAN      NOT NULL DEFAULT false,
    ext_attrs           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version             INTEGER      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_supplier_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_supplier_quality_grade CHECK (quality_grade IS NULL OR quality_grade IN ('A', 'B', 'C')),
    CONSTRAINT chk_supplier_version CHECK (version >= 1),
    CONSTRAINT chk_supplier_ext_attrs_object CHECK (jsonb_typeof(ext_attrs) = 'object')
);
CREATE UNIQUE INDEX uq_supplier_tenant_code ON supplier (tenant_id, code) WHERE deleted = false;

-- -----------------------------------------------------------------------------
-- 4. 物料分类 / 物料
-- -----------------------------------------------------------------------------
CREATE TABLE material_category (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    parent_id     UUID,
    code          VARCHAR(64) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    sort_no       INTEGER     NOT NULL DEFAULT 0,
    version       INTEGER     NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    deleted       BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_material_category_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_material_category_parent FOREIGN KEY (parent_id) REFERENCES material_category (id),
    CONSTRAINT chk_material_category_version CHECK (version >= 1)
);
CREATE UNIQUE INDEX uq_material_category_tenant_code ON material_category (tenant_id, code) WHERE deleted = false;
CREATE INDEX idx_material_category_parent ON material_category (parent_id);

CREATE TABLE material (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(256) NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    spec                VARCHAR(512),
    unit                VARCHAR(16)  NOT NULL,
    category_id         UUID,
    safety_stock        NUMERIC(18,4),
    moq                 NUMERIC(18,4),
    lead_time_days      INTEGER,
    default_supplier_id UUID,
    ext_attrs           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    industry_attrs      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version             INTEGER      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_material_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_material_category FOREIGN KEY (category_id) REFERENCES material_category (id),
    CONSTRAINT fk_material_supplier FOREIGN KEY (default_supplier_id) REFERENCES supplier (id),
    CONSTRAINT chk_material_type CHECK (type IN ('FINISHED', 'SEMI', 'RAW', 'AUX', 'PACK')),
    CONSTRAINT chk_material_version CHECK (version >= 1),
    CONSTRAINT chk_material_ext_attrs_object CHECK (jsonb_typeof(ext_attrs) = 'object'),
    CONSTRAINT chk_material_industry_attrs_object CHECK (jsonb_typeof(industry_attrs) = 'object')
);
CREATE UNIQUE INDEX uq_material_tenant_code ON material (tenant_id, code) WHERE deleted = false;
CREATE INDEX idx_material_tenant_type_category ON material (tenant_id, type, category_id) WHERE deleted = false;
CREATE INDEX idx_material_tenant_name_trgm ON material USING GIN (name gin_trgm_ops);
CREATE INDEX idx_material_ext_attrs_gin ON material USING GIN (ext_attrs);
CREATE INDEX idx_material_industry_attrs_gin ON material USING GIN (industry_attrs);
COMMENT ON TABLE material IS '物料主数据；ext_attrs 存通用扩展属性，industry_attrs 存 MSD/电芯等行业属性（GIN 索引）';
COMMENT ON COLUMN material.industry_attrs IS '行业属性 JSON schema 见 docs/db/schema.md；SMT: msd_level/exposure_hours/bake_hours/panel_size；储能: cell_model/nominal_capacity_ah/nominal_voltage_v/grading_rule';

-- -----------------------------------------------------------------------------
-- 5. BOM（版本化 + 明细行）
-- -----------------------------------------------------------------------------
CREATE TABLE bom (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    parent_material_id  UUID        NOT NULL,
    bom_version         VARCHAR(32) NOT NULL,      -- PRD 中的 version（业务版本号）
    effective_date      DATE        NOT NULL,
    expiry_date         DATE,
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    is_panel            BOOLEAN     NOT NULL DEFAULT false,
    panel_size          INTEGER,
    -- 冗余快照：便于 BOM 树快速渲染与版本对比；bom_item 为唯一事实来源
    items               JSONB       NOT NULL DEFAULT '[]'::jsonb,
    version             INTEGER     NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_bom_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_bom_parent_material FOREIGN KEY (parent_material_id) REFERENCES material (id),
    CONSTRAINT chk_bom_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_bom_version CHECK (version >= 1),
    CONSTRAINT chk_bom_effective_range CHECK (expiry_date IS NULL OR expiry_date >= effective_date),
    CONSTRAINT chk_bom_items_array CHECK (jsonb_typeof(items) = 'array')
);
CREATE UNIQUE INDEX uq_bom_parent_version ON bom (parent_material_id, bom_version) WHERE deleted = false;
-- 同一父件同一时刻仅允许一个 ACTIVE 版本
CREATE UNIQUE INDEX uq_bom_one_active_per_parent ON bom (tenant_id, parent_material_id) WHERE status = 'ACTIVE' AND deleted = false;
CREATE INDEX idx_bom_tenant_status ON bom (tenant_id, status);
COMMENT ON TABLE bom IS '物料清单主表；bom_version 对应 PRD 的 version 业务版本号，version 为乐观锁版本';
COMMENT ON COLUMN bom.items IS '子件快照数组，schema 见 docs/db/schema.md；正式明细以 bom_item 为准';

CREATE TABLE bom_item (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    bom_id              UUID        NOT NULL,
    child_material_id   UUID        NOT NULL,
    qty_per_unit        NUMERIC(18,6) NOT NULL DEFAULT 1,
    scrap_rate          NUMERIC(5,2) NOT NULL DEFAULT 0,
    position            VARCHAR(128),              -- SMT 位号
    substitutes         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    is_phantom          BOOLEAN     NOT NULL DEFAULT false,
    level               INTEGER,
    seq_no              INTEGER     NOT NULL DEFAULT 0,
    version             INTEGER     NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_bom_item_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_bom_item_bom FOREIGN KEY (bom_id) REFERENCES bom (id) ON DELETE CASCADE,
    CONSTRAINT fk_bom_item_child_material FOREIGN KEY (child_material_id) REFERENCES material (id),
    CONSTRAINT chk_bom_item_qty CHECK (qty_per_unit > 0),
    CONSTRAINT chk_bom_item_scrap CHECK (scrap_rate >= 0 AND scrap_rate <= 100),
    CONSTRAINT chk_bom_item_version CHECK (version >= 1),
    CONSTRAINT chk_bom_item_substitutes_array CHECK (jsonb_typeof(substitutes) = 'array')
);
CREATE INDEX idx_bom_item_bom ON bom_item (bom_id, seq_no);
CREATE INDEX idx_bom_item_child_material ON bom_item (child_material_id);
CREATE UNIQUE INDEX uq_bom_item_pos ON bom_item (bom_id, child_material_id, position) WHERE deleted = false AND position IS NOT NULL;
COMMENT ON TABLE bom_item IS 'BOM 子件行；substitutes 为替代料数组 [{materialId, priority, ratio}]';

-- -----------------------------------------------------------------------------
-- 6. 资源组 / 资源 / 工作日历
-- -----------------------------------------------------------------------------
CREATE TABLE resource_group (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    code                VARCHAR(64) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         VARCHAR(512),
    calendar_id         UUID,
    version             INTEGER     NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_resource_group_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_resource_group_version CHECK (version >= 1)
);
CREATE UNIQUE INDEX uq_resource_group_tenant_code ON resource_group (tenant_id, code) WHERE deleted = false;

CREATE TABLE resource (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    code                VARCHAR(64)  NOT NULL,
    name                VARCHAR(256) NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    group_id            UUID,
    capacity_mode       VARCHAR(24)  NOT NULL DEFAULT 'UNIT_PER_HOUR',
    capacity_value      NUMERIC(18,4) NOT NULL DEFAULT 0,
    batch_capacity      INTEGER,
    efficiency          NUMERIC(5,2) NOT NULL DEFAULT 1.00,
    calendar_id         UUID,
    setup_seconds       INTEGER      NOT NULL DEFAULT 0,
    teardown_seconds    INTEGER      NOT NULL DEFAULT 0,
    maintenance_plan    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    industry_attrs      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version             INTEGER      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_resource_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_resource_group FOREIGN KEY (group_id) REFERENCES resource_group (id),
    CONSTRAINT chk_resource_type CHECK (type IN ('PRIMARY', 'SECONDARY', 'HUMAN', 'TOOL')),
    CONSTRAINT chk_resource_capacity_mode CHECK (capacity_mode IN ('UNIT_PER_HOUR', 'SECONDS_PER_UNIT', 'BATCH_SECONDS')),
    CONSTRAINT chk_resource_capacity_value CHECK (capacity_value >= 0),
    CONSTRAINT chk_resource_efficiency CHECK (efficiency >= 0.5 AND efficiency <= 1.5),
    CONSTRAINT chk_resource_batch_capacity CHECK (batch_capacity IS NULL OR batch_capacity > 0),
    CONSTRAINT chk_resource_setup CHECK (setup_seconds >= 0 AND teardown_seconds >= 0),
    CONSTRAINT chk_resource_version CHECK (version >= 1),
    CONSTRAINT chk_resource_maintenance_plan_object CHECK (jsonb_typeof(maintenance_plan) = 'object'),
    CONSTRAINT chk_resource_industry_attrs_object CHECK (jsonb_typeof(industry_attrs) = 'object'),
    CONSTRAINT chk_resource_batch_mode CHECK (capacity_mode <> 'BATCH_SECONDS' OR batch_capacity IS NOT NULL)
);
CREATE UNIQUE INDEX uq_resource_tenant_code ON resource (tenant_id, code) WHERE deleted = false;
CREATE INDEX idx_resource_tenant_type ON resource (tenant_id, type) WHERE deleted = false;
CREATE INDEX idx_resource_tenant_group ON resource (tenant_id, group_id) WHERE deleted = false;
CREATE INDEX idx_resource_calendar ON resource (calendar_id) WHERE deleted = false;
COMMENT ON TABLE resource IS '主/辅/人力/工具资源；产能模式与值必须匹配，效率系数 0.5-1.5';

CREATE TABLE calendar (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    code                VARCHAR(64),
    name                VARCHAR(256) NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    resource_id         UUID,                        -- RESOURCE 类型日历绑定的资源
    resource_group_id   UUID,                        -- 资源组级日历
    effective_from      DATE         NOT NULL,
    effective_to        DATE,
    shifts              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    holidays            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    -- 日历业务版本号（历史日历只读，变更即新建版本）
    calendar_version    INTEGER      NOT NULL DEFAULT 1,
    version             INTEGER      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_calendar_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_calendar_resource FOREIGN KEY (resource_id) REFERENCES resource (id),
    CONSTRAINT fk_calendar_resource_group FOREIGN KEY (resource_group_id) REFERENCES resource_group (id),
    CONSTRAINT chk_calendar_type CHECK (type IN ('FACTORY', 'RESOURCE', 'SHIFT')),
    CONSTRAINT chk_calendar_effective_range CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT chk_calendar_shifts_array CHECK (jsonb_typeof(shifts) = 'array'),
    CONSTRAINT chk_calendar_holidays_array CHECK (jsonb_typeof(holidays) = 'array'),
    CONSTRAINT chk_calendar_version CHECK (version >= 1),
    CONSTRAINT chk_calendar_type_ref CHECK (
        (type = 'FACTORY' AND resource_id IS NULL) OR type <> 'FACTORY'
    )
);
CREATE UNIQUE INDEX uq_calendar_tenant_name_version ON calendar (tenant_id, name, calendar_version) WHERE deleted = false;
CREATE INDEX idx_calendar_tenant_type_range ON calendar (tenant_id, type, effective_from, effective_to) WHERE deleted = false;
CREATE INDEX idx_calendar_resource ON calendar (resource_id) WHERE deleted = false;
CREATE INDEX idx_calendar_resource_group ON calendar (resource_group_id) WHERE deleted = false;

-- resource / resource_group 与 calendar 互为引用，外键在 calendar 建表后补充
ALTER TABLE resource
    ADD CONSTRAINT fk_resource_calendar FOREIGN KEY (calendar_id) REFERENCES calendar (id);
ALTER TABLE resource_group
    ADD CONSTRAINT fk_resource_group_calendar FOREIGN KEY (calendar_id) REFERENCES calendar (id);

COMMENT ON TABLE calendar IS '工作日历；shifts/holidays 为数组 JSONB，历史日历只读（通过新版本覆盖）';
COMMENT ON COLUMN calendar.shifts IS '[{startTime, endTime, daysOfWeek:[1..7]}]';
COMMENT ON COLUMN calendar.holidays IS '[{date, name, kind}]，kind: LEGAL/COMPANY/MAKEUP';
COMMENT ON COLUMN resource.calendar_id IS '资源专属日历；优先级：资源专属 > 资源组 > 全厂默认';

-- -----------------------------------------------------------------------------
-- 7. 工序模版
-- -----------------------------------------------------------------------------
CREATE TABLE operation_template (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID         NOT NULL,
    code                      VARCHAR(64)  NOT NULL,
    name                      VARCHAR(256) NOT NULL,
    primary_resource_id       UUID,
    primary_resource_group_id UUID,
    secondary_resource_ids    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    input_material_ids        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    output_material_id        UUID,
    standard_seconds          INTEGER      NOT NULL DEFAULT 0,
    setup_seconds             INTEGER      NOT NULL DEFAULT 0,
    teardown_seconds          INTEGER      NOT NULL DEFAULT 0,
    -- 模版业务版本号（修改生成新版本，旧版本仍可被历史路线引用）
    template_version          INTEGER      NOT NULL DEFAULT 1,
    version                   INTEGER      NOT NULL DEFAULT 1,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by                UUID,
    updated_by                UUID,
    deleted                   BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_operation_template_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_operation_template_resource FOREIGN KEY (primary_resource_id) REFERENCES resource (id),
    CONSTRAINT fk_operation_template_resource_group FOREIGN KEY (primary_resource_group_id) REFERENCES resource_group (id),
    CONSTRAINT fk_operation_template_output_material FOREIGN KEY (output_material_id) REFERENCES material (id),
    CONSTRAINT chk_operation_template_seconds CHECK (standard_seconds >= 0 AND setup_seconds >= 0 AND teardown_seconds >= 0),
    CONSTRAINT chk_operation_template_version CHECK (version >= 1),
    CONSTRAINT chk_operation_template_secondary_array CHECK (jsonb_typeof(secondary_resource_ids) = 'array'),
    CONSTRAINT chk_operation_template_input_array CHECK (jsonb_typeof(input_material_ids) = 'array')
);
CREATE UNIQUE INDEX uq_operation_template_code_version ON operation_template (tenant_id, code, template_version) WHERE deleted = false;
CREATE INDEX idx_operation_template_primary_resource ON operation_template (primary_resource_id);
COMMENT ON TABLE operation_template IS '可复用工序模版；template_version 为业务版本，version 为乐观锁版本';

-- -----------------------------------------------------------------------------
-- 8. 工艺路线（DAG：主表 + 节点 + 边）
-- -----------------------------------------------------------------------------
CREATE TABLE routing (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    product_id          UUID        NOT NULL,
    routing_version     VARCHAR(32) NOT NULL,       -- PRD 中的 version（业务版本号）
    effective_date      DATE        NOT NULL,
    expiry_date         DATE,
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    is_panel            BOOLEAN     NOT NULL DEFAULT false,
    panel_size          INTEGER,
    nodes               JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- 图快照，routing_node 为准
    edges               JSONB       NOT NULL DEFAULT '[]'::jsonb,  -- 图快照，routing_edge 为准
    version             INTEGER     NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_routing_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_routing_product FOREIGN KEY (product_id) REFERENCES material (id),
    CONSTRAINT chk_routing_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_routing_version CHECK (version >= 1),
    CONSTRAINT chk_routing_effective_range CHECK (expiry_date IS NULL OR expiry_date >= effective_date),
    CONSTRAINT chk_routing_nodes_array CHECK (jsonb_typeof(nodes) = 'array'),
    CONSTRAINT chk_routing_edges_array CHECK (jsonb_typeof(edges) = 'array')
);
CREATE UNIQUE INDEX uq_routing_product_version ON routing (product_id, routing_version) WHERE deleted = false;
CREATE UNIQUE INDEX uq_routing_one_active_per_product ON routing (tenant_id, product_id) WHERE status = 'ACTIVE' AND deleted = false;
CREATE INDEX idx_routing_tenant_status ON routing (tenant_id, status);
COMMENT ON TABLE routing IS '工艺路线 DAG 主表；routing_version 为业务版本；nodes/edges 为前端图快照';

CREATE TABLE routing_node (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL,
    routing_id              UUID        NOT NULL,
    node_key                VARCHAR(64) NOT NULL,   -- 前端/JSON 中的节点标识
    operation_template_id   UUID,
    operation_code          VARCHAR(64),
    operation_name          VARCHAR(256),
    standard_seconds        INTEGER,
    setup_seconds           INTEGER,
    teardown_seconds        INTEGER,
    is_optional             BOOLEAN     NOT NULL DEFAULT false,
    -- 引用模版时的覆盖参数（如时长、资源覆盖）
    overrides               JSONB       NOT NULL DEFAULT '{}'::jsonb,
    position_x              NUMERIC(12,2),
    position_y              NUMERIC(12,2),
    seq_no                  INTEGER     NOT NULL DEFAULT 0,
    version                 INTEGER     NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    deleted                 BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_routing_node_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_routing_node_routing FOREIGN KEY (routing_id) REFERENCES routing (id) ON DELETE CASCADE,
    CONSTRAINT fk_routing_node_template FOREIGN KEY (operation_template_id) REFERENCES operation_template (id),
    CONSTRAINT chk_routing_node_version CHECK (version >= 1),
    CONSTRAINT chk_routing_node_overrides_object CHECK (jsonb_typeof(overrides) = 'object')
);
CREATE UNIQUE INDEX uq_routing_node_key ON routing_node (routing_id, node_key) WHERE deleted = false;
CREATE INDEX idx_routing_node_template ON routing_node (operation_template_id);
COMMENT ON TABLE routing_node IS '工艺路线节点；node_key 与 routing.nodes JSON 快照中的 id 对应';

CREATE TABLE routing_edge (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    routing_id    UUID        NOT NULL,
    from_node_id  UUID        NOT NULL,
    to_node_id    UUID        NOT NULL,
    depend_type   VARCHAR(8)  NOT NULL DEFAULT 'FS',
    lag_minutes   INTEGER     NOT NULL DEFAULT 0,
    version       INTEGER     NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    deleted       BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_routing_edge_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_routing_edge_routing FOREIGN KEY (routing_id) REFERENCES routing (id) ON DELETE CASCADE,
    CONSTRAINT fk_routing_edge_from FOREIGN KEY (from_node_id) REFERENCES routing_node (id) ON DELETE CASCADE,
    CONSTRAINT fk_routing_edge_to FOREIGN KEY (to_node_id) REFERENCES routing_node (id) ON DELETE CASCADE,
    CONSTRAINT chk_routing_edge_depend_type CHECK (depend_type IN ('FS', 'SS', 'FF')),
    CONSTRAINT chk_routing_edge_no_self CHECK (from_node_id <> to_node_id),
    CONSTRAINT chk_routing_edge_version CHECK (version >= 1)
);
CREATE UNIQUE INDEX uq_routing_edge_relation ON routing_edge (routing_id, from_node_id, to_node_id, depend_type) WHERE deleted = false;
CREATE INDEX idx_routing_edge_routing ON routing_edge (routing_id);
CREATE INDEX idx_routing_edge_to ON routing_edge (to_node_id);
COMMENT ON TABLE routing_edge IS '工艺路线依赖边；FS/SS/FF 三种依赖，含滞后时间 lag_minutes；环检测由应用层在保存时完成';

-- -----------------------------------------------------------------------------
-- 9. 订单
-- -----------------------------------------------------------------------------
CREATE TABLE orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    order_no        VARCHAR(64)  NOT NULL,
    source          VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    product_id      UUID         NOT NULL,
    qty             NUMERIC(18,4) NOT NULL,
    due_date        DATE         NOT NULL,
    priority        VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    customer_id     UUID,
    routing_id      UUID,
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    freeze_status   VARCHAR(24)  NOT NULL DEFAULT 'UNFROZEN',
    remark          TEXT,
    ext_attrs       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version         INTEGER      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_orders_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_orders_product FOREIGN KEY (product_id) REFERENCES material (id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customer (id),
    CONSTRAINT fk_orders_routing FOREIGN KEY (routing_id) REFERENCES routing (id),
    CONSTRAINT chk_orders_source CHECK (source IN ('ERP_SYNC', 'MANUAL', 'AGENT')),
    CONSTRAINT chk_orders_qty CHECK (qty > 0),
    CONSTRAINT chk_orders_priority CHECK (priority IN ('URGENT', 'HIGH', 'NORMAL', 'LOW')),
    CONSTRAINT chk_orders_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'SCHEDULED', 'RELEASED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_orders_freeze_status CHECK (freeze_status IN ('UNFROZEN', 'FROZEN', 'LOCKED_TIME_WINDOW', 'LOCKED_RESOURCE')),
    CONSTRAINT chk_orders_version CHECK (version >= 1),
    CONSTRAINT chk_orders_ext_attrs_object CHECK (jsonb_typeof(ext_attrs) = 'object')
);
CREATE UNIQUE INDEX uq_orders_tenant_order_no ON orders (tenant_id, order_no) WHERE deleted = false;
CREATE INDEX idx_orders_tenant_status_due_date ON orders (tenant_id, status, due_date) WHERE deleted = false;
CREATE INDEX idx_orders_tenant_customer ON orders (tenant_id, customer_id) WHERE deleted = false;
CREATE INDEX idx_orders_tenant_product_due_date ON orders (tenant_id, product_id, due_date) WHERE deleted = false;
COMMENT ON TABLE orders IS '销售/生产订单；status 与 freeze_status 为两条独立状态轴（见 docs/db/schema.md 状态机）';

-- -----------------------------------------------------------------------------
-- 10. 场景 Sandbox
-- -----------------------------------------------------------------------------
CREATE TABLE sandbox (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    name                VARCHAR(128) NOT NULL,
    type                VARCHAR(16)  NOT NULL DEFAULT 'WHAT_IF',
    parent_sandbox_id   UUID,
    -- 数据快照：订单 / 资源 / 策略 / 结果
    snapshot            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    expires_at          TIMESTAMPTZ,
    version             INTEGER      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_sandbox_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_sandbox_parent FOREIGN KEY (parent_sandbox_id) REFERENCES sandbox (id),
    CONSTRAINT chk_sandbox_type CHECK (type IN ('BASELINE', 'WHAT_IF', 'ARCHIVED')),
    CONSTRAINT chk_sandbox_version CHECK (version >= 1),
    CONSTRAINT chk_sandbox_snapshot_object CHECK (jsonb_typeof(snapshot) = 'object')
);
-- 每租户仅一个 BASELINE
CREATE UNIQUE INDEX uq_sandbox_baseline ON sandbox (tenant_id) WHERE type = 'BASELINE' AND deleted = false;
CREATE INDEX idx_sandbox_tenant_type_expires ON sandbox (tenant_id, type, expires_at) WHERE deleted = false;
COMMENT ON TABLE sandbox IS '排产场景；BASELINE 唯一，WHAT_IF 从 BASELINE 复制，ARCHIVED 只读';

-- -----------------------------------------------------------------------------
-- 11. 排产任务 Task（高频大表，BIGSERIAL）
-- -----------------------------------------------------------------------------
CREATE TABLE task (
    id                      BIGSERIAL PRIMARY KEY,
    tenant_id               UUID         NOT NULL,
    order_id                UUID         NOT NULL,
    sandbox_id              UUID         NOT NULL,
    operation_index         INTEGER      NOT NULL,
    operation_template_id   UUID,
    resource_id             UUID,
    secondary_resource_ids  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    start_time              TIMESTAMPTZ,
    end_time                TIMESTAMPTZ,
    setup_start_time        TIMESTAMPTZ,
    setup_end_time          TIMESTAMPTZ,
    qty                     NUMERIC(18,4),
    status                  VARCHAR(16)  NOT NULL DEFAULT 'PLANNED',
    sequence_in_resource    INTEGER,
    version                 INTEGER      NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_by              UUID,
    deleted                 BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_task_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_task_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_task_sandbox FOREIGN KEY (sandbox_id) REFERENCES sandbox (id),
    CONSTRAINT fk_task_operation_template FOREIGN KEY (operation_template_id) REFERENCES operation_template (id),
    CONSTRAINT fk_task_resource FOREIGN KEY (resource_id) REFERENCES resource (id),
    CONSTRAINT chk_task_status CHECK (status IN ('PLANNED', 'FROZEN', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_task_time_range CHECK (end_time IS NULL OR start_time IS NULL OR end_time > start_time),
    CONSTRAINT chk_task_qty CHECK (qty IS NULL OR qty > 0),
    CONSTRAINT chk_task_version CHECK (version >= 1),
    CONSTRAINT chk_task_secondary_array CHECK (jsonb_typeof(secondary_resource_ids) = 'array')
);
-- 甘特图：按 sandbox + 资源 + 时间查询
CREATE INDEX idx_task_sandbox_resource_start ON task (sandbox_id, resource_id, start_time);
-- 订单工序链
CREATE INDEX idx_task_order_operation ON task (order_id, operation_index);
CREATE INDEX idx_task_sandbox_status ON task (sandbox_id, status);
CREATE INDEX idx_task_resource_start ON task (resource_id, start_time);
COMMENT ON TABLE task IS '排产任务（求解结果）；BIGSERIAL 主键，版本号 version 用于乐观锁与人工调整冲突检测';
COMMENT ON COLUMN task.id IS 'BIGSERIAL：高频大表，索引紧凑；对外 API 使用 id，不再提供 UUID';

-- -----------------------------------------------------------------------------
-- 12. 排产策略 + 规则
-- -----------------------------------------------------------------------------
CREATE TABLE strategy (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL,
    name                VARCHAR(128) NOT NULL,
    direction           VARCHAR(16) NOT NULL DEFAULT 'FORWARD',
    strategy_version    INTEGER     NOT NULL DEFAULT 1,
    initial_rules       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    optimization_rules  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    effective_from      DATE        NOT NULL,
    effective_to        DATE,
    apply_condition     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version             INTEGER     NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT fk_strategy_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_strategy_direction CHECK (direction IN ('FORWARD', 'BACKWARD', 'BOTTLENECK')),
    CONSTRAINT chk_strategy_version CHECK (version >= 1),
    CONSTRAINT chk_strategy_effective_range CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT chk_strategy_initial_rules_object CHECK (jsonb_typeof(initial_rules) = 'object'),
    CONSTRAINT chk_strategy_optimization_rules_object CHECK (jsonb_typeof(optimization_rules) = 'object'),
    CONSTRAINT chk_strategy_apply_condition_object CHECK (jsonb_typeof(apply_condition) = 'object')
);
CREATE UNIQUE INDEX uq_strategy_tenant_name_version ON strategy (tenant_id, name, strategy_version) WHERE deleted = false;
CREATE INDEX idx_strategy_tenant_effective ON strategy (tenant_id, effective_from, effective_to) WHERE deleted = false;
COMMENT ON TABLE strategy IS '排产策略；strategy_version 为业务版本，version 为乐观锁版本';
COMMENT ON COLUMN strategy.initial_rules IS '初排规则对象；结构化规则以 strategy_rule 为准';
COMMENT ON COLUMN strategy.apply_condition IS '适用条件对象，如 {"productLines":[],"customerIds":[]}';

CREATE TABLE strategy_rule (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    strategy_id     UUID         NOT NULL,
    rule_code       VARCHAR(128) NOT NULL,
    name            VARCHAR(256),
    rule_phase      VARCHAR(16)  NOT NULL DEFAULT 'OPTIMIZATION',
    rule_type       VARCHAR(16)  NOT NULL DEFAULT 'SOFT',
    weight          NUMERIC(8,4) NOT NULL DEFAULT 1,
    params          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    seq_no          INTEGER      NOT NULL DEFAULT 0,
    version         INTEGER      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_strategy_rule_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_strategy_rule_strategy FOREIGN KEY (strategy_id) REFERENCES strategy (id) ON DELETE CASCADE,
    CONSTRAINT chk_strategy_rule_phase CHECK (rule_phase IN ('INITIAL', 'OPTIMIZATION')),
    CONSTRAINT chk_strategy_rule_type CHECK (rule_type IN ('HARD', 'SOFT')),
    CONSTRAINT chk_strategy_rule_weight CHECK (weight >= 0),
    CONSTRAINT chk_strategy_rule_version CHECK (version >= 1),
    CONSTRAINT chk_strategy_rule_params_object CHECK (jsonb_typeof(params) = 'object')
);
CREATE UNIQUE INDEX uq_strategy_rule_code ON strategy_rule (strategy_id, rule_code) WHERE deleted = false;
CREATE INDEX idx_strategy_rule_strategy_phase ON strategy_rule (strategy_id, rule_phase, seq_no);
COMMENT ON TABLE strategy_rule IS '策略规则明细；rule_phase=INITIAL/OPTIMIZATION，rule_type=HARD/SOFT';

-- -----------------------------------------------------------------------------
-- 13. 求解任务 SolveTask（高频大表，BIGSERIAL）
-- -----------------------------------------------------------------------------
CREATE TABLE solve_task (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    sandbox_id          UUID         NOT NULL,
    strategy_id         UUID,
    engine              VARCHAR(16)  NOT NULL DEFAULT 'TIMEFOLD',
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    config              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    score_hard          BIGINT,
    score_soft          BIGINT,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    metrics             JSONB        NOT NULL DEFAULT '{}'::jsonb,
    error_message       TEXT,
    idempotency_key     VARCHAR(64),
    version             INTEGER      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_solve_task_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_solve_task_sandbox FOREIGN KEY (sandbox_id) REFERENCES sandbox (id),
    CONSTRAINT fk_solve_task_strategy FOREIGN KEY (strategy_id) REFERENCES strategy (id),
    CONSTRAINT chk_solve_task_engine CHECK (engine IN ('TIMEFOLD', 'ORTOOLS', 'RULE_BASED')),
    CONSTRAINT chk_solve_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT')),
    CONSTRAINT chk_solve_task_version CHECK (version >= 1),
    CONSTRAINT chk_solve_task_config_object CHECK (jsonb_typeof(config) = 'object'),
    CONSTRAINT chk_solve_task_metrics_object CHECK (jsonb_typeof(metrics) = 'object'),
    CONSTRAINT chk_solve_task_time_range CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);
CREATE UNIQUE INDEX uq_solve_task_idempotency ON solve_task (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND deleted = false;
CREATE INDEX idx_solve_task_sandbox_status_created ON solve_task (sandbox_id, status, created_at);
CREATE INDEX idx_solve_task_strategy ON solve_task (strategy_id);
COMMENT ON TABLE solve_task IS '求解任务生命周期；idempotency_key 幂等，engine=Timefold/OR-Tools/RuleBased';

-- -----------------------------------------------------------------------------
-- 14. 约束归因
-- -----------------------------------------------------------------------------
CREATE TABLE constraint_justification (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    solve_task_id       BIGINT       NOT NULL,
    constraint_id       VARCHAR(128) NOT NULL,
    constraint_name     VARCHAR(256),
    score_impact        VARCHAR(64),
    implicated_facts    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    explanation_zh      TEXT,
    version             INTEGER      NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT fk_constraint_justification_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_constraint_justification_solve_task FOREIGN KEY (solve_task_id) REFERENCES solve_task (id) ON DELETE CASCADE,
    CONSTRAINT chk_constraint_justification_version CHECK (version >= 1),
    CONSTRAINT chk_constraint_justification_facts_array CHECK (jsonb_typeof(implicated_facts) = 'array')
);
CREATE INDEX idx_constraint_justification_solve_task ON constraint_justification (solve_task_id, constraint_id);
COMMENT ON TABLE constraint_justification IS 'Timefold ConstraintJustification 归因数据；explanation_zh 由 Agent 生成';

-- -----------------------------------------------------------------------------
-- 15. 人工调整历史（撤销栈）
-- -----------------------------------------------------------------------------
CREATE TABLE task_adjustment (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    task_id         BIGINT       NOT NULL,
    sandbox_id      UUID         NOT NULL,
    adjust_type     VARCHAR(16)  NOT NULL,
    before_value    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    after_value     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    reason          VARCHAR(512),
    operator_id     UUID,
    undone          BOOLEAN      NOT NULL DEFAULT false,
    undo_of_id      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    CONSTRAINT fk_task_adjustment_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_task_adjustment_task FOREIGN KEY (task_id) REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_adjustment_sandbox FOREIGN KEY (sandbox_id) REFERENCES sandbox (id),
    CONSTRAINT fk_task_adjustment_undo_of FOREIGN KEY (undo_of_id) REFERENCES task_adjustment (id),
    CONSTRAINT chk_task_adjustment_type CHECK (adjust_type IN ('SPLIT', 'MERGE', 'MOVE', 'LOCK', 'FREEZE', 'UNFREEZE')),
    CONSTRAINT chk_task_adjustment_before_object CHECK (jsonb_typeof(before_value) = 'object'),
    CONSTRAINT chk_task_adjustment_after_object CHECK (jsonb_typeof(after_value) = 'object')
);
CREATE INDEX idx_task_adjustment_task_created ON task_adjustment (task_id, created_at DESC);
CREATE INDEX idx_task_adjustment_sandbox_created ON task_adjustment (sandbox_id, created_at DESC);
COMMENT ON TABLE task_adjustment IS '人工调整历史（追加型）；before/after 存任务快照，undone 支持撤销栈';

-- -----------------------------------------------------------------------------
-- 16. 审计日志（追加型，BIGSERIAL）
-- -----------------------------------------------------------------------------
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    user_id         UUID,                       -- 弱外键：仅索引，不对 users 建 FK
    action          VARCHAR(64) NOT NULL,
    resource_type   VARCHAR(64),
    resource_id     VARCHAR(64),
    before_value    JSONB,
    after_value     JSONB,
    ip              VARCHAR(64),
    user_agent      TEXT,
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_tenant_created_action ON audit_log (tenant_id, created_at DESC, action);
CREATE INDEX idx_audit_log_resource ON audit_log (resource_type, resource_id);
CREATE INDEX idx_audit_log_user_created ON audit_log (tenant_id, user_id, created_at DESC);
COMMENT ON TABLE audit_log IS '审计日志；追加只读，无 updated_at/deleted（不可篡改）';

-- =============================================================================
-- 17. 种子数据（最小集合）
-- 说明：仅包含多租户默认租户、RBAC 预置角色/权限、演示管理员账号、默认 BASELINE 场景、
--       7x24 全厂日历。业务主数据（物料/BOM/资源等）不预置，由用户导入。
-- =============================================================================

-- 默认租户（MVP 单租户；所有业务表 tenant_id 默认值引用此 UUID）
INSERT INTO tenant (id, code, name, status, config)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'DEMO',
    '衡策 APS 演示租户',
    'ACTIVE',
    '{"solverTimeoutSeconds": 60, "maxUsers": 20, "maxSandboxes": 20, "features": {"agent": true, "multiTenant": false}}'::jsonb
)
ON CONFLICT (id) DO NOTHING;

-- 预置角色
INSERT INTO role (id, tenant_id, code, name, description, is_builtin)
VALUES
    ('00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000001', 'ADMIN',             '系统管理员',   '全部权限', true),
    ('00000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000001', 'PLANNER',           '计划员',       '排产、调整、发布', true),
    ('00000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-000000000001', 'PROCESS_ENGINEER',  '工艺工程师',   'BOM、工艺路线、资源维护', true),
    ('00000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000001', 'SALES',             '销售',         '订单创建、ATP 查询（只读排产）', true),
    ('00000000-0000-0000-0000-000000000105', '00000000-0000-0000-0000-000000000001', 'VIEWER',            '只读用户',     '全部模块只读', true)
ON CONFLICT (id) DO NOTHING;

-- 预置权限点（模块级 + 操作级）
INSERT INTO permission (id, tenant_id, code, name, module, description)
VALUES
    ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000001', 'MD_READ',          '基础数据查看', 'MD',  '物料/BOM/资源/日历/客户/供应商只读'),
    ('00000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000001', 'MD_WRITE',         '基础数据维护', 'MD',  '基础数据增删改'),
    ('00000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000001', 'PR_READ',          '工艺查看',     'PR',  '工序模版/工艺路线只读'),
    ('00000000-0000-0000-0000-000000000204', '00000000-0000-0000-0000-000000000001', 'PR_WRITE',         '工艺维护',     'PR',  '工序模版/工艺路线增删改'),
    ('00000000-0000-0000-0000-000000000205', '00000000-0000-0000-0000-000000000001', 'OM_READ',          '订单查看',     'OM',  '订单列表/详情只读'),
    ('00000000-0000-0000-0000-000000000206', '00000000-0000-0000-0000-000000000001', 'OM_WRITE',         '订单维护',     'OM',  '订单创建/修改/取消'),
    ('00000000-0000-0000-0000-000000000207', '00000000-0000-0000-0000-000000000001', 'OM_ATP',           '交期预估',     'OM',  'ATP 交期预估查询'),
    ('00000000-0000-0000-0000-000000000208', '00000000-0000-0000-0000-000000000001', 'ORDER_FREEZE',     '订单冻结',     'OM',  '订单冻结/解冻'),
    ('00000000-0000-0000-0000-000000000209', '00000000-0000-0000-0000-000000000001', 'SS_READ',          '策略查看',     'SS',  '排产策略只读'),
    ('00000000-0000-0000-0000-000000000210', '00000000-0000-0000-0000-000000000001', 'SS_WRITE',         '策略维护',     'SS',  '排产策略增删改/克隆'),
    ('00000000-0000-0000-0000-000000000211', '00000000-0000-0000-0000-000000000001', 'SE_SOLVE',         '触发求解',     'SE',  '发起排产求解任务'),
    ('00000000-0000-0000-0000-000000000212', '00000000-0000-0000-0000-000000000001', 'SCHEDULE_ADJUST',  '计划调整',     'GT',  '甘特图人工调整（拆分/合并/移动/锁定）'),
    ('00000000-0000-0000-0000-000000000213', '00000000-0000-0000-0000-000000000001', 'SCHEDULE_PUBLISH', '计划发布',     'GT',  '计划发布/回滚/应用为 BASELINE'),
    ('00000000-0000-0000-0000-000000000214', '00000000-0000-0000-0000-000000000001', 'SYS_USER',         '用户管理',     'SYS', '用户增删改'),
    ('00000000-0000-0000-0000-000000000215', '00000000-0000-0000-0000-000000000001', 'SYS_ROLE',         '角色权限管理', 'SYS', '角色与权限分配'),
    ('00000000-0000-0000-0000-000000000216', '00000000-0000-0000-0000-000000000001', 'SYS_AUDIT',        '审计查看',     'SYS', '审计日志查询/导出'),
    ('00000000-0000-0000-0000-000000000217', '00000000-0000-0000-0000-000000000001', 'AGENT_EVENT',      '异常事件查看', 'AG',  'Agent 异常事件与决策查看'),
    ('00000000-0000-0000-0000-000000000218', '00000000-0000-0000-0000-000000000001', 'AGENT_CONFIRM',    'Agent 决策确认', 'AG', '确认/驳回 Agent 重排建议')
ON CONFLICT (id) DO NOTHING;

-- ADMIN：全部权限
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000101', p.id
FROM permission p
WHERE p.tenant_id = '00000000-0000-0000-0000-000000000001'
ON CONFLICT DO NOTHING;

-- PLANNER：基础数据只读 + 订单/策略/求解/调整/发布 + Agent
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000102', p.id
FROM permission p
WHERE p.tenant_id = '00000000-0000-0000-0000-000000000001'
  AND p.code IN ('MD_READ','PR_READ','OM_READ','OM_WRITE','OM_ATP','ORDER_FREEZE','SS_READ','SS_WRITE',
                 'SE_SOLVE','SCHEDULE_ADJUST','SCHEDULE_PUBLISH','AGENT_EVENT','AGENT_CONFIRM')
ON CONFLICT DO NOTHING;

-- PROCESS_ENGINEER：基础数据/工艺维护 + 订单只读 + 策略只读 + Agent 查看
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000103', p.id
FROM permission p
WHERE p.tenant_id = '00000000-0000-0000-0000-000000000001'
  AND p.code IN ('MD_READ','MD_WRITE','PR_READ','PR_WRITE','OM_READ','SS_READ','AGENT_EVENT')
ON CONFLICT DO NOTHING;

-- SALES：基础数据只读 + 订单维护 + ATP
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000104', p.id
FROM permission p
WHERE p.tenant_id = '00000000-0000-0000-0000-000000000001'
  AND p.code IN ('MD_READ','OM_READ','OM_WRITE','OM_ATP')
ON CONFLICT DO NOTHING;

-- VIEWER：只读
INSERT INTO role_permission (role_id, permission_id)
SELECT '00000000-0000-0000-0000-000000000105', p.id
FROM permission p
WHERE p.tenant_id = '00000000-0000-0000-0000-000000000001'
  AND p.code IN ('MD_READ','PR_READ','OM_READ','SS_READ')
ON CONFLICT DO NOTHING;

-- 管理员账号：password_hash 留空，首次部署由应用初始化流程设置口令（避免默认口令风险）
INSERT INTO users (id, tenant_id, username, display_name, password_hash, email, status)
VALUES (
    '00000000-0000-0000-0000-000000000301',
    '00000000-0000-0000-0000-000000000001',
    'admin',
    '系统管理员',
    NULL,
    NULL,
    'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role (user_id, role_id)
VALUES (
    '00000000-0000-0000-0000-000000000301',
    '00000000-0000-0000-0000-000000000101'
)
ON CONFLICT DO NOTHING;

-- 默认 BASELINE 场景（每租户唯一）
INSERT INTO sandbox (id, tenant_id, name, type, snapshot, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000401',
    '00000000-0000-0000-0000-000000000001',
    '主计划（BASELINE）',
    'BASELINE',
    '{"note": "系统初始化自动创建，勿删除"}'::jsonb,
    now()
)
ON CONFLICT (id) DO NOTHING;

-- 默认 7x24 全厂日历（资源未指定专属日历时兜底使用）
INSERT INTO calendar (id, tenant_id, code, name, type, effective_from, shifts, holidays)
VALUES (
    '00000000-0000-0000-0000-000000000501',
    '00000000-0000-0000-0000-000000000001',
    'CAL-7X24',
    '7x24 全厂默认日历',
    'FACTORY',
    DATE '2026-01-01',
    '[{"startTime": "00:00", "endTime": "24:00", "daysOfWeek": [1,2,3,4,5,6,7]}]'::jsonb,
    '[]'::jsonb
)
ON CONFLICT (id) DO NOTHING;
