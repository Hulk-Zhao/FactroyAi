package com.hengce.aps.agent.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * provider 注册表：按名称解析实现，未知名称返回空而不抛异常。
 *
 * <p>启动时只打印「已注册名称 + 是否配置完整」，不打印任何凭证。</p>
 */
public final class LlmProviderFactory {

    private static final Logger LOG = LoggerFactory.getLogger(LlmProviderFactory.class);

    private final Map<String, LlmProvider> providers;

    /**
     * 以容器注入的全部 provider 建立注册表；重名以先注册者为准并告警。
     *
     * @param providers provider 列表，可为空
     */
    public LlmProviderFactory(List<LlmProvider> providers) {
        Map<String, LlmProvider> registry = new LinkedHashMap<>();
        if (providers != null) {
            for (LlmProvider provider : providers) {
                String key = normalize(provider.name());
                if (registry.putIfAbsent(key, provider) != null) {
                    LOG.warn("event=agent.llm.provider.duplicate name={}", key);
                }
            }
        }
        this.providers = Map.copyOf(registry);
        this.providers.forEach((name, provider) ->
                LOG.info("event=agent.llm.provider.registered name={} configured={}", name, provider.available()));
    }

    /**
     * 按名称查找 provider。
     *
     * @param name provider 名
     * @return 找到时返回实现
     */
    public Optional<LlmProvider> find(String name) {
        return Optional.ofNullable(providers.get(normalize(name)));
    }

    /**
     * 已注册名称集合，用于未知名称的降级说明。
     *
     * @return 名称集合
     */
    public Set<String> names() {
        return providers.keySet();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
