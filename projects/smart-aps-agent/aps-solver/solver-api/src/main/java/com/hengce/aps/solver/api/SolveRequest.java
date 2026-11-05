package com.hengce.aps.solver.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP 过渡求解请求信封（contract-freeze C1 §3.1/§3.5）。
 *
 * @param taskNo 求解任务号，仅作客户端关联号，不是主键
 * @param sandboxId 场景标识，可空，缺省取默认场景
 * @param strategyId 策略标识，可空
 * @param engine 引擎编码，为空时使用默认引擎
 * @param orderNos 参与排产的订单号；空表示场景内全部已确认订单
 * @param parameters 引擎参数
 */
public record SolveRequest(
        String taskNo,
        UUID sandboxId,
        UUID strategyId,
        String engine,
        List<String> orderNos,
        Map<String, Object> parameters) {
}
