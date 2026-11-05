package com.hengce.aps.infrastructure.persistence.mapper.process.template;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengce.aps.infrastructure.persistence.process.OperationTemplate;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 工序模版 Mapper。
 *
 * <p>放在 {@code persistence.mapper} 根包下以命中 MybatisPlusConfiguration 的扫描范围。
 * 语句实现见 {@code resources/mapper/process/template/OperationTemplateMapper.xml}；
 * JSONB 数组列由 {@code UuidListJsonbTypeHandler} 负责读写。</p>
 *
 * <p>更新语句采用「单实体入参 + 显式 WHERE version」实现乐观锁，不依赖 MyBatis-Plus
 * 乐观锁插件对自定义 XML 的改写能力。</p>
 */
public interface OperationTemplateMapper {

    /**
     * 新增工序模版。
     *
     * @param entity 待写入实体，id/tenantId/审计字段由调用方填充
     * @return 影响行数
     */
    int insert(OperationTemplate entity);

    /**
     * 按乐观锁版本更新工序模版。
     *
     * @param entity 待更新实体，id 与 version 为当前版本
     * @return 影响行数，0 表示版本冲突或记录不存在
     */
    int update(OperationTemplate entity);

    /**
     * 按主键查询未删除的工序模版。
     *
     * @param id 主键
     * @return 工序模版，不存在时返回 null
     */
    OperationTemplate selectById(@Param("id") UUID id);

    /**
     * 逻辑删除工序模版。
     *
     * @param id 主键
     * @return 影响行数
     */
    int deleteById(@Param("id") UUID id);

    /**
     * 统计同编码同业务版本的模版数量，用于唯一性校验。
     *
     * @param code 模版编码
     * @param templateVersion 业务版本号
     * @param excludeId 需要排除的主键，新增时传 null
     * @return 匹配数量
     */
    long countByCodeVersion(@Param("code") String code,
                            @Param("templateVersion") Integer templateVersion,
                            @Param("excludeId") UUID excludeId);

    /**
     * 按编码/名称/主资源类型过滤并分页。
     *
     * @param page 分页参数
     * @param code 编码模糊条件，可空
     * @param name 名称模糊条件，可空
     * @param resourceType 主资源类型，可空
     * @param sortColumn 排序字段（数据库列名，由服务层白名单校验）
     * @param sortDirection 排序方向 ASC/DESC
     * @return 分页结果
     */
    IPage<OperationTemplate> selectPageByFilter(Page<OperationTemplate> page,
                                                @Param("code") String code,
                                                @Param("name") String name,
                                                @Param("resourceType") String resourceType,
                                                @Param("sortColumn") String sortColumn,
                                                @Param("sortDirection") String sortDirection);
}
