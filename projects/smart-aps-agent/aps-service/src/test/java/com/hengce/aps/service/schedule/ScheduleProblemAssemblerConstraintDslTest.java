package com.hengce.aps.service.schedule;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.schedule.ScheduleRepository;
import com.hengce.aps.infrastructure.persistence.strategy.ConstraintDsl;
import com.hengce.aps.infrastructure.persistence.strategy.ConstraintDslMapper;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.dsl.RuleCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 装配器约束 DSL 接线测试：唯一 ACTIVE 注入、无 ACTIVE 保持空、歧义与不支持参数拒绝。
 */
class ScheduleProblemAssemblerConstraintDslTest {

    private static final SolveConfig CONFIG = new SolveConfig(
            "timefold", 60, 0, "LATE_ACCEPTANCE", 0L, 0L, 10, "FORWARD", true,
            Map.of("softWeights", RuleCode.defaultWeights()));

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class,
                new com.hengce.aps.infrastructure.persistence.process.template.handler.UuidTypeHandler());
        configuration.getTypeHandlerRegistry().register(java.io.Serializable.class,
                org.apache.ibatis.type.ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), ConstraintDsl.class);
    }

    @Test
    void injectsUniqueActiveDslIntoProblemConstraints() {
        ScheduleRepository repository = mock(ScheduleRepository.class);
        ConstraintDslMapper mapper = mock(ConstraintDslMapper.class);
        ConstraintDsl active = activeDsl(compiledSpec(Map.of()));
        when(repository.findOrders(any(), any(), anyBoolean())).thenReturn(List.of());
        when(mapper.selectList(any())).thenReturn(List.of(active));

        ScheduleProblemAssembler.AssembledProblem assembled = assembler(repository, mapper)
                .assemble(new SolveRequest("t-1", null, null, null, List.of(), Map.of()), CONFIG);

        assertEquals(8, assembled.problem().constraints().size());
        assertEquals(RuleCode.defaultWeights(), assembled.activeConstraintDsl().weights());
        assertEquals("dsl-default", assembled.activeConstraintDsl().code());
    }

    @Test
    void keepsHardOnlyWhenNoActiveDsl() {
        ScheduleRepository repository = mock(ScheduleRepository.class);
        ConstraintDslMapper mapper = mock(ConstraintDslMapper.class);
        when(repository.findOrders(any(), any(), anyBoolean())).thenReturn(List.of());
        when(mapper.selectList(any())).thenReturn(List.of());

        ScheduleProblemAssembler.AssembledProblem assembled = assembler(repository, mapper)
                .assemble(new SolveRequest("t-2", null, null, null, List.of(), Map.of()), CONFIG);

        assertTrue(assembled.problem().constraints().isEmpty());
        assertNull(assembled.activeConstraintDsl());
    }

    @Test
    void rejectsMultipleActiveDslNamingCodes() {
        ScheduleRepository repository = mock(ScheduleRepository.class);
        ConstraintDslMapper mapper = mock(ConstraintDslMapper.class);
        ConstraintDsl first = activeDsl(compiledSpec(Map.of()));
        ConstraintDsl second = activeDsl(compiledSpec(Map.of()));
        second.setCode("dsl-second");
        when(repository.findOrders(any(), any(), anyBoolean())).thenReturn(List.of());
        when(mapper.selectList(any())).thenReturn(List.of(first, second));

        BizException exception = assertThrows(BizException.class, () -> assembler(repository, mapper)
                .assemble(new SolveRequest("t-3", null, null, null, List.of(), Map.of()), CONFIG));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("dsl-default"), exception.getMessage());
        assertTrue(exception.getMessage().contains("dsl-second"), exception.getMessage());
    }

    @Test
    void rejectsActiveDslWithEngineUnsupportedParameter() {
        ScheduleRepository repository = mock(ScheduleRepository.class);
        ConstraintDslMapper mapper = mock(ConstraintDslMapper.class);
        ConstraintDsl active = activeDsl(compiledSpec(Map.of(
                "SAME_PRODUCT_CONTINUOUS", Map.of("groupBy", "MATERIAL"))));
        when(repository.findOrders(any(), any(), anyBoolean())).thenReturn(List.of());
        when(mapper.selectList(any())).thenReturn(List.of(active));

        BizException exception = assertThrows(BizException.class, () -> assembler(repository, mapper)
                .assemble(new SolveRequest("t-4", null, null, null, List.of(), Map.of()), CONFIG));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("SAME_PRODUCT_CONTINUOUS"), exception.getMessage());
        assertTrue(exception.getMessage().contains("groupBy=MATERIAL"), exception.getMessage());
    }

    private static ScheduleProblemAssembler assembler(ScheduleRepository repository,
                                                      ConstraintDslMapper mapper) {
        return new ScheduleProblemAssembler(repository, null, null, null, null,
                null, null, null, null, "Asia/Shanghai", mapper);
    }

    private static ConstraintDsl activeDsl(List<Map<String, Object>> compiledSpec) {
        ConstraintDsl entity = new ConstraintDsl();
        entity.setId(UUID.randomUUID());
        entity.setCode("dsl-default");
        entity.setVersionNo(2);
        entity.setStatus("ACTIVE");
        entity.setChecksum("checksum");
        entity.setCompiledSpec(compiledSpec);
        return entity;
    }

    private static List<Map<String, Object>> compiledSpec(Map<String, Map<String, Object>> paramsByRule) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", entry.getKey());
            item.put("type", "SOFT");
            item.put("weight", entry.getValue());
            item.put("hard", false);
            item.put("phase", "OPTIMIZATION");
            item.put("params", paramsByRule.getOrDefault(entry.getKey(), Map.of()));
            items.add(item);
        }
        return items;
    }
}
