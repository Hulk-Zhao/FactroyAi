package com.hengce.aps.infrastructure.persistence.process.routing;

import java.util.UUID;

/**
 * 资源编码只读投影，用于工艺路线节点响应回显 primaryResourceCode。
 */
public class ResourceCodeRow {

    /** 资源主键。 */
    private UUID id;

    /** 资源业务编码。 */
    private String code;

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
}
