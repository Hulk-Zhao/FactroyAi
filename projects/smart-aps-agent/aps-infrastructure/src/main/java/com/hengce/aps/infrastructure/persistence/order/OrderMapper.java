package com.hengce.aps.infrastructure.persistence.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 订单主表 Mapper。
 *
 * <p>租户过滤由 BD-01 的 TenantLineInnerInterceptor 统一追加，逻辑删除由 {@code @TableLogic} 处理。
 * 自定义只读 SQL 仅用于客户存在性校验；工艺路线与 BOM 校验分别复用 BD-07 / BD-03 的查询能力。</p>
 */
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 统计未删除的客户数量，供订单 customerId 引用校验。
     *
     * @param customerId 客户主键
     * @return 匹配数量
     */
    @Select("SELECT COUNT(1) FROM customer WHERE id = #{customerId} AND deleted = false")
    long countCustomer(@Param("customerId") UUID customerId);

    /**
     * 批量读取未删除客户的编码与名称（C50），用于订单读模型内嵌 {@code customerCode}/{@code customerName}。
     *
     * <p>租户条件显式绑定 {@code tenant_id}（与拦截器追加条件同值，双保险，关闭拦截器时仍不跨租户）；
     * 逻辑删除由 {@code deleted = false} 显式过滤；调用方保证集合非空，避免拼出空 IN。</p>
     *
     * @param tenantId 当前租户标识
     * @param customerIds 客户主键集合，非空
     * @return 命中的客户摘要，未命中/已软删除的客户不出现在结果中
     */
    @Select("<script>"
            + "SELECT c.id AS customer_id, c.code, c.name FROM customer c "
            + "WHERE c.tenant_id = #{tenantId} AND c.deleted = false AND c.id IN "
            + "<foreach collection='customerIds' item='customerId' open='(' separator=',' close=')'>"
            + "#{customerId}</foreach>"
            + "</script>")
    List<CustomerSummaryRow> selectCustomerSummaries(@Param("tenantId") UUID tenantId,
                                                     @Param("customerIds") Collection<UUID> customerIds);

    /**
     * 客户摘要（C50），与 {@code customer.code}/{@code customer.name} 对齐。
     *
     * @param customerId 客户主键
     * @param code 客户编码
     * @param name 客户名称
     */
    record CustomerSummaryRow(UUID customerId, String code, String name) {
    }
}
