package com.hengce.aps.infrastructure.persistence.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hengce.aps.infrastructure.persistence.process.template.handler.UuidTypeHandler;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

/**
 * UuidTypeHandler 读写与 null 处理测试，不依赖 PostgreSQL。
 */
class UuidTypeHandlerTest {

    private static final UUID SAMPLE = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void shouldWriteUuidWithTypesOther() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        UuidTypeHandler handler = new UuidTypeHandler();

        handler.setNonNullParameter(statement, 1, SAMPLE, null);

        verify(statement).setObject(1, SAMPLE, Types.OTHER);
    }

    @Test
    void shouldWriteNullWithTypesOtherWhenJdbcTypeAbsent() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);

        new UuidTypeHandler().setParameter(statement, 2, null, null);

        verify(statement).setNull(2, Types.OTHER);
    }

    @Test
    void shouldWriteNullWithDeclaredJdbcTypeCode() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);

        new UuidTypeHandler().setParameter(statement, 3, null, JdbcType.OTHER);

        verify(statement).setNull(3, Types.OTHER);
    }

    @Test
    void shouldReadUuidFromResultSet() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject("resource_id")).thenReturn(SAMPLE);
        when(resultSet.getObject(4)).thenReturn(SAMPLE.toString());

        assertEquals(SAMPLE, new UuidTypeHandler().getNullableResult(resultSet, "resource_id"));
        assertEquals(SAMPLE, new UuidTypeHandler().getNullableResult(resultSet, 4));
    }

    @Test
    void shouldReadNullAsNull() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject("resource_group_id")).thenReturn(null);

        assertNull(new UuidTypeHandler().getNullableResult(resultSet, "resource_group_id"));
    }
}
