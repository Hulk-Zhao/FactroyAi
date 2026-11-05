package com.hengce.aps.infrastructure.persistence.mapper.process.routing;

import com.hengce.aps.infrastructure.persistence.process.routing.RoutingEdge;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 工艺路线依赖边 Mapper。
 *
 * <p>图整体替换时先逻辑删除旧边再插入新边，避免边 UUID 复用带来的快照不一致。</p>
 */
public interface RoutingEdgeMapper {

    /**
     * 查询路线下未删除的边。
     *
     * @param routingId 路线主键
     * @return 边列表
     */
    List<RoutingEdge> selectByRoutingId(@Param("routingId") UUID routingId);

    /**
     * 新增边。
     *
     * @param entity 待写入边
     * @return 影响行数
     */
    int insert(RoutingEdge entity);

    /**
     * 逻辑删除路线下全部边。
     *
     * @param routingId 路线主键
     * @return 影响行数
     */
    int deleteByRoutingId(@Param("routingId") UUID routingId);
}
