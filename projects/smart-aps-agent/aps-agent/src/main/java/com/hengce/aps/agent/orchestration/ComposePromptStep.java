package com.hengce.aps.agent.orchestration;

import com.hengce.aps.agent.prompt.PromptStore;
import com.hengce.aps.agent.prompt.PromptVersion;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 步骤 2：取当前生效 Prompt 版本并做 {@code {{var}}} 占位替换。
 *
 * <p>若该键尚无任何版本，则以配置的默认模板惰性写入版本 1 并激活（幂等），
 * 之后所有变更都通过版本存储完成，不再依赖代码常量。</p>
 */
public final class ComposePromptStep implements AgentStep {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private final PromptStore promptStore;

    private final String defaultContent;

    /**
     * 构造步骤。
     *
     * @param promptStore Prompt 版本存储
     * @param defaultContent 首次初始化使用的模板
     */
    public ComposePromptStep(PromptStore promptStore, String defaultContent) {
        this.promptStore = promptStore;
        this.defaultContent = defaultContent;
    }

    @Override
    public int number() {
        return 2;
    }

    @Override
    public String name() {
        return "compose-prompt";
    }

    @Override
    public void execute(AgentRunState state) {
        PromptVersion active = promptStore.ensureActive(state.getPromptKey(), defaultContent, "auto-seed");
        state.setPromptVersion(active.version());
        state.setComposedPrompt(render(active.content(), state.getVariables()));
    }

    private static String render(String template, Map<String, Object> variables) {
        if (template == null) {
            return "";
        }
        if (variables == null || variables.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.containsKey(key) ? String.valueOf(variables.get(key)) : matcher.group(0);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }
}
