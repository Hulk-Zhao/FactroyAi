package com.hengce.aps.infrastructure.persistence.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 审计日志追加写入与只读查询，对应 V1 的 {@code audit_log}（追加只读，无 updated_at/deleted）。
 *
 * <p>{@code audit_log} 不参与 MyBatis 实体映射，使用 {@link JdbcTemplate} 显式携带 {@code tenant_id}，
 * 避免依赖租户拦截器。{@code before_value}/{@code after_value} 仅记录面向审计的字段快照。</p>
 */
@Repository
public class AuditLogRepository {

    /**
     * JSON 序列化器；调用方只传字符串/数字等可序列化值，避免 Java 8 时间类型依赖。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE = new TypeReference<>() {
    };

    private static final String SELECT_COLUMNS = """
            SELECT id, user_id, action, resource_type, resource_id,
                   before_value::text AS before_value, after_value::text AS after_value,
                   ip, trace_id, created_at
              FROM audit_log
            """;

    private static final RowMapper<AuditLogRow> ROW_MAPPER = (rs, rowNum) -> new AuditLogRow(
            rs.getLong("id"),
            (UUID) rs.getObject("user_id"),
            rs.getString("action"),
            rs.getString("resource_type"),
            rs.getString("resource_id"),
            parseJson(rs.getString("before_value")),
            parseJson(rs.getString("after_value")),
            rs.getString("ip"),
            rs.getString("trace_id"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造持久层。
     *
     * @param jdbcTemplate Spring JDBC 模板
     */
    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 追加一条审计记录，操作人取自 {@link AuditContext}。
     *
     * @param tenantId 租户标识
     * @param action 动作编码，如 ORDER_FROZEN / ORDER_RELEASED
     * @param resourceType 资源类型，如 ORDER
     * @param resourceId 资源业务标识，如订单号
     * @param beforeValue 变更前字段快照，可为空
     * @param afterValue 变更后字段快照，可为空
     */
    public void record(UUID tenantId, String action, String resourceType, String resourceId,
                       Map<String, Object> beforeValue, Map<String, Object> afterValue) {
        record(tenantId, AuditContext.getCurrentUserId(), action, resourceType, resourceId,
                beforeValue, afterValue);
    }

    /**
     * 追加一条审计记录，显式指定操作人（登录成功/失败等未进入认证上下文的场景）。
     *
     * @param tenantId 租户标识
     * @param userId 操作人，可为空（API Key / off 模式不得伪造）
     * @param action 动作编码
     * @param resourceType 资源类型
     * @param resourceId 资源业务标识
     * @param beforeValue 变更前快照
     * @param afterValue 变更后快照
     */
    public void record(UUID tenantId, UUID userId, String action, String resourceType, String resourceId,
                       Map<String, Object> beforeValue, Map<String, Object> afterValue) {
        String identityMarker = AuditContext.getIdentitySource() == AuditContext.IdentitySource.DEV_HEADER
                ? AuditContext.DEV_HEADER_MARKER : null;
        String sql = """
                INSERT INTO audit_log (tenant_id, user_id, action, resource_type, resource_id,
                                       before_value, after_value, user_agent)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """;
        jdbcTemplate.update(sql, tenantId, userId, action, resourceType, resourceId,
                toJson(beforeValue), toJson(afterValue), identityMarker);
    }

    /**
     * 分页查询审计日志，过滤条件均为可选；时间区间为 {@code [from, to)}。
     *
     * @param tenantId 租户（隐式，不接受请求参数）
     * @param action 动作精确匹配
     * @param resourceType 资源类型精确匹配
     * @param resourceId 资源标识精确匹配
     * @param userId 操作人精确匹配
     * @param from 起始时间（含）
     * @param to 结束时间（不含）
     * @param ascending true 按 createdAt 升序，false 降序
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 分页结果
     */
    public AuditLogPage query(UUID tenantId, String action, String resourceType, String resourceId,
                              UUID userId, OffsetDateTime from, OffsetDateTime to, boolean ascending,
                              int offset, int limit) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        appendEquals(where, args, "action", action);
        appendEquals(where, args, "resource_type", resourceType);
        appendEquals(where, args, "resource_id", resourceId);
        if (userId != null) {
            where.append(" AND user_id = ?");
            args.add(userId);
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND created_at < ?");
            args.add(to);
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM audit_log" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        String order = ascending ? " ORDER BY created_at ASC, id ASC" : " ORDER BY created_at DESC, id DESC";
        List<AuditLogRow> items = jdbcTemplate.query(
                SELECT_COLUMNS + where + order + " LIMIT ? OFFSET ?", ROW_MAPPER, pageArgs.toArray());
        return new AuditLogPage(items, total == null ? 0L : total);
    }

    /**
     * 按动作与资源标识查找一条已存在的审计记录，用于 Agent 事件上报的幂等判重。
     *
     * <p>调用方需先持有 {@link #lockIdempotencyKey(UUID, String)} 的咨询锁，
     * 才能保证“判重 + 写入”在并发下不产生重复行。</p>
     *
     * @param tenantId 租户标识
     * @param action 动作编码
     * @param resourceId 资源标识
     * @return 最早的一条记录
     */
    public java.util.Optional<AuditLogRow> findFirstByResource(UUID tenantId, String action, String resourceId) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE tenant_id = ? AND action = ? AND resource_id = ?"
                        + " ORDER BY id ASC LIMIT 1",
                ROW_MAPPER, tenantId, action, resourceId).stream().findFirst();
    }

    /**
     * 在当前事务内获取按“租户 + 幂等键”串行化的 Postgres 咨询锁。
     *
     * <p>不新增唯一索引即可保证判重写入的并发安全；锁随事务结束自动释放。</p>
     *
     * @param tenantId 租户标识
     * @param idempotencyKey 幂等键
     */
    public void lockIdempotencyKey(UUID tenantId, String idempotencyKey) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, rowNum) -> (Void) null,
                tenantId + ":agent-event:" + idempotencyKey);
    }

    private static void appendEquals(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, JSON_MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计快照反序列化失败", exception);
        }
    }

    private static String toJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计快照序列化失败", exception);
        }
    }
}
