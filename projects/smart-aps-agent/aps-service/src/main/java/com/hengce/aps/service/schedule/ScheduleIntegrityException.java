package com.hengce.aps.service.schedule;

/**
 * 落库前后的数据完整性守卫失败（例如整表重叠校验拦下计划）。
 *
 * <p>这类失败不是引擎异常：求解引擎正常返回，是落库前的最后一道闸门拒绝了结果。
 * 因此调用方必须映射为 {@code status=FAILED} + {@code errorCode=FAILED}，保留明细，
 * 且不得写 {@code engineFallback*} 降级标记，以免在监控里与引擎异常混为一谈。</p>
 */
public class ScheduleIntegrityException extends RuntimeException {

    public ScheduleIntegrityException(String message) {
        super(message);
    }
}
