package com.hengce.aps.infrastructure.persistence.mapper.process.routing;

import com.hengce.aps.infrastructure.persistence.process.routing.ResourceCodeRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 工艺路线只读引用查询 Mapper。
 *
 * <p>仅做存在性/引用计数与编码回显，不依赖其他业务流尚未落地的 Mapper；
 * tenant_id 由 BD-01 的租户拦截器统一追加。</p>
 */
public interface RoutingReferenceMapper {

    /**
     * 查询给定资源中仍然存在（未删除）的主键。
     *
     * @param ids 待校验资源主键集合
     * @return 存在的资源主键
     */
    List<UUID> selectExistingResourceIds(@Param("ids") Collection<UUID> ids);

    /**
     * 查询给定工序模版中仍然存在（未删除）的主键。
     *
     * @param ids 待校验模版主键集合
     * @return 存在的模版主键
     */
    List<UUID> selectExistingTemplateIds(@Param("ids") Collection<UUID> ids);

    /**
     * 查询资源编码，用于节点响应回显 primaryResourceCode。
     *
     * @param ids 资源主键集合
     * @return 资源编码投影
     */
    List<ResourceCodeRow> selectResourceCodes(@Param("ids") Collection<UUID> ids);

    /**
     * 按物料编码查询产品主键。
     *
     * @param code 物料编码
     * @return 物料主键，不存在时返回 null
     */
    UUID selectProductIdByCode(@Param("code") String code);

    /**
     * 统计引用该路线的订单数量。
     *
     * @param routingId 路线主键
     * @return 引用数量
     */
    long countOrderReferences(@Param("routingId") UUID routingId);

    /**
     * 统计引用该路线的排产任务数量（经 orders.routing_id 关联）。
     *
     * @param routingId 路线主键
     * @return 引用数量
     */
    long countTaskReferences(@Param("routingId") UUID routingId);
}
