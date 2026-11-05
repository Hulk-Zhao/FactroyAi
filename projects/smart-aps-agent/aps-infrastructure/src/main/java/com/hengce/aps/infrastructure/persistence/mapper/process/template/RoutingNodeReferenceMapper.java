package com.hengce.aps.infrastructure.persistence.mapper.process.template;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工序模版引用检查（只读）。
 *
 * <p>工艺路线流（BD-07）尚未落地，这里只做最小的 routing_node 只读查询，避免依赖
 * 其他业务流未提交的 Mapper。租户条件由 MyBatis-Plus TenantLineInnerInterceptor 自动追加。</p>
 */
public interface RoutingNodeReferenceMapper {

    /**
     * 统计引用指定工序模版的未删除路线节点数量。
     *
     * @param templateId 工序模版主键
     * @return 引用数量
     */
    @Select("SELECT COUNT(1) FROM routing_node "
            + "WHERE operation_template_id = #{templateId} AND deleted = false")
    long countActiveReferences(@Param("templateId") UUID templateId);
}
