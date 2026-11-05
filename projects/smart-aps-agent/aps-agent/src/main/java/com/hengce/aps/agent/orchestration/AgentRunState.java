package com.hengce.aps.agent.orchestration;

import com.hengce.aps.agent.llm.LlmDegradationReason;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一次运行的完整可变状态；序列化为 JSON 后整体落入检查点。
 *
 * <p>字段刻意保持扁平且可空，便于后续在检查点 JSON 中读取历史字段。</p>
 */
public class AgentRunState {

    private UUID runId;

    private Map<String, Object> variables = new LinkedHashMap<>();

    private String promptKey;

    private Integer promptVersion;

    private String composedPrompt;

    private String output;

    private String provider;

    private boolean degraded;

    private LlmDegradationReason degradationReason;

    private String degradationDetail;

    private long latencyMillis;

    private Integer promptTokens;

    private Integer completionTokens;

    private boolean outputValid;

    private String validationError;

    private int lastCompletedStep;

    private String lastStepName;

    /**
     * 读取运行标识。
     *
     * @return 运行标识
     */
    public UUID getRunId() {
        return runId;
    }

    /**
     * 设置运行标识。
     *
     * @param runId 运行标识
     */
    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    /**
     * 读取上下文变量。
     *
     * @return 变量表
     */
    public Map<String, Object> getVariables() {
        return variables;
    }

    /**
     * 设置上下文变量。
     *
     * @param variables 变量表
     */
    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new LinkedHashMap<>() : variables;
    }

    /**
     * 读取 Prompt 键。
     *
     * @return Prompt 键
     */
    public String getPromptKey() {
        return promptKey;
    }

    /**
     * 设置 Prompt 键。
     *
     * @param promptKey Prompt 键
     */
    public void setPromptKey(String promptKey) {
        this.promptKey = promptKey;
    }

    /**
     * 读取 Prompt 版本。
     *
     * @return 版本号，未知为 null
     */
    public Integer getPromptVersion() {
        return promptVersion;
    }

    /**
     * 设置 Prompt 版本。
     *
     * @param promptVersion 版本号
     */
    public void setPromptVersion(Integer promptVersion) {
        this.promptVersion = promptVersion;
    }

    /**
     * 读取组装后的完整提示词。
     *
     * @return 提示词
     */
    public String getComposedPrompt() {
        return composedPrompt;
    }

    /**
     * 设置组装后的完整提示词。
     *
     * @param composedPrompt 提示词
     */
    public void setComposedPrompt(String composedPrompt) {
        this.composedPrompt = composedPrompt;
    }

    /**
     * 读取模型输出。
     *
     * @return 输出文本，降级时为 null
     */
    public String getOutput() {
        return output;
    }

    /**
     * 设置模型输出。
     *
     * @param output 输出文本
     */
    public void setOutput(String output) {
        this.output = output;
    }

    /**
     * 读取实际 provider。
     *
     * @return provider 名
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 设置实际 provider。
     *
     * @param provider provider 名
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * 是否已降级。
     *
     * @return true 表示无可用模型输出
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * 设置降级标记。
     *
     * @param degraded 是否降级
     */
    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    /**
     * 读取降级原因。
     *
     * @return 降级原因，正常为 null
     */
    public LlmDegradationReason getDegradationReason() {
        return degradationReason;
    }

    /**
     * 设置降级原因。
     *
     * @param degradationReason 降级原因
     */
    public void setDegradationReason(LlmDegradationReason degradationReason) {
        this.degradationReason = degradationReason;
    }

    /**
     * 读取降级说明。
     *
     * @return 脱敏说明
     */
    public String getDegradationDetail() {
        return degradationDetail;
    }

    /**
     * 设置降级说明。
     *
     * @param degradationDetail 脱敏说明
     */
    public void setDegradationDetail(String degradationDetail) {
        this.degradationDetail = degradationDetail;
    }

    /**
     * 读取调用耗时。
     *
     * @return 毫秒
     */
    public long getLatencyMillis() {
        return latencyMillis;
    }

    /**
     * 设置调用耗时。
     *
     * @param latencyMillis 毫秒
     */
    public void setLatencyMillis(long latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    /**
     * 读取输入 token 数。
     *
     * @return token 数，未知为 null
     */
    public Integer getPromptTokens() {
        return promptTokens;
    }

    /**
     * 设置输入 token 数。
     *
     * @param promptTokens token 数
     */
    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    /**
     * 读取输出 token 数。
     *
     * @return token 数，未知为 null
     */
    public Integer getCompletionTokens() {
        return completionTokens;
    }

    /**
     * 设置输出 token 数。
     *
     * @param completionTokens token 数
     */
    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    /**
     * 结构化输出是否通过校验。
     *
     * @return true 表示合法 JSON 对象
     */
    public boolean isOutputValid() {
        return outputValid;
    }

    /**
     * 设置结构化校验结果。
     *
     * @param outputValid 是否通过
     */
    public void setOutputValid(boolean outputValid) {
        this.outputValid = outputValid;
    }

    /**
     * 读取校验失败说明。
     *
     * @return 说明，通过时为 null
     */
    public String getValidationError() {
        return validationError;
    }

    /**
     * 设置校验失败说明。
     *
     * @param validationError 说明
     */
    public void setValidationError(String validationError) {
        this.validationError = validationError;
    }

    /**
     * 读取已完成步骤号。
     *
     * @return 已完成步骤号
     */
    public int getLastCompletedStep() {
        return lastCompletedStep;
    }

    /**
     * 设置已完成步骤号。
     *
     * @param lastCompletedStep 步骤号
     */
    public void setLastCompletedStep(int lastCompletedStep) {
        this.lastCompletedStep = lastCompletedStep;
    }

    /**
     * 读取最后完成的步骤名。
     *
     * @return 步骤名
     */
    public String getLastStepName() {
        return lastStepName;
    }

    /**
     * 设置最后完成的步骤名。
     *
     * @param lastStepName 步骤名
     */
    public void setLastStepName(String lastStepName) {
        this.lastStepName = lastStepName;
    }
}
