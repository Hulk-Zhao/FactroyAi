package com.hengce.aps.agent.prompt;

import com.hengce.aps.common.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于 PostgreSQL 表 {@code agent_prompt_version} 的 Prompt 版本存储（V13）。
 *
 * <p>选型理由：Prompt 版本需要与运行记录同库、同租户、可审计，且要能在一个事务内
 * 「取消旧 active + 激活新 active」；单表 + 部分唯一索引即可满足，不需要引入额外组件。
 * 版本号由「同键最大版本 + 1」生成，数据库唯一约束兜底并发。</p>
 */
@Component
public class JdbcPromptStore implements PromptStore {

    private static final RowMapper<PromptVersion> ROW_MAPPER = (resultSet, rowNum) -> new PromptVersion(
            resultSet.getObject("tenant_id", UUID.class),
            resultSet.getString("prompt_key"),
            resultSet.getInt("version"),
            resultSet.getString("content"),
            resultSet.getString("note"),
            resultSet.getBoolean("active"),
            resultSet.getObject("created_at", OffsetDateTime.class),
            resultSet.getObject("created_by", UUID.class));

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造存储。
     *
     * @param jdbcTemplate JDBC 模板
     */
    public JdbcPromptStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public PromptVersion register(String promptKey, String content, String note, UUID createdBy) {
        requireKey(promptKey);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Prompt 内容不能为空: " + promptKey);
        }
        UUID tenantId = TenantContext.getTenantId();
        Integer nextVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM agent_prompt_version "
                        + "WHERE tenant_id = ? AND prompt_key = ?",
                Integer.class, tenantId, promptKey);
        jdbcTemplate.update(
                "INSERT INTO agent_prompt_version "
                        + "(tenant_id, prompt_key, version, content, note, active, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, false, ?)",
                tenantId, promptKey, nextVersion, content, note, createdBy);
        return find(promptKey, nextVersion)
                .orElseThrow(() -> new IllegalStateException("Prompt 版本写入后不可见: " + promptKey));
    }

    @Override
    public Optional<PromptVersion> findActive(String promptKey) {
        requireKey(promptKey);
        List<PromptVersion> rows = jdbcTemplate.query(
                "SELECT tenant_id, prompt_key, version, content, note, active, created_at, created_by "
                        + "FROM agent_prompt_version WHERE tenant_id = ? AND prompt_key = ? AND active",
                ROW_MAPPER, TenantContext.getTenantId(), promptKey);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<PromptVersion> find(String promptKey, int version) {
        requireKey(promptKey);
        List<PromptVersion> rows = jdbcTemplate.query(
                "SELECT tenant_id, prompt_key, version, content, note, active, created_at, created_by "
                        + "FROM agent_prompt_version WHERE tenant_id = ? AND prompt_key = ? AND version = ?",
                ROW_MAPPER, TenantContext.getTenantId(), promptKey, version);
        return rows.stream().findFirst();
    }

    @Override
    public List<PromptVersion> list(String promptKey) {
        requireKey(promptKey);
        return jdbcTemplate.query(
                "SELECT tenant_id, prompt_key, version, content, note, active, created_at, created_by "
                        + "FROM agent_prompt_version WHERE tenant_id = ? AND prompt_key = ? "
                        + "ORDER BY version",
                ROW_MAPPER, TenantContext.getTenantId(), promptKey);
    }

    @Override
    @Transactional
    public PromptVersion activate(String promptKey, int version) {
        requireKey(promptKey);
        UUID tenantId = TenantContext.getTenantId();
        if (find(promptKey, version).isEmpty()) {
            throw new IllegalArgumentException("Prompt 版本不存在: " + promptKey + "#" + version);
        }
        jdbcTemplate.update(
                "UPDATE agent_prompt_version SET active = false "
                        + "WHERE tenant_id = ? AND prompt_key = ? AND active",
                tenantId, promptKey);
        jdbcTemplate.update(
                "UPDATE agent_prompt_version SET active = true "
                        + "WHERE tenant_id = ? AND prompt_key = ? AND version = ?",
                tenantId, promptKey, version);
        return find(promptKey, version)
                .orElseThrow(() -> new IllegalStateException("激活后版本不可见: " + promptKey + "#" + version));
    }

    @Override
    @Transactional
    public Optional<PromptVersion> rollback(String promptKey) {
        requireKey(promptKey);
        Optional<PromptVersion> active = findActive(promptKey);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        int currentVersion = active.get().version();
        Integer previous = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM agent_prompt_version "
                        + "WHERE tenant_id = ? AND prompt_key = ? AND version < ?",
                Integer.class, TenantContext.getTenantId(), promptKey, currentVersion);
        if (previous == null) {
            return Optional.empty();
        }
        activate(promptKey, previous);
        return find(promptKey, previous);
    }

    @Override
    public boolean hasAny(String promptKey) {
        requireKey(promptKey);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_prompt_version WHERE tenant_id = ? AND prompt_key = ?",
                Long.class, TenantContext.getTenantId(), promptKey);
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public PromptVersion ensureActive(String promptKey, String content, String note) {
        requireKey(promptKey);
        Optional<PromptVersion> active = findActive(promptKey);
        if (active.isPresent()) {
            return active.get();
        }
        if (!hasAny(promptKey)) {
            PromptVersion created = register(promptKey, content, note, null);
            return activate(promptKey, created.version());
        }
        Integer latest = jdbcTemplate.queryForObject(
                "SELECT MAX(version) FROM agent_prompt_version WHERE tenant_id = ? AND prompt_key = ?",
                Integer.class, TenantContext.getTenantId(), promptKey);
        return activate(promptKey, latest == null ? 1 : latest);
    }

    private static void requireKey(String promptKey) {
        if (promptKey == null || promptKey.isBlank()) {
            throw new IllegalArgumentException("Prompt 键不能为空");
        }
    }
}
