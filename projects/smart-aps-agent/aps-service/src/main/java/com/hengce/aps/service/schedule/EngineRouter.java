package com.hengce.aps.service.schedule;

import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.service.strategy.StrategyView;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverEngine;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 引擎路由唯一解析入口（M2-SE-06，FR-SE-003 / C28）。
 *
 * <p>解析顺序：请求 {@code engine} → 策略声明 → 系统默认 {@code timefold}。未知引擎名返回
 * {@code 400/40000} 并列出可选值；目标引擎不可用时收敛到 Timefold 并输出降级原因，而不是抛出 500。</p>
 *
 * <p>策略级引擎声明复用开放的策略 JSON，读取 {@code optimizationRules.engine}，缺失时回退
 * {@code initialRules.engine}。C28 原文只冻结了请求级选择，策略级键位属于本卡补充能力（已在报告中标注）。</p>
 */
public final class EngineRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(EngineRouter.class);

    /**
     * 策略 JSON 中声明求解引擎的键。
     */
    private static final String STRATEGY_ENGINE_KEY = "engine";

    /**
     * 策略服务，仅用于读取策略声明；不可用时按“未声明”处理。
     */
    private final StrategyService strategyService;

    /**
     * 是否允许降级；关闭时 {@code aps.solve.fallback-enabled=false} 的语义优先。
     */
    private final boolean fallbackEnabled;

    /**
     * OR-Tools 是否已启用实现；M2 占位适配器默认关闭。
     */
    private final boolean ortoolsEnabled;

    /**
     * 构造路由。
     *
     * @param strategyService 策略服务
     * @param fallbackEnabled 降级开关
     * @param ortoolsEnabled OR-Tools 实现开关
     */
    public EngineRouter(StrategyService strategyService, boolean fallbackEnabled, boolean ortoolsEnabled) {
        this.strategyService = strategyService;
        this.fallbackEnabled = fallbackEnabled;
        this.ortoolsEnabled = ortoolsEnabled;
    }

    /**
     * 解析一次求解请求的引擎路由。
     *
     * @param request 求解请求，可空（按默认引擎处理）
     * @return 路由决策
     */
    public EngineRoute resolve(SolveRequest request) {
        String declared = request == null ? null : normalize(request.engine());
        String source;
        SolverEngine selected;
        if (declared != null) {
            source = EngineRoute.SOURCE_REQUEST;
            selected = parse(declared);
        } else {
            Optional<String> strategyEngine = strategyEngine(request == null ? null : request.strategyId());
            if (strategyEngine.isPresent()) {
                declared = strategyEngine.get();
                source = EngineRoute.SOURCE_STRATEGY;
                selected = parse(declared);
            } else {
                selected = SolverEngine.fromCode(ApsConstants.DEFAULT_SOLVER_ENGINE);
                declared = selected.getCode();
                source = EngineRoute.SOURCE_DEFAULT;
            }
        }

        if (selected == SolverEngine.ORTOOLS && fallbackEnabled && !ortoolsEnabled) {
            EngineRoute route = new EngineRoute(declared, source, selected, SolverEngine.TIMEFOLD,
                    true, EngineRoute.REASON_SOLVER_UNAVAILABLE,
                    EngineRoute.DETAIL_ORTOOLS_NOT_ENABLED,
                    "OR-Tools 适配器为 M2 占位实现（未启用），已降级到 Timefold");
            LOGGER.info("引擎路由决策: requested={}, source={}, selected={}, effective={}, fallback={}, reason={}",
                    route.requestedEngine(), route.engineSource(), route.selectedEngine().getCode(),
                    route.effectiveEngine().getCode(), route.fallback(), route.fallbackReason());
            return route;
        }
        EngineRoute route = new EngineRoute(declared, source, selected, selected, false, null, null, null);
        LOGGER.info("引擎路由决策: requested={}, source={}, selected={}, effective={}, fallback={}",
                route.requestedEngine(), route.engineSource(), route.selectedEngine().getCode(),
                route.effectiveEngine().getCode(), route.fallback());
        return route;
    }

    /**
     * 解析引擎编码；未知编码返回 400/40000 并列出可选值。
     *
     * @param engineCode 引擎编码
     * @return 引擎类型
     */
    private static SolverEngine parse(String engineCode) {
        try {
            return SolverEngine.fromCode(engineCode);
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的求解引擎: " + engineCode + "，可选值: " + allowedEngines());
        }
    }

    /**
     * 当前可选引擎编码列表。
     *
     * @return 逗号分隔的可选值
     */
    private static String allowedEngines() {
        StringBuilder builder = new StringBuilder();
        for (SolverEngine engine : SolverEngine.values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(engine.getCode());
        }
        return builder.toString();
    }

    /**
     * 读取策略声明的引擎；策略不存在或不可读时静默回退默认引擎，不改变既有提交行为。
     *
     * @param strategyId 策略主键，可空
     * @return 引擎编码
     */
    private Optional<String> strategyEngine(UUID strategyId) {
        if (strategyId == null) {
            return Optional.empty();
        }
        StrategyView strategy;
        try {
            strategy = strategyService.get(strategyId);
        } catch (BizException exception) {
            LOGGER.warn("策略 {} 不可用，忽略策略级引擎声明: {}", strategyId, exception.getMessage());
            return Optional.empty();
        }
        if (strategy == null) {
            return Optional.empty();
        }
        Optional<String> fromOptimization = readEngine(strategy.optimizationRules());
        if (fromOptimization.isPresent()) {
            return fromOptimization;
        }
        return readEngine(strategy.initialRules());
    }

    private static Optional<String> readEngine(Map<String, Object> rules) {
        if (rules == null) {
            return Optional.empty();
        }
        Object value = rules.get(STRATEGY_ENGINE_KEY);
        if (value instanceof String text && !text.isBlank()) {
            return Optional.of(text.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return Optional.empty();
    }

    private static String normalize(String engineCode) {
        if (engineCode == null) {
            return null;
        }
        String trimmed = engineCode.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
