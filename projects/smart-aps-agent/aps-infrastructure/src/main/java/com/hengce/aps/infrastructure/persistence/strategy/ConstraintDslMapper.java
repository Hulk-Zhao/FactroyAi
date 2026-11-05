package com.hengce.aps.infrastructure.persistence.strategy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * 约束 DSL Mapper；tenant_id 由 BD-01 的租户拦截器统一追加。
 */
public interface ConstraintDslMapper extends BaseMapper<ConstraintDsl> {

    /**
     * 查询某标识当前激活版本。
     *
     * @param code DSL 标识
     * @return 激活版本；无激活版本时为空
     */
    @Select("SELECT * FROM constraint_dsl WHERE code = #{code} AND status = 'ACTIVE' AND deleted = false "
            + "ORDER BY version_no DESC LIMIT 1")
    ConstraintDsl selectActiveByCode(@Param("code") String code);

    /**
     * 查询某标识的最大版本号。
     *
     * @param code DSL 标识
     * @return 最大版本号；无版本时为 0
     */
    @Select("SELECT COALESCE(MAX(version_no), 0) FROM constraint_dsl WHERE code = #{code} AND deleted = false")
    int selectMaxVersionNo(@Param("code") String code);

    /**
     * 查询某标识下的全部版本，按版本号倒序。
     *
     * @param code DSL 标识
     * @return 版本列表
     */
    @Select("SELECT * FROM constraint_dsl WHERE code = #{code} AND deleted = false ORDER BY version_no DESC")
    java.util.List<ConstraintDsl> selectVersions(@Param("code") String code);

    /**
     * 统计指定激活版本以外的激活记录（用于激活前自检）。
     *
     * @param code DSL 标识
     * @param excludedId 排除的版本主键
     * @return 记录数
     */
    @Select("SELECT COUNT(*) FROM constraint_dsl WHERE code = #{code} AND status = 'ACTIVE' "
            + "AND id <> #{excludedId} AND deleted = false")
    long countOtherActive(@Param("code") String code, @Param("excludedId") UUID excludedId);
}
