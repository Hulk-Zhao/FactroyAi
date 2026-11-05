package com.hengce.aps.common.constant;

import java.time.Duration;

/**
 * 平台级常量。
 */
public final class ApsConstants {

    /**
     * REST API 统一前缀。
     */
    public static final String API_PREFIX = "/api/v1";

    /**
     * 默认求解引擎编码。
     */
    public static final String DEFAULT_SOLVER_ENGINE = "timefold";

    /**
     * 默认页码。
     */
    public static final int DEFAULT_PAGE = 1;

    /**
     * 默认每页条数。
     */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 每页条数上限。
     */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 幂等键请求头。
     */
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * API Key 请求头（M2 开发模式认证）。
     */
    public static final String HEADER_API_KEY = "X-API-Key";

    /**
     * 认证请求头。
     */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /**
     * 开发态操作人请求头（仅 dev 模式生效，必须为合法 UUID；prod 走 JWT subject）。
     */
    public static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 租户请求头（MVP 占位，接入认证后由网关/令牌解析）。
     */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    /**
     * DEMO 租户标识。
     */
    public static final String DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    /**
     * 幂等键保留时长。
     */
    public static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private ApsConstants() {
    }
}
