package com.hengce.aps.agent.testsupport;

import com.hengce.aps.agent.llm.LlmCallException;
import com.hengce.aps.agent.llm.LlmProvider;
import com.hengce.aps.agent.llm.LlmRequest;
import com.hengce.aps.agent.llm.LlmResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 测试专用假 provider。
 *
 * <p><b>仅在 src/test 作用域存在，生产代码与运行时 classpath 不包含本类</b>；
 * 它用于在无凭证环境验证编排链路、切换与降级，不代表任何真实模型调用。
 * 生产侧的「关闭态」由 {@code DisabledLlmProvider}（name=none）表达。</p>
 */
public final class FakeLlmProvider implements LlmProvider {

    private final String name;

    private final boolean available;

    private final Function<LlmRequest, String> responder;

    private final Supplier<LlmCallException> failure;

    private final AtomicInteger invocations = new AtomicInteger();

    private FakeLlmProvider(String name, boolean available, Function<LlmRequest, String> responder,
                            Supplier<LlmCallException> failure) {
        this.name = name;
        this.available = available;
        this.responder = responder;
        this.failure = failure;
    }

    /**
     * 固定文本应答。
     *
     * @param name provider 名
     * @param reply 固定输出
     * @return 假 provider
     */
    public static FakeLlmProvider replying(String name, String reply) {
        return new FakeLlmProvider(name, true, request -> reply, null);
    }

    /**
     * 按请求动态应答，用于「同一 Prompt 打不同 provider」对照。
     *
     * @param name provider 名
     * @param responder 应答函数
     * @return 假 provider
     */
    public static FakeLlmProvider answering(String name, Function<LlmRequest, String> responder) {
        return new FakeLlmProvider(name, true, responder, null);
    }

    /**
     * 配置完整但不可用（模拟缺 url/key）。
     *
     * @param name provider 名
     * @return 假 provider
     */
    public static FakeLlmProvider unavailable(String name) {
        return new FakeLlmProvider(name, false, request -> "", null);
    }

    /**
     * 调用即失败。
     *
     * @param name provider 名
     * @param timeout 是否模拟超时
     * @param message 失败说明
     * @return 假 provider
     */
    public static FakeLlmProvider failing(String name, boolean timeout, String message) {
        return new FakeLlmProvider(name, true, request -> "",
                () -> new LlmCallException(message, timeout, null));
    }

    /**
     * 配置完整但网络不可达。
     *
     * @param name provider 名
     * @return 假 provider
     */
    public static FakeLlmProvider unreachable(String name) {
        return new FakeLlmProvider(name, true, request -> "",
                () -> new LlmCallException("network error: ConnectException", false, true, null));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        invocations.incrementAndGet();
        if (failure != null) {
            throw failure.get();
        }
        return new LlmResponse(responder.apply(request), "fake-" + name, 5, 7, 2L);
    }

    /**
     * 已调用次数。
     *
     * @return 次数
     */
    public int invocations() {
        return invocations.get();
    }
}
