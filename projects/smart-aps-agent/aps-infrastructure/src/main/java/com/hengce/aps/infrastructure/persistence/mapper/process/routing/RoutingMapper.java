package com.hengce.aps.infrastructure.persistence.mapper.process.routing;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.infrastructure.persistence.process.routing.Routing;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 工艺路线主表 Mapper。
 *
 * <p>语句实现见 {@code resources/mapper/process/routing/RoutingMapper.xml}；节点/边快照
 * 存 JSONB，由 {@code JsonbTypeHandler} 读写。更新语句显式携带 {@code version} 条件实现
 * 乐观锁，冲突时返回 0 行。</p>
 */
public interface RoutingMapper {

    /**
     * 新增工艺路线。
     *
     * @param entity 待写入实体，id/tenantId/审计字段由调用方填充
     * @return 影响行数
     */
    int insert(Routing entity);

    /**
     * 按主键查询未删除的工艺路线，并回填 productCode。
     *
     * @param id 主键
     * @return 工艺路线，不存在时返回 null
     */
    Routing selectById(@Param("id") UUID id);

    /**
     * 按产品编码/业务版本/状态过滤并分页，条件之间为 AND。
     *
     * @param page 分页参数
     * @param productCode 产品物料编码，精确匹配，可空
     * @param version 业务版本号 routing_version，可空
     * @param status 状态 DRAFT/ACTIVE/INACTIVE，可空
     * @param sortColumn 排序列（数据库列名，由服务层白名单校验）
     * @param sortDirection 排序方向 ASC/DESC
     * @return 分页结果
     */
    IPage<Routing> selectPageByFilter(Page<Routing> page,
                                      @Param("productCode") String productCode,
                                      @Param("version") String version,
                                      @Param("status") String status,
                                      @Param("sortColumn") String sortColumn,
                                      @Param("sortDirection") String sortDirection);

    /**
     * 乐观锁更新图快照。
     *
     * @param entity id 与 version 为当前版本，nodes/edges 为新快照
     * @return 影响行数，0 表示版本冲突或记录不存在
     */
    int updateGraph(Routing entity);

    /**
     * 更新状态并递增乐观锁版本。
     *
     * @param id 主键
     * @param status 目标状态
     * @return 影响行数
     */
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    /**
     * 将同产品其他 ACTIVE 路线收敛为 INACTIVE。
     *
     * @param productId 产品主键
     * @param excludeId 需要保留的路线主键
     * @return 影响行数
     */
    int deactivateOthers(@Param("productId") UUID productId, @Param("excludeId") UUID excludeId);

    /**
     * 逻辑删除工艺路线。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteById(@Param("id") UUID id);

    /**
     * 统计同产品同业务版本的路线数量，用于唯一性校验。
     *
     * @param productId 产品主键
     * @param routingVersion 业务版本号
     * @param excludeId 需排除的主键，新增时传 null
     * @return 匹配数量
     */
    long countByProductVersion(@Param("productId") UUID productId,
                               @Param("routingVersion") String routingVersion,
                               @Param("excludeId") UUID excludeId);

    /**
     * 统计同产品其他 ACTIVE 路线数量。
     *
     * @param productId 产品主键
     * @param excludeId 需排除的主键
     * @return 匹配数量
     */
    long countActiveByProduct(@Param("productId") UUID productId, @Param("excludeId") UUID excludeId);
}
