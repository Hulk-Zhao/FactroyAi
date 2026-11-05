-- =============================================================================
-- V11 · 计划下发通知 outbox（W5 / C33）
-- 归属：M3-W5（contract-freeze §25.5 / §25.13 号段台账）
-- 约束：只新增 notification_outbox 表；不改 V1–V7 任何对象，不碰 plan_version。
-- 幂等：唯一 (tenant_id, dispatch_id, channel, address)，重试不得产生重复行。
-- 状态机：QUEUED -> SENT | FAILED；每目标最多 2 次尝试，失败退避 30s。
-- 回滚点 R11：DROP TABLE notification_outbox 即可回到无 outbox 状态（不触及其他表）。
-- =============================================================================

CREATE TABLE notification_outbox (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           UUID        NOT NULL,
    dispatch_id         UUID        NOT NULL,
    plan_id             UUID        NOT NULL,
    channel             VARCHAR(16) NOT NULL,
    address             VARCHAR(256) NOT NULL,
    message             TEXT,
    status              VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    attempts            INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_notification_outbox_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT fk_notification_outbox_plan FOREIGN KEY (plan_id) REFERENCES plan_version (id),
    CONSTRAINT chk_notification_outbox_channel CHECK (channel IN ('EMAIL', 'SMS', 'FEISHU')),
    CONSTRAINT chk_notification_outbox_status CHECK (status IN ('QUEUED', 'SENT', 'FAILED')),
    CONSTRAINT chk_notification_outbox_attempts CHECK (attempts >= 0)
);

CREATE UNIQUE INDEX uq_notification_outbox_target
    ON notification_outbox (tenant_id, dispatch_id, channel, address);
CREATE INDEX idx_notification_outbox_due
    ON notification_outbox (tenant_id, status, next_attempt_at);

COMMENT ON TABLE notification_outbox IS '计划下发 outbox；只入队不真发，每目标最多 2 次尝试、失败退避 30s';
