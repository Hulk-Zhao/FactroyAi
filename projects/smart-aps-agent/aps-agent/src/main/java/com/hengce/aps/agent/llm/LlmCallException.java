package com.hengce.aps.agent.llm;

/**
 * provider 调用失败的受检语义；网关据此映射降级原因。
 *
 * <p>消息中不得包含密钥或完整 URL（由 provider 实现保证），
 * 以便安全地写入日志与降级原因。</p>
 */
public class LlmCallException extends RuntimeException {

    private final boolean timeout;

    private final boolean unavailable;

    /**
     * 构造非超时失败。
     *
     * @param message 脱敏后的失败描述
     */
    public LlmCallException(String message) {
        this(message, false, false, null);
    }

    /**
     * 构造失败。
     *
     * @param message 脱敏后的失败描述
     * @param timeout 是否为超时
     * @param cause 底层原因
     */
    public LlmCallException(String message, boolean timeout, Throwable cause) {
        this(message, timeout, false, cause);
    }

    /**
     * 构造失败。
     *
     * @param message 脱敏后的失败描述
     * @param timeout 是否为超时
     * @param unavailable 是否网络/连接不可达
     * @param cause 底层原因，可为 null
     */
    public LlmCallException(String message, boolean timeout, boolean unavailable, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
        this.unavailable = unavailable;
    }

    /**
     * 是否为超时。
     *
     * @return true 表示超时
     */
    public boolean isTimeout() {
        return timeout;
    }

    /**
     * 是否网络/连接不可达。
     *
     * @return true 表示未能连上上游
     */
    public boolean isUnavailable() {
        return unavailable;
    }
}
