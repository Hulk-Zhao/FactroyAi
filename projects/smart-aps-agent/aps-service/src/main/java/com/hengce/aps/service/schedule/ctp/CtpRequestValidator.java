package com.hengce.aps.service.schedule.ctp;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.solver.api.ctp.CtpMaterialPolicy;
import com.hengce.aps.solver.api.ctp.CtpRequest;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CTP 询单入参校验（C53 §25.28）：
 * <ul>
 *   <li>缺少字段 / 业务非法 → 422/42200；</li>
 *   <li>{@code materialCode} 长度、{@code 0 < qty <= 1e9}、{@code timeoutSeconds 1..15} 逐项校验；</li>
 *   <li>{@code materialPolicy} 与 {@code allowSubstitute} 任一为替代即生效 {@code SUBSTITUTE}，
 *       否则 {@code STRICT}，规则确定、无隐式覆盖。</li>
 * </ul>
 *
 * <p>不依赖 Spring，可直接单元测试。</p>
 */
public final class CtpRequestValidator {

    /** 数量上限。 */
    public static final BigDecimal MAX_QTY = BigDecimal.valueOf(1_000_000_000L);

    /** 限时秒数下限。 */
    public static final int MIN_TIMEOUT_SECONDS = 1;

    /** 限时秒数上限：C53 同步上限 15s。 */
    public static final int MAX_TIMEOUT_SECONDS = 15;

    /** 编码类字段最大长度。 */
    private static final int MAX_CODE_LENGTH = 64;

    private CtpRequestValidator() {
    }

    /**
     * 校验并归一化入参。
     *
     * @param request                原始入参，可为 null
     * @param defaultTimeoutSeconds  缺省限时秒数
     * @return 归一化结果
     * @throws BizException 非法参数（422/42200）
     */
    public static NormalizedCtpRequest validate(CtpRequest request, int defaultTimeoutSeconds) {
        if (request == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "请求体不能为空");
        }
        String materialCode = trimToNull(request.materialCode());
        if (materialCode == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "materialCode 不能为空");
        }
        if (materialCode.length() > MAX_CODE_LENGTH) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "materialCode 长度不能超过 " + MAX_CODE_LENGTH);
        }
        BigDecimal qty = request.qty();
        if (qty == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "qty 不能为空");
        }
        if (qty.signum() <= 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "qty 必须大于 0");
        }
        if (qty.compareTo(MAX_QTY) > 0) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "qty 不能超过 " + MAX_QTY.toPlainString());
        }
        LocalDate requestedDate = request.requestedDate();
        if (requestedDate == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "requestedDate 不能为空");
        }
        String customerCode = trimToNull(request.customerCode());
        if (customerCode != null && customerCode.length() > MAX_CODE_LENGTH) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "customerCode 长度不能超过 " + MAX_CODE_LENGTH);
        }
        int timeoutSeconds = defaultTimeoutSeconds < MIN_TIMEOUT_SECONDS
                ? MIN_TIMEOUT_SECONDS : defaultTimeoutSeconds;
        if (request.timeoutSeconds() != null) {
            timeoutSeconds = request.timeoutSeconds();
        }
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "timeoutSeconds 必须在 " + MIN_TIMEOUT_SECONDS + "-" + MAX_TIMEOUT_SECONDS + " 秒之间");
        }
        boolean allowSubstitute = Boolean.TRUE.equals(request.allowSubstitute())
                || request.materialPolicy() == CtpMaterialPolicy.SUBSTITUTE;
        return new NormalizedCtpRequest(materialCode, qty, requestedDate, customerCode,
                allowSubstitute, timeoutSeconds);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 归一化后的询单参数。
     *
     * @param materialCode    物料编码
     * @param qty             需求数量
     * @param requestedDate   请求交期
     * @param customerCode    客户编码；仅审计/展示
     * @param allowSubstitute 生效的替代策略（已合并 materialPolicy）
     * @param timeoutSeconds  限时秒数
     */
    public record NormalizedCtpRequest(
            String materialCode,
            BigDecimal qty,
            LocalDate requestedDate,
            String customerCode,
            boolean allowSubstitute,
            int timeoutSeconds) {
    }
}
