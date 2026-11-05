package com.hengce.aps.service.masterdata.order;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 订单冻结锁定信息，对应 contract-freeze C23 的「锁窗/锁资源」。
 *
 * <p>{@link com.hengce.aps.domain.model.FreezeStatus#LOCKED_TIME_WINDOW} 使用 {@code from}/{@code to}；
 * {@link com.hengce.aps.domain.model.FreezeStatus#LOCKED_RESOURCE} 使用 {@code resourceCodes}。
 * 该信息落在 {@code orders.ext_attrs.freezeLock}，不改动 V1 既有列与语义。</p>
 *
 * @param from 锁定时间窗开始（UTC），锁资源态可为空
 * @param to 锁定时间窗结束（UTC），锁资源态可为空
 * @param resourceCodes 锁定资源编码，锁窗态可为空
 */
public record FreezeLock(OffsetDateTime from, OffsetDateTime to, List<String> resourceCodes) {

    /**
     * 规范化资源编码：去空白、去重、保持顺序。
     *
     * @return 规范化后的资源编码
     */
    public List<String> normalizedResourceCodes() {
        if (resourceCodes == null) {
            return List.of();
        }
        return resourceCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
