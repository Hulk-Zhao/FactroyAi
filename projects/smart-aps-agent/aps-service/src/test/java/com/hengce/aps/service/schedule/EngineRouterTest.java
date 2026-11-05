package com.hengce.aps.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.service.strategy.StrategyView;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverEngine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 引擎路由单测（M2-SE-06）：请求级优先于策略级、缺省 Timefold、未知引擎 400、OR-Tools 不可用时降级。
 */
@ExtendWith(MockitoExtension.class)
class EngineRouterTest {

    private static final UUID STRATEGY_ID = UUID.randomUUID();

    @Mock
    private StrategyService strategyService;

    @Test
    void noEngineAndNoStrategyShouldUseDefaultTimefoldWithoutFallback() {
        EngineRouter router = new EngineRouter(strategyService, true, false);

        EngineRoute route = router.resolve(request(null, null));

        assertEquals("timefold", route.requestedEngine());
        assertEquals(EngineRoute.SOURCE_DEFAULT, route.engineSource());
        assertEquals(SolverEngine.TIMEFOLD, route.selectedEngine());
        assertEquals(SolverEngine.TIMEFOLD, route.effectiveEngine());
        assertFalse(route.fallback());
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        route.applyFallback(metrics);
        assertEquals(Boolean.FALSE, metrics.get("engineSpecified"));
        assertFalse(metrics.containsKey("engineFallback"));
    }

    @Test
    void requestEngineShouldOverrideStrategyDeclaration() {
        EngineRouter router = new EngineRouter(strategyService, true, false);

        EngineRoute route = router.resolve(request("timefold", STRATEGY_ID));

        assertEquals(EngineRoute.SOURCE_REQUEST, route.engineSource());
        assertEquals(SolverEngine.TIMEFOLD, route.effectiveEngine());
        assertFalse(route.fallback());
        verifyNoInteractions(strategyService);
    }

    @Test
    void blankRequestEngineShouldReadStrategyDeclaration() {
        when(strategyService.get(STRATEGY_ID)).thenReturn(strategy(Map.of("engine", "ortools")));
        EngineRouter router = new EngineRouter(strategyService, true, false);

        EngineRoute route = router.resolve(request(null, STRATEGY_ID));

        assertEquals("ortools", route.requestedEngine());
        assertEquals(EngineRoute.SOURCE_STRATEGY, route.engineSource());
        assertEquals(SolverEngine.ORTOOLS, route.selectedEngine());
        assertEquals(SolverEngine.TIMEFOLD, route.effectiveEngine());
        assertTrue(route.fallback());
        assertEquals(EngineRoute.REASON_SOLVER_UNAVAILABLE, route.fallbackReason());
    }

    @Test
    void unavailableOrtoolsShouldFallbackToTimefoldInsteadOfFailing() {
        EngineRouter router = new EngineRouter(strategyService, true, false);

        EngineRoute route = router.resolve(request("ortools", null));

        assertEquals(EngineRoute.SOURCE_REQUEST, route.engineSource());
        assertEquals(SolverEngine.ORTOOLS, route.selectedEngine());
        assertEquals(SolverEngine.TIMEFOLD, route.effectiveEngine());
        assertTrue(route.fallback());
        assertEquals(EngineRoute.REASON_SOLVER_UNAVAILABLE, route.fallbackReason());
    }

    @Test
    void fallbackDisabledShouldKeepRequestedOrtools() {
        EngineRouter router = new EngineRouter(strategyService, false, false);

        EngineRoute route = router.resolve(request("ortools", null));

        assertEquals(SolverEngine.ORTOOLS, route.effectiveEngine());
        assertFalse(route.fallback());
    }

    @Test
    void unknownEngineShouldReturn400WithAllowedValues() {
        EngineRouter router = new EngineRouter(strategyService, true, false);

        BizException exception = assertThrows(BizException.class,
                () -> router.resolve(request("gurobi", null)));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("timefold"), exception.getMessage());
        assertTrue(exception.getMessage().contains("ortools"), exception.getMessage());
    }

    @Test
    void unavailableStrategyShouldFallbackToDefaultEngine() {
        when(strategyService.get(STRATEGY_ID))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "策略不存在"));
        EngineRouter router = new EngineRouter(strategyService, true, false);

        EngineRoute route = router.resolve(request(null, STRATEGY_ID));

        assertEquals(EngineRoute.SOURCE_DEFAULT, route.engineSource());
        assertEquals(SolverEngine.TIMEFOLD, route.effectiveEngine());
    }

    @Test
    void routingDecisionShouldBeWrittenIntoMetrics() {
        EngineRouter router = new EngineRouter(strategyService, true, false);
        EngineRoute route = router.resolve(request("ortools", null));
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();

        route.applyFallback(metrics);

        assertEquals("ortools", metrics.get("requestedEngine"));
        assertEquals("REQUEST", metrics.get("engineSource"));
        assertEquals(Boolean.TRUE, metrics.get("engineSpecified"));
        assertEquals("ortools", metrics.get("selectedEngine"));
        assertEquals("timefold", metrics.get("effectiveEngine"));
        assertEquals(Boolean.TRUE, metrics.get("engineFallback"));
        assertEquals(EngineRoute.REASON_SOLVER_UNAVAILABLE, metrics.get("engineFallbackReason"));
        assertEquals(EngineRoute.DETAIL_ORTOOLS_NOT_ENABLED, metrics.get("engineFallbackDetail"));
        assertEquals(EngineRoute.FALLBACK_MODE, metrics.get("fallbackMode"));
        assertTrue(metrics.get("fallbackAt") != null);
    }

    private static SolveRequest request(String engine, UUID strategyId) {
        return new SolveRequest("T-1", UUID.randomUUID(), strategyId, engine, List.of("SO-1"), Map.of());
    }

    private static StrategyView strategy(Map<String, Object> optimizationRules) {
        return new StrategyView(STRATEGY_ID, "策略A", "FORWARD", 1, Map.of(), optimizationRules,
                null, null, Map.of(), List.of(), 0, BigDecimal.ZERO, 1, null, null);
    }
}
