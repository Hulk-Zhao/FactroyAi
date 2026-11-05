package com.hengce.aps.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限声明（C30）：{@code value} 为 V1 的 18 个权限码之一；留空表示仅需认证。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /**
     * 权限码；空字符串表示只要求已认证。
     *
     * @return 权限码
     */
    String value() default "";
}
