package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.infrastructure.persistence.mapper.process.routing.RoutingMapper;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * B-8 回归：排序参数为空时 Mapper 必须回落到默认排序，不能拼出非法 SQL。
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class RoutingSortDefaultRealDbTest extends AbstractRealDbIntegrationTest {

    @Autowired
    private RoutingMapper routingMapper;

    @Test
    void selectPageWithNullSortShouldFallBackToDefaultOrdering() {
        Page<Routing> page = new Page<>(1, 5);

        IPage<Routing> result = assertDoesNotThrow(
                () -> routingMapper.selectPageByFilter(page, null, null, null, null, null));

        evidence("RoutingMapper.selectPageByFilter(null sort) -> OK, total=" + result.getTotal());
    }
}
