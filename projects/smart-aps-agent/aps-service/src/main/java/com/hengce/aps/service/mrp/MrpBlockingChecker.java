package com.hengce.aps.service.mrp;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MRP 阻塞判定器，供 W4（排产装配流）在求解装配阶段调用。
 *
 * <p>调用方式：</p>
 * <pre>{@code
 * MrpBlockingDecision decision = mrpBlockingChecker.evaluate(sandboxId, orderNos, blocking);
 * if (decision.blocked()) {
 *     // 按 C25 拒绝进入求解或降级为“缺料待确认”
 * }
 * }</pre>
 *
 * <p>开关 {@code aps.mrp.blocking-enabled} 默认 true；关闭后仍返回齐套分级，但
 * {@code blocked} 恒为 false（回滚点 R2 的一部分）。</p>
 */
@Service
public class MrpBlockingChecker {

    private final MrpAnalysisService analysisService;

    private final boolean blockingEnabled;

    /**
     * 构造判定器。
     *
     * @param analysisService MRP 分析服务
     * @param blockingEnabled 全局阻塞开关
     */
    public MrpBlockingChecker(MrpAnalysisService analysisService,
                              @Value("${aps.mrp.blocking-enabled:true}") boolean blockingEnabled) {
        this.analysisService = analysisService;
        this.blockingEnabled = blockingEnabled;
    }

    /**
     * 判定给定订单是否因缺料阻塞排产。
     *
     * @param sandboxId 排产场景标识
     * @param orderNos 待排产订单号；空表示全部可排产订单
     * @param blocking 调用方是否要求按缺料阻塞
     * @return 阻塞判定结果；{@code blocked=true} 表示应拒绝/拦截本次排产
     */
    public MrpBlockingDecision evaluate(UUID sandboxId, List<String> orderNos, boolean blocking) {
        MrpAnalysisResponse response = analysisService.analyze(
                new MrpAnalysisCommand(sandboxId, orderNos, false, blocking));
        List<String> shortageCodes = response.shortages().stream()
                .map(MrpAnalysisResponse.MrpShortage::materialCode)
                .distinct()
                .sorted()
                .toList();
        boolean blocked = blocking && blockingEnabled && response.status() != MrpStatus.FULL
                && response.orderCount() > 0;
        String message;
        if (blocked) {
            message = "MRP 齐套未通过（" + response.status() + "），缺料物料: "
                    + String.join(", ", shortageCodes);
        } else if (!blocking) {
            message = "调用方未启用缺料阻塞，MRP 状态: " + response.status();
        } else {
            message = "MRP 齐套检查通过，状态: " + response.status();
        }
        return new MrpBlockingDecision(blocked, response.status(), shortageCodes, message);
    }
}
