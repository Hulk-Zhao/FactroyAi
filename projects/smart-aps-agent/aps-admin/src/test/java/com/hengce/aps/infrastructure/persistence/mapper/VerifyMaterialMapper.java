package com.hengce.aps.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengce.aps.infrastructure.persistence.handler.JsonbTypeHandler;
import com.hengce.aps.verify.entity.VerifyMaterialEntity;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * 仅本地真库回归使用（test scope）：复用生产 MapperScan 根包，验证真实拦截器链。
 */
@Mapper
public interface VerifyMaterialMapper extends BaseMapper<VerifyMaterialEntity> {

    /**
     * 显式绑定 JSONB TypeHandler 读取 ext_attrs（避免依赖 autoResultMap 触发 id 类型映射校验）。
     */
    @Select("SELECT ext_attrs FROM material WHERE id = #{id}")
    @Results(value = {
            @Result(column = "ext_attrs", property = "extAttrs", typeHandler = JsonbTypeHandler.class)
    })
    VerifyMaterialEntity selectExtAttrs(@Param("id") UUID id);
}
