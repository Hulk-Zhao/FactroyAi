package com.hengce.aps.service.schedule.atp;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * ATP 入参服务端校验：所有非法参数统一转换为 422/42200。
 *
 * <p>不依赖 Spring，可直接单元测试。</p>
 */
public final class AtpRequestValidator {

    /** 数量上限，防止构造启发式被极端值拖慢。 */
    public static final BigDecimal MAX_QTY = BigDecimal.valueOf(1_000_000_000L);

    /** ACCURATE 限时允许的最小秒数。 */
    public static final int MIN_TIMEOUT_SECONDS = 1;

    /** ACCURATE 限时允许的最大秒数。 */
    public static final int MAX_TIMEOUT_SECONDS = 30;

    /** 编码类字段最大长度，与主数据表一致。 */
    private static final int MAX_CODE_LENGTH = 64;

    private AtpRequestValidator() {
    }

    /**
     * 校验并归一化入参。
     *
     * @param query                  原始入参，可为 null
     * @param defaultTimeoutSeconds  ACCURATE 默认限时秒数
     * @return 归一化结果
     * @throws BizException 非法参数（422/42200）
     */
    public static NormalizedAtpQuery validate(AtpQuery query, int defaultTimeoutSeconds) {
        if (query == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "请求体不能为空");
        }
        String materialCode = trimToNull(query.materialCode());
        if (materialCode == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "materialCode 不能为空");
        }
        if (materialCode.length() > MAX_CODE_LENGTH) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "materialCode 长度不能超过 " + MAX_CODE_LENGTH);
        }
        BigDecimal qty = query.qty();
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
        LocalDate requestedDate = query.requestedDate();
        if (requestedDate == null) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, "requestedDate 不能为空");
        }
        AtpMode mode;
        try {
            mode = AtpMode.fromCode(query.mode());
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, ex.getMessage());
        }
        int timeoutSeconds = defaultTimeoutSeconds < MIN_TIMEOUT_SECONDS
                ? MIN_TIMEOUT_SECONDS : defaultTimeoutSeconds;
        if (query.timeoutSeconds() != null) {
            timeoutSeconds = query.timeoutSeconds();
        }
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "timeoutSeconds 必须在 " + MIN_TIMEOUT_SECONDS + "-" + MAX_TIMEOUT_SECONDS + " 秒之间");
        }
        String orderNo = trimToNull(query.orderNo());
        if (orderNo != null && orderNo.length() > MAX_CODE_LENGTH) {
            throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "orderNo 长度不能超过 " + MAX_CODE_LENGTH);
        }
        boolean allowSplitBatch = Boolean.TRUE.equals(query.allowSplitBatch());
        return new NormalizedAtpQuery(materialCode, qty, requestedDate, query.customerId(),
                orderNo, mode, timeoutSeconds, allowSplitBatch);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 归一化后的入参。
     *
     * @param materialCode    物料编码
     * @param qty             数量
     * @param requestedDate   请求交期
     * @param customerId      客户主键
     * @param orderNo         已有订单号
     * @param mode            档位
     * @param timeoutSeconds  限时秒数
     * @param allowSplitBatch 是否允许拆批建议
     */
    public record NormalizedAtpQuery(
            String materialCode,
            BigDecimal qty,
            LocalDate requestedDate,
            UUID customerId,
            String orderNo,
            AtpMode mode,
            int timeoutSeconds,
            boolean allowSplitBatch) {
    }
}
