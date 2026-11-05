package com.hengce.aps.infrastructure.persistence.resource;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 资源 Mapper。
 *
 * <p>引用计数查询为只读最小 SQL，供停用前校验使用；不依赖日历/任务流尚未落地的 Mapper。
 * tenant_id 由 BD-01 的租户拦截器统一追加。</p>
 */
public interface ResourceMapper extends BaseMapper<Resource> {

    /**
     * 统计引用该资源的日历数量。
     *
     * @param resourceId 资源主键
     * @return 引用数
     */
    @Select("SELECT COUNT(*) FROM calendar WHERE resource_id = #{resourceId} AND deleted = false")
    long countCalendarReferences(@Param("resourceId") UUID resourceId);

    /**
     * 统计引用该资源的排产任务数量。
     *
     * @param resourceId 资源主键
     * @return 引用数
     */
    @Select("SELECT COUNT(*) FROM task WHERE resource_id = #{resourceId} AND deleted = false")
    long countTaskReferences(@Param("resourceId") UUID resourceId);

    /**
     * 统计同编码且已软删除的资源数量，用于停用接口的幂等判断。
     *
     * @param code 资源编码
     * @return 已停用记录数
     */
    @Select("SELECT COUNT(*) FROM resource WHERE code = #{code} AND deleted = true")
    long countDeletedByCode(@Param("code") String code);
}
