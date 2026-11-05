package com.hengce.aps.common.exception;

/**
 * 平台统一业务码。
 *
 * <p>编码规则对齐 PRD BR-INT-001-04：{@code 0} 成功，{@code 4xxxx} 客户端错误，{@code 5xxxx} 服务端错误。
 * 每个业务码的 HTTP 语义由 Web 层统一映射，公共模块不依赖 Spring。</p>
 */
public enum ErrorCode {

    /** 成功。 */
    SUCCESS(0, "success"),

    /** 请求参数不合法（400）。 */
    BAD_REQUEST(40000, "请求参数不合法"),

    /** 未认证或凭证失效（401）。 */
    UNAUTHORIZED(40100, "未认证或凭证已失效"),

    /** 权限不足（403）。 */
    FORBIDDEN(40300, "无权访问该资源"),

    /** 资源不存在（404）。 */
    NOT_FOUND(40400, "资源不存在"),

    /** 请求方法不支持（405）。 */
    METHOD_NOT_ALLOWED(40500, "不支持的请求方法"),

    /** 幂等键冲突，相同请求正在处理（409）。 */
    IDEMPOTENCY_CONFLICT(40900, "幂等键冲突：相同请求正在处理中"),

    /** 资源状态冲突，如乐观锁版本不一致（409）。 */
    STATE_CONFLICT(40901, "资源状态冲突，请刷新后重试"),

    /** 已发布的计划/任务不可回滚或覆盖（409）。 */
    PUBLISHED_CONFLICT(40903, "已发布的计划/任务不可回滚或覆盖"),

    /** 不支持的媒体类型（415）。 */
    UNSUPPORTED_MEDIA_TYPE(41500, "不支持的 Content-Type"),

    /** 业务规则校验未通过（422）。 */
    UNPROCESSABLE_ENTITY(42200, "业务规则校验未通过"),

    /** 甘特调整不可行（422）。 */
    ADJUSTMENT_INFEASIBLE(42201, "调整不可行"),

    /** 请求频率超限（429）。 */
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后重试"),

    /** 系统内部错误（500）。 */
    INTERNAL_ERROR(50000, "系统内部错误"),

    /** 求解引擎不可用（500）。 */
    SOLVER_NOT_AVAILABLE(50010, "求解引擎不可用"),

    /** 功能尚未实现（501），用于契约占位端点。 */
    NOT_IMPLEMENTED(50100, "接口尚未实现"),

    /** 依赖服务暂不可用（503）。 */
    SERVICE_UNAVAILABLE(50300, "依赖服务暂不可用");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 业务码数值。
     *
     * @return 业务码
     */
    public int getCode() {
        return code;
    }

    /**
     * 默认提示信息。
     *
     * @return 提示信息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 是否为成功码。
     *
     * @return {@code true} 表示成功
     */
    public boolean isSuccess() {
        return code == SUCCESS.code;
    }

    /**
     * 是否为客户端错误段（4xxxx）。
     *
     * @return {@code true} 表示客户端错误
     */
    public boolean isClientError() {
        return code >= 40000 && code < 50000;
    }

    /**
     * 是否为服务端错误段（5xxxx）。
     *
     * @return {@code true} 表示服务端错误
     */
    public boolean isServerError() {
        return code >= 50000 && code < 60000;
    }
}
