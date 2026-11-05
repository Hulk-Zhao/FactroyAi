package com.hengce.aps.service.masterdata.order;

import com.hengce.aps.domain.model.FreezeStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 订单冻结命令，对齐 C23 的四态 payload（含锁窗/锁资源）。
 *
 * @param freezeStatus 目标冻结状态
 * @param lockFrom 锁窗开始（UTC），仅 LOCKED_TIME_WINDOW 必填
 * @param lockTo 锁窗结束（UTC），仅 LOCKED_TIME_WINDOW 必填且需晚于 lockFrom
 * @param lockedResourceCodes 锁定资源编码，仅 LOCKED_RESOURCE 必填
 * @param version 当前乐观锁版本
 */
public record OrderFreezeCommand(
        FreezeStatus freezeStatus,
        OffsetDateTime lockFrom,
        OffsetDateTime lockTo,
        List<String> lockedResourceCodes,
        Integer version) {
}
