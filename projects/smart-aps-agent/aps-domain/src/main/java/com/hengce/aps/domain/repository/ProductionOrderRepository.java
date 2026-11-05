package com.hengce.aps.domain.repository;

import com.hengce.aps.domain.model.ProductionOrder;

import java.util.List;
import java.util.Optional;

/**
 * 生产订单仓储抽象，实现由基础设施层提供。
 */
public interface ProductionOrderRepository {

    /**
     * 查询全部订单。
     *
     * @return 订单列表
     */
    List<ProductionOrder> findAll();

    /**
     * 按订单号查询。
     *
     * @param orderNo 订单号
     * @return 订单，可能为空
     */
    Optional<ProductionOrder> findByOrderNo(String orderNo);
}
