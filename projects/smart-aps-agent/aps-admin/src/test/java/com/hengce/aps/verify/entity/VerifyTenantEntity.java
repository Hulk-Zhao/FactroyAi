package com.hengce.aps.verify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.UUID;

/**
 * tenant 表不含 tenant_id 列，用于验证租户拦截器的忽略表行为。
 */
@TableName("tenant")
public class VerifyTenantEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String code;

    private String name;

    private String status;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
