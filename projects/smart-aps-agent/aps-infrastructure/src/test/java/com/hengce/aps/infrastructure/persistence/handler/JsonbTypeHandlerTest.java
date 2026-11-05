package com.hengce.aps.infrastructure.persistence.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * JsonbTypeHandler 序列化/反序列化测试，不依赖 PostgreSQL。
 */
class JsonbTypeHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldWriteJsonWithTypesOther() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        JsonbTypeHandler<Map<String, Object>> handler = new JsonbTypeHandler<>();
        Map<String, Object> attrs = Map.of("color", "black", "panel_size", 4);

        handler.setNonNullParameter(statement, 1, attrs, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(statement).setObject(eq(1), captor.capture(), eq(Types.OTHER));
        JsonNode node = OBJECT_MAPPER.readTree(captor.getValue());
        assertEquals("black", node.get("color").asText());
        assertEquals(4, node.get("panel_size").asInt());
    }

    @Test
    void shouldReadObjectColumnAsMap() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("ext_attrs"))
                .thenReturn("{\"color\":\"black\",\"tags\":[\"rohs\",\"reach\"]}");

        Object value = new JsonbTypeHandler<>().getNullableResult(resultSet, "ext_attrs");

        assertInstanceOf(Map.class, value);
        Map<?, ?> map = (Map<?, ?>) value;
        assertEquals("black", map.get("color"));
        assertInstanceOf(List.class, map.get("tags"));
    }

    @Test
    void shouldReadArrayColumnAsList() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(2))
                .thenReturn("[\"00000000-0000-0000-0000-000000000101\",\"00000000-0000-0000-0000-000000000102\"]");

        Object value = new JsonbTypeHandler<>().getNullableResult(resultSet, 2);

        List<?> list = assertInstanceOf(List.class, value);
        assertEquals(2, list.size());
        assertEquals("00000000-0000-0000-0000-000000000101", list.get(0));
    }

    @Test
    void shouldReturnNullForNullOrDefaultColumn() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("metrics")).thenReturn(null);
        when(resultSet.getString("config")).thenReturn("  ");

        assertNull(new JsonbTypeHandler<>().getNullableResult(resultSet, "metrics"));
        assertNull(new JsonbTypeHandler<>().getNullableResult(resultSet, "config"));
    }

    @Test
    void shouldReadTypedColumn() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("note")).thenReturn("\"每周三保养\"");

        JsonbTypeHandler<String> handler = new JsonbTypeHandler<>(String.class);

        assertEquals("每周三保养", handler.getNullableResult(resultSet, "note"));
    }
}
