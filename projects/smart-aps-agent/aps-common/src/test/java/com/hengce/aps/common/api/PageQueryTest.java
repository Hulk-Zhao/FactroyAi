package com.hengce.aps.common.api;

import com.hengce.aps.common.constant.ApsConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分页入参规约测试。
 */
class PageQueryTest {

    @Test
    void shouldUseDefaults() {
        PageQuery query = new PageQuery();

        assertEquals(ApsConstants.DEFAULT_PAGE, query.getPage());
        assertEquals(ApsConstants.DEFAULT_PAGE_SIZE, query.getSize());
        assertEquals(0, query.offset());
    }

    @Test
    void shouldNormalizeIllegalValues() {
        PageQuery query = new PageQuery();
        query.setPage(0);
        query.setSize(999);

        query.normalize();

        assertEquals(1, query.getPage());
        assertEquals(ApsConstants.MAX_PAGE_SIZE, query.getSize());
    }

    @Test
    void shouldTreatNonPositiveSizeAsDefault() {
        PageQuery query = new PageQuery();
        query.setSize(0);

        query.normalize();

        assertEquals(ApsConstants.DEFAULT_PAGE_SIZE, query.getSize());
    }

    @Test
    void shouldCalculateOffset() {
        PageQuery query = new PageQuery();
        query.setPage(3);
        query.setSize(20);

        assertEquals(40, query.offset());
    }
}
