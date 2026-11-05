package com.hengce.aps.infrastructure.persistence.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PostgreSQL JSONB 字段读写处理器，适用于 ext_attrs / industry_attrs / config / metrics 等列。
 *
 * <p>写入使用 {@link Types#OTHER}，避免 JDBC 以 VARCHAR 提交导致 PostgreSQL 报
 * 「column is of type jsonb but expression is of type character varying」。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 * @TableField(value = "ext_attrs", typeHandler = JsonbTypeHandler.class)
 * private Map<String, Object> extAttrs;
 *
 * // 需要强类型时
 * @TableField(value = "metrics", typeHandler = MetricsJsonbTypeHandler.class)
 * }</pre>
 * <p>无参构造返回 {@code Map}/{@code List} 等 Jackson 默认结构；有参构造
 * {@link #JsonbTypeHandler(Class)} 可绑定具体 DTO。</p>
 *
 * @param <T> JSONB 列对应的 Java 类型
 */
public class JsonbTypeHandler<T> extends BaseTypeHandler<T> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Class<T> targetType;

    /**
     * MyBatis 反射实例化入口：按 JSON 结构返回 Map / List 等原始类型。
     */
    public JsonbTypeHandler() {
        this.targetType = null;
    }

    /**
     * 指定反序列化目标类型。
     *
     * @param targetType JSONB 列映射的 Java 类型
     */
    public JsonbTypeHandler(Class<T> targetType) {
        this.targetType = targetType;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setObject(i, OBJECT_MAPPER.writeValueAsString(parameter), Types.OTHER);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Failed to serialize JSONB parameter", exception);
        }
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    @SuppressWarnings("unchecked")
    private T parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Class<?> type = targetType == null ? Object.class : targetType;
            return (T) OBJECT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Failed to deserialize JSONB column", exception);
        }
    }
}
