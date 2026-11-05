package com.hengce.aps.infrastructure.persistence;

import com.hengce.aps.domain.model.ProductionOrder;
import com.hengce.aps.domain.repository.ProductionOrderRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版订单仓储，用于脚手架阶段联通分层；后续替换为 PostgreSQL 实现。
 */
@Repository
public class InMemoryProductionOrderRepository implements ProductionOrderRepository {

    private final Map<String, ProductionOrder> orders = new ConcurrentHashMap<>();

    @Override
    public List<ProductionOrder> findAll() {
        return List.copyOf(orders.values());
    }

    @Override
    public Optional<ProductionOrder> findByOrderNo(String orderNo) {
        return Optional.ofNullable(orders.get(orderNo));
    }
}
