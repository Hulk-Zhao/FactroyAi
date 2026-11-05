package com.hengce.aps.infrastructure.persistence.mapper.calendar;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hengce.aps.infrastructure.persistence.calendar.Calendar;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工作日历持久层接口。
 *
 * <p>JSONB 列的读写全部通过 XML 中的 {@code JsonbTypeHandler} 显式绑定，
 * 因此不继承 {@code BaseMapper}，避免自动 SQL 以 VARCHAR 提交 JSONB。</p>
 */
@Mapper
public interface CalendarMapper {

    /**
     * 按主键查询未删除日历。
     *
     * @param id 日历主键
     * @return 日历实体，不存在返回 null
     */
    Calendar findById(@Param("id") UUID id);

    /**
     * 分页查询日历。
     *
     * @param page 分页参数（由 MyBatis-Plus 分页拦截器处理）
     * @param name 名称模糊过滤，可空
     * @param type 类型精确过滤，可空
     * @param resourceId 资源精确过滤，可空
     * @param resourceGroupId 资源组精确过滤，可空
     * @return 当前页记录
     */
    List<Calendar> selectPage(IPage<Calendar> page,
                                    @Param("name") String name,
                                    @Param("type") String type,
                                    @Param("resourceId") UUID resourceId,
                                    @Param("resourceGroupId") UUID resourceGroupId);

    /**
     * 取同租户同名日历的当前最大业务版本号。
     *
     * @param name 日历名称
     * @return 最大版本号；不存在返回 0
     */
    int maxCalendarVersion(@Param("name") String name);

    /**
     * 统计租户内同名同业务版本的未删除日历数量。
     *
     * @param name 日历名称
     * @param calendarVersion 业务版本号
     * @param excludeId 排除的主键，可空
     * @return 数量
     */
    long countByNameAndVersion(@Param("name") String name,
                               @Param("calendarVersion") Integer calendarVersion,
                               @Param("excludeId") UUID excludeId);

    /**
     * 统计引用该日历的资源数量（最小只读引用检查）。
     *
     * @param calendarId 日历主键
     * @return 资源数量
     */
    long countResourceReferences(@Param("calendarId") UUID calendarId);

    /**
     * 统计引用该日历的资源组数量（最小只读引用检查）。
     *
     * @param calendarId 日历主键
     * @return 资源组数量
     */
    long countResourceGroupReferences(@Param("calendarId") UUID calendarId);

    /**
     * 新增日历。
     *
     * @param entity 日历实体
     * @return 影响行数
     */
    int insert(Calendar entity);

    /**
     * 按乐观锁版本更新日历。
     *
     * @param entity 日历实体（version 为更新前版本）
     * @return 影响行数；0 表示版本冲突
     */
    int update(Calendar entity);

    /**
     * 逻辑删除日历。
     *
     * @param id 日历主键
     * @param version 更新前版本号
     * @param updatedAt 更新时间（UTC）
     * @param updatedBy 操作人，可空
     * @return 影响行数；0 表示版本冲突
     */
    int logicDelete(@Param("id") UUID id,
                    @Param("version") Integer version,
                    @Param("updatedAt") OffsetDateTime updatedAt,
                    @Param("updatedBy") UUID updatedBy);
}
