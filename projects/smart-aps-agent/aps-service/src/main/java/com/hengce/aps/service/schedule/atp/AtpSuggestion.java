package com.hengce.aps.service.schedule.atp;

/**
 * 可执行建议。
 *
 * @param action              SHIFT_TO_ALTERNATE_RESOURCE/ADD_OVERTIME/ADD_SHIFT/ADJUST_PROMISED_DATE/
 *                            SPLIT_BATCH/LOCK_CAPACITY/MAINTAIN_RESOURCE/EXTEND_TIMEOUT
 * @param resourceCode        目标资源编码，可为 null
 * @param operationCode       目标工序编码，可为 null
 * @param operationIndex      目标工序序号，可为 null
 * @param expectedGainMinutes 预计可提前分钟数，0 表示无量化收益
 * @param detail              可执行说明
 */
public record AtpSuggestion(
        String action,
        String resourceCode,
        String operationCode,
        Integer operationIndex,
        int expectedGainMinutes,
        String detail) {
}
