package com.hengce.aps.service.order;

import com.hengce.aps.common.exception.BusinessException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.domain.model.ProductionOrder;
import com.hengce.aps.domain.repository.ProductionOrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 生产订单查询服务。
 */
@Service
public class ProductionOrderService {

    private final ProductionOrderRepository productionOrderRepository;

    public ProductionOrderService(ProductionOrderRepository productionOrderRepository) {
        this.productionOrderRepository = productionOrderRepository;
    }

    /**
     * 查询全部订单。
     *
     * @return 订单列表
     */
    public List<ProductionOrder> listOrders() {
        return productionOrderRepository.findAll();
    }

    /**
     * 按订单号查询。
     *
     * @param orderNo 订单号
     * @return 订单
     */
    public ProductionOrder getOrder(String orderNo) {
        return productionOrderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在: " + orderNo));
    }
}
