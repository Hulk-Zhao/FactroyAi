package com.hengce.aps.web.security;

/**
 * 认证方式（C30）。
 */
public enum AuthMechanism {

    /** JWT Bearer。 */
    JWT,
    /** dev 模式 X-API-Key。 */
    API_KEY
}
