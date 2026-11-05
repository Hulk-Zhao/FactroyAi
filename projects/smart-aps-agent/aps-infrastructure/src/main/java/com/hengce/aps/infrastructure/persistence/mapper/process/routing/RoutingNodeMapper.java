package com.hengce.aps.infrastructure.persistence.mapper.process.routing;

import com.hengce.aps.infrastructure.persistence.process.routing.RoutingNode;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 工艺路线节点 Mapper。
 *
 * <p>{@code overrides} 为 JSONB，由 {@code JsonbTypeHandler} 读写；节点按 seq_no 升序返回。</p>
 */
public interface RoutingNodeMapper {

    /**
     * 查询路线下未删除的节点，按 seq_no、node_key 升序。
     *
     * @param routingId 路线主键
     * @return 节点列表
     */
    List<RoutingNode> selectByRoutingId(@Param("routingId") UUID routingId);

    /**
     * 新增节点。
     *
     * @param entity 待写入节点
     * @return 影响行数
     */
    int insert(RoutingNode entity);

    /**
     * 更新节点（保留主键，用于同一 node_key 跨保存的稳定标识）。
     *
     * @param entity 待更新节点，id 必填
     * @return 影响行数
     */
    int update(RoutingNode entity);

    /**
     * 逻辑删除单个节点。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteById(@Param("id") UUID id);

    /**
     * 逻辑删除路线下全部节点。
     *
     * @param routingId 路线主键
     * @return 影响行数
     */
    int deleteByRoutingId(@Param("routingId") UUID routingId);
}
