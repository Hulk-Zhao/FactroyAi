package com.hengce.aps.infrastructure.persistence.process.template.handler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * PostgreSQL uuid 列的类型处理器。
 *
 * <p>MyBatis 3.5 默认注册表不包含 UUID 处理器，工序模版、日历、物料等业务表均以
 * UUID 作为主键或外键，因此由 {@code MybatisTypeHandlerConfiguration} 全局注册到
 * {@code TypeHandlerRegistry}，避免各 Mapper 逐个声明。</p>
 *
 * <p>写入按 {@link Types#OTHER} 交给 PostgreSQL JDBC 驱动做 uuid 适配；参数为
 * {@code null} 且未声明 {@link JdbcType} 时同样按 {@link Types#OTHER} 提交
 * {@code setNull}，避免 {@link BaseTypeHandler} 抛出「JDBC requires that the JdbcType
 * must be specified」。</p>
 */
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setParameter(PreparedStatement statement, int index, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        if (parameter == null) {
            statement.setNull(index, jdbcType == null ? Types.OTHER : jdbcType.TYPE_CODE);
            return;
        }
        setNonNullParameter(statement, index, parameter, jdbcType);
    }

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, parameter, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return toUuid(resultSet.getObject(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return toUuid(resultSet.getObject(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return toUuid(statement.getObject(columnIndex));
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
