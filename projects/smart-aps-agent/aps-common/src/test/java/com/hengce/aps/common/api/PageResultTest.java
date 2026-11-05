package com.hengce.aps.common.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分页出参规约测试。
 */
class PageResultTest {

    @Test
    void shouldCalculateTotalPages() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), new PageQuery(), 45);

        assertEquals(2, result.items().size());
        assertEquals(1, result.page());
        assertEquals(20, result.size());
        assertEquals(45, result.total());
        assertEquals(3, result.totalPages());
    }

    @Test
    void emptyTotalShouldHaveZeroPages() {
        PageResult<String> result = PageResult.of(List.of(), new PageQuery(), 0);

        assertEquals(0, result.totalPages());
    }

    @Test
    void shouldDefendAgainstNullItems() {
        PageResult<String> result = PageResult.of(null, new PageQuery(), 0);

        assertTrue(result.items().isEmpty());
    }

    @Test
    void shouldNormalizeQueryBeforeBuilding() {
        PageQuery query = new PageQuery();
        query.setPage(0);
        query.setSize(1000);

        PageResult<String> result = PageResult.of(List.of(), query, 0);

        assertEquals(1, result.page());
        assertEquals(200, result.size());
    }
}
