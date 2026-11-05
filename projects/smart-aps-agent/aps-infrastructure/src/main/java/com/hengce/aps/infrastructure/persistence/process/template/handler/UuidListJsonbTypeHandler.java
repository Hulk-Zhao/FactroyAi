package com.hengce.aps.infrastructure.persistence.process.template.handler;

import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * UUID 数组型 JSONB 列的读写处理器，用于 operation_template 的
 * {@code secondary_resource_ids} / {@code input_material_ids}。
 *
 * <p>复用 {@link JsonbTypeHandler} 的 JSONB 读写逻辑；区别在于反序列化后把
 * Jackson 默认产出的字符串元素归一化为 {@link UUID}，避免上层拿到 {@code List<String>}
 * 后再做易错的强制类型转换。</p>
 */
public class UuidListJsonbTypeHandler extends JsonbTypeHandler<List<UUID>> {

    @Override
    public List<UUID> getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return toUuidList(super.getNullableResult(resultSet, columnName));
    }

    @Override
    public List<UUID> getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return toUuidList(super.getNullableResult(resultSet, columnIndex));
    }

    @Override
    public List<UUID> getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return toUuidList(super.getNullableResult(statement, columnIndex));
    }

    private static List<UUID> toUuidList(List<?> raw) {
        if (raw == null) {
            return null;
        }
        List<UUID> result = new ArrayList<>(raw.size());
        for (Object value : raw) {
            if (value == null) {
                result.add(null);
            } else if (value instanceof UUID uuid) {
                result.add(uuid);
            } else {
                result.add(UUID.fromString(value.toString()));
            }
        }
        return result;
    }
}
