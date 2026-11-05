package com.hengce.aps.service.strategy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.strategy.Strategy;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyMapper;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyRule;
import com.hengce.aps.infrastructure.persistence.strategy.StrategyRuleMapper;
import com.hengce.aps.solver.dsl.RuleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 策略服务测试：8 条规则权重契约、CRUD 闭环、克隆与导入导出、409/422 拦截。
 */
@ExtendWith(MockitoExtension.class)
class StrategyServiceTest {

    @org.junit.jupiter.api.BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UUID.class,
                new com.hengce.aps.infrastructure.persistence.process.template.handler.UuidTypeHandler());
        configuration.getTypeHandlerRegistry().register(java.io.Serializable.class,
                org.apache.ibatis.type.ObjectTypeHandler.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Strategy.class);
    }

    @Mock
    private StrategyMapper strategyMapper;

    @Mock
    private StrategyRuleMapper strategyRuleMapper;

    private StrategyService strategyService;

    @BeforeEach
    void setUp() {
        strategyService = new StrategyService(strategyMapper, strategyRuleMapper);
    }

    @Test
    void createWithOmittedRulesUsesEightDefaultWeights() {
        stubNoDuplicateName();
        when(strategyRuleMapper.selectList(any())).thenReturn(List.of());

        StrategyView view = strategyService.create(createCommand(null));
        ArgumentCaptor<StrategyRule> captor = ArgumentCaptor.forClass(StrategyRule.class);
        verify(strategyRuleMapper, times(8)).insert(captor.capture());

        List<StrategyRule> inserted = captor.getAllValues();
        assertEquals(8, inserted.size());
        BigDecimal total = inserted.stream().map(StrategyRule::getWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(BigDecimal.valueOf(RuleCode.TOTAL_WEIGHT), total);
        for (StrategyRule rule : inserted) {
            assertEquals("OPTIMIZATION", rule.getRulePhase());
            assertEquals("SOFT", rule.getRuleType());
        }
        assertNotNull(view.id());
        assertEquals("STRATEGY-A", view.name());
    }

    @Test
    void createRejectsWeightSumNotHundred() {
        List<StrategyRuleCommand> rules = defaultRuleCommands();
        rules.set(0, new StrategyRuleCommand(RuleCode.MIN_TARDINESS.name(), BigDecimal.valueOf(30), Map.of(), true));

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.create(createCommand(rules)));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("[42203]"), exception.getMessage());
        verify(strategyMapper, never()).insert(any(Strategy.class));
    }

    @Test
    void createRejectsUnknownRuleCode() {
        List<StrategyRuleCommand> rules = defaultRuleCommands();
        rules.set(1, new StrategyRuleCommand("NOT_A_RULE", BigDecimal.valueOf(15), Map.of(), true));

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.create(createCommand(rules)));

        assertTrue(exception.getMessage().contains("[42202]"), exception.getMessage());
    }

    @Test
    void createAcceptsDisabledRuleOnlyWhenWeightIsZero() {
        List<StrategyRuleCommand> rules = defaultRuleCommands();
        rules.set(0, new StrategyRuleCommand(RuleCode.MIN_TARDINESS.name(), BigDecimal.ZERO,
                Map.of(), false));
        rules.set(1, new StrategyRuleCommand(RuleCode.EDD.name(), BigDecimal.valueOf(35),
                Map.of(), true));
        stubNoDuplicateName();
        when(strategyRuleMapper.selectList(any()))
                .thenAnswer(invocation -> ruleEntities(UUID.randomUUID(), rules));

        StrategyView view = strategyService.create(createCommand(rules));

        StrategyRuleView disabled = view.rules().stream()
                .filter(rule -> RuleCode.MIN_TARDINESS.name().equals(rule.code()))
                .findFirst().orElseThrow();
        assertFalse(disabled.enabled());
        assertEquals(0, disabled.weight().compareTo(BigDecimal.ZERO));
        assertEquals(BigDecimal.valueOf(RuleCode.TOTAL_WEIGHT), view.weightSum());
    }

    @Test
    void createRejectsDisabledRuleWithNonZeroWeight() {
        List<StrategyRuleCommand> rules = defaultRuleCommands();
        rules.set(0, new StrategyRuleCommand(RuleCode.MIN_TARDINESS.name(), BigDecimal.valueOf(20),
                Map.of(), false));

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.create(createCommand(rules)));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("enabled=false 时权重必须为 0"),
                exception.getMessage());
        verify(strategyMapper, never()).insert(any(Strategy.class));
    }

    @Test
    void createRejectsDuplicateStrategyVersion() {
        when(strategyMapper.selectCount(any())).thenReturn(1L);

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.create(createCommand(null)));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    @Test
    void updateRequiresFullRuleReplacement() {
        UUID id = UUID.randomUUID();
        when(strategyMapper.selectById(id)).thenReturn(existingStrategy(id));

        BizException exception = assertThrows(BizException.class, () -> strategyService.update(id,
                new StrategyUpdateCommand(1, null, null, null, null, null, null, null)));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("[42207]"));
        verify(strategyMapper, never()).updateById(any(Strategy.class));
    }

    @Test
    void updateRejectsStaleVersion() {
        UUID id = UUID.randomUUID();
        when(strategyMapper.selectById(id)).thenReturn(existingStrategy(id));

        BizException exception = assertThrows(BizException.class, () -> strategyService.update(id,
                new StrategyUpdateCommand(9, "STRATEGY-A", null, null, null, null, null,
                        defaultRuleCommands())));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
    }

    @Test
    void updateReplacesRulesInPlace() {
        UUID id = UUID.randomUUID();
        Strategy existing = existingStrategy(id);
        Strategy latest = existingStrategy(id);
        latest.setVersion(2);
        when(strategyMapper.selectById(id)).thenReturn(existing, latest);
        when(strategyMapper.updateById(any(Strategy.class))).thenReturn(1);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id));

        StrategyView view = strategyService.update(id, new StrategyUpdateCommand(1, null, null, null,
                null, null, null, defaultRuleCommands()));

        verify(strategyMapper).updateById(existing);
        verify(strategyRuleMapper, times(8)).updateById(any(StrategyRule.class));
        verify(strategyRuleMapper, never()).deleteById(any(UUID.class));
        assertEquals(8, view.ruleCount());
        assertEquals(BigDecimal.valueOf(100), view.weightSum());
    }

    @Test
    void cloneCopiesAllRulesAndBumpsVersion() {
        UUID id = UUID.randomUUID();
        Strategy source = existingStrategy(id);
        source.setName("STRATEGY-A");
        when(strategyMapper.selectById(id)).thenReturn(source);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id), List.of(), List.of());
        when(strategyMapper.selectList(any())).thenReturn(List.of(source));
        when(strategyMapper.selectCount(any())).thenReturn(0L);

        StrategyView view = strategyService.clone(id, new StrategyCloneCommand(null, null, null));

        assertEquals(2, view.strategyVersion());
        ArgumentCaptor<Strategy> captor = ArgumentCaptor.forClass(Strategy.class);
        verify(strategyMapper).insert(captor.capture());
        assertEquals("STRATEGY-A", captor.getValue().getName());
        verify(strategyRuleMapper, times(8)).insert(any(StrategyRule.class));
    }

    @Test
    void exportThenImportProducesEquivalentRules() {
        UUID id = UUID.randomUUID();
        Strategy source = existingStrategy(id);
        when(strategyMapper.selectById(id)).thenReturn(source);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id), List.of());

        StrategyExportBundle bundle = strategyService.export(id);
        assertEquals(StrategyExportPayload.FORMAT_VERSION, bundle.strategy().get("formatVersion"));
        assertEquals(8, bundle.rules().size());

        stubNoDuplicateName();
        StrategyImportResult result = strategyService.importStrategy(
                new StrategyImportRequest(payloadOf(bundle), false));
        assertTrue(result.valid());
        assertNotNull(result.strategyId());

        ArgumentCaptor<StrategyRule> captor = ArgumentCaptor.forClass(StrategyRule.class);
        verify(strategyRuleMapper, times(8)).insert(captor.capture());
        BigDecimal total = captor.getAllValues().stream().map(StrategyRule::getWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(BigDecimal.valueOf(100), total);
    }

    /**
     * 单测①：同一载荷两次导出 checksum 相同。
     */
    @Test
    void exportComputesSameChecksumForSamePayload() {
        UUID id = UUID.randomUUID();
        when(strategyMapper.selectById(id)).thenReturn(existingStrategy(id));
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id));

        StrategyExportBundle first = strategyService.export(id);
        StrategyExportBundle second = strategyService.export(id);

        assertEquals(first.checksum(), second.checksum());
        assertTrue(first.checksum().matches("[0-9a-f]{64}"), first.checksum());
    }

    /**
     * 单测②：改一个字段后 checksum 必变。
     */
    @Test
    void exportChecksumChangesWhenOneFieldChanges() {
        UUID id = UUID.randomUUID();
        when(strategyMapper.selectById(id)).thenReturn(existingStrategy(id));
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id));

        StrategyExportBundle bundle = strategyService.export(id);
        Map<String, Object> edited = new LinkedHashMap<>(bundle.strategy());
        edited.put("name", "STRATEGY-EDITED");

        String editedChecksum = StrategyCanonicalJson.checksum(edited, ruleNodesOf(bundle.rules()));
        assertNotEquals(bundle.checksum(), editedChecksum);
    }

    /**
     * 单测③：checksum 不匹配 → 拒绝且不落库。
     */
    @Test
    void importRejectsTamperedPayloadWithoutPersisting() {
        StrategyExportBundle bundle = exportFixture();
        Map<String, Object> payload = payloadOf(bundle);
        Map<String, Object> edited = new LinkedHashMap<>(bundle.strategy());
        edited.put("name", "STRATEGY-EDITED");
        payload.put("strategy", edited);

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.importStrategy(new StrategyImportRequest(payload, false)));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, exception.getErrorCode());
        assertEquals(42200, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("被改动"), exception.getMessage());
        verify(strategyMapper, never()).insert(any(Strategy.class));
        verify(strategyRuleMapper, never()).insert(any(StrategyRule.class));
    }

    /**
     * 单测③补充：checksum 缺失 / 格式非法各自单独区分（不混成“格式错误”）。
     */
    @Test
    void importDistinguishesMissingAndMalformedChecksum() {
        StrategyExportBundle bundle = exportFixture();

        Map<String, Object> missing = payloadOf(bundle);
        missing.remove("checksum");
        BizException missingException = assertThrows(BizException.class,
                () -> strategyService.importStrategy(new StrategyImportRequest(missing, true)));
        assertEquals(42200, missingException.getErrorCode().getCode());
        assertTrue(missingException.getMessage().contains("缺失"), missingException.getMessage());

        Map<String, Object> malformed = payloadOf(bundle);
        malformed.put("checksum", "not-a-checksum");
        BizException malformedException = assertThrows(BizException.class,
                () -> strategyService.importStrategy(new StrategyImportRequest(malformed, true)));
        assertEquals(42200, malformedException.getErrorCode().getCode());
        assertTrue(malformedException.getMessage().contains("格式非法"), malformedException.getMessage());
        verify(strategyMapper, never()).insert(any(Strategy.class));
    }

    /**
     * 单测④：dryRun=true 只校验不落库，strategyId 为 null。
     */
    @Test
    void importDryRunValidatesWithoutPersisting() {
        StrategyExportBundle bundle = exportFixture();

        StrategyImportResult result = strategyService.importStrategy(
                new StrategyImportRequest(payloadOf(bundle), true));

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
        assertNull(result.strategyId());
        verify(strategyMapper, never()).insert(any(Strategy.class));
        verify(strategyRuleMapper, never()).insert(any(StrategyRule.class));
    }

    /**
     * 单测⑤：dryRun=false 正常落库并返回 strategyId。
     */
    @Test
    void importPersistsWhenDryRunIsFalse() {
        StrategyExportBundle bundle = exportFixture();
        stubNoDuplicateName();

        StrategyImportResult result = strategyService.importStrategy(
                new StrategyImportRequest(payloadOf(bundle), false));

        assertTrue(result.valid());
        assertNotNull(result.strategyId());
        verify(strategyMapper).insert(any(Strategy.class));
        verify(strategyRuleMapper, times(8)).insert(any(StrategyRule.class));
    }

    /**
     * 幂等：同名同版本且内容 checksum 相同 → 200 命中既有，不新建/不升版本/不写 rules。
     *
     * <p>既有记录的 {@code id}/{@code version} 与导出源不同，用于证明幂等判定已剔除这两个元数据。</p>
     */
    @Test
    void importIsIdempotentWhenExistingContentMatches() {
        StrategyExportBundle bundle = exportFixture();
        UUID existingId = UUID.randomUUID();
        Strategy existing = existingStrategy(existingId);
        existing.setVersion(7);
        when(strategyMapper.selectOne(any())).thenReturn(existing);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(existingId));

        StrategyImportResult result = strategyService.importStrategy(
                new StrategyImportRequest(payloadOf(bundle), false));

        assertTrue(result.valid());
        assertTrue(result.existing());
        assertEquals(existingId, result.strategyId());
        verify(strategyMapper, never()).insert(any(Strategy.class));
        verify(strategyRuleMapper, never()).insert(any(StrategyRule.class));
    }

    /**
     * 冲突：同名同版本但内容 checksum 不同 → 40901，message 点名 name 与 version，不自动升版本。
     */
    @Test
    void importRejectsSameNameVersionWithDifferentContent() {
        StrategyExportBundle bundle = exportFixture();
        UUID existingId = UUID.randomUUID();
        when(strategyMapper.selectOne(any())).thenReturn(existingStrategy(existingId));
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(existingId));

        Map<String, Object> payload = payloadOf(bundle);
        Map<String, Object> edited = new LinkedHashMap<>(bundle.strategy());
        edited.put("direction", "BACKWARD");
        payload.put("strategy", edited);
        payload.put("checksum", StrategyCanonicalJson.checksum(edited, ruleNodesOf(bundle.rules())));

        BizException exception = assertThrows(BizException.class,
                () -> strategyService.importStrategy(new StrategyImportRequest(payload, false)));

        assertEquals(ErrorCode.STATE_CONFLICT, exception.getErrorCode());
        assertEquals(40901, exception.getErrorCode().getCode());
        assertTrue(exception.getMessage().contains("STRATEGY-A"), exception.getMessage());
        assertTrue(exception.getMessage().contains("v1"), exception.getMessage());
        verify(strategyMapper, never()).insert(any(Strategy.class));
        verify(strategyRuleMapper, never()).insert(any(StrategyRule.class));
    }

    /**
     * errors[].code 契约对齐：OpenAPI 声明为 string，实际必须是 "42200" 字符串，
     * 且它只是端点层码的投影——字段级差异只体现在 field / message。
     */
    @Test
    void importErrorsCarryStringCode() {
        StrategyExportBundle bundle = exportFixture();
        Map<String, Object> payload = payloadOf(bundle);
        Map<String, Object> edited = new LinkedHashMap<>(bundle.strategy());
        edited.put("name", "   ");
        edited.put("direction", "SIDEWAYS");
        payload.put("strategy", edited);
        payload.put("checksum", StrategyCanonicalJson.checksum(edited, ruleNodesOf(bundle.rules())));

        StrategyImportResult result = strategyService.importStrategy(
                new StrategyImportRequest(payload, true));

        assertFalse(result.valid());
        assertEquals(2, result.errors().size());
        for (StrategyImportError error : result.errors()) {
            assertEquals("42200", error.code());
        }
        assertEquals("strategy.name", result.errors().get(0).field());
        assertEquals("strategy.direction", result.errors().get(1).field());
        assertNull(result.strategyId());
        verify(strategyMapper, never()).insert(any(Strategy.class));
    }

    private StrategyExportBundle exportFixture() {
        UUID id = UUID.randomUUID();
        when(strategyMapper.selectById(id)).thenReturn(existingStrategy(id));
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules(id));
        return strategyService.export(id);
    }

    private static Map<String, Object> payloadOf(StrategyExportBundle bundle) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("strategy", bundle.strategy());
        payload.put("rules", ruleNodesOf(bundle.rules()));
        payload.put("checksum", bundle.checksum());
        return payload;
    }

    private static List<Map<String, Object>> ruleNodesOf(List<StrategyRuleCommand> rules) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (StrategyRuleCommand rule : rules) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("code", rule.code());
            node.put("weight", rule.weight());
            node.put("params", rule.params() == null ? Map.of() : rule.params());
            node.put("enabled", rule.enabled());
            nodes.add(node);
        }
        return nodes;
    }

    @Test
    void listCarriesRulesAndWeightSummary() {
        Page<Strategy> page = new Page<>(1, 20);
        Strategy strategy = existingStrategy(UUID.randomUUID());
        page.setRecords(List.of(strategy));
        page.setTotal(1);
        when(strategyMapper.selectPage(any(), any())).thenReturn(page);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules((UUID) strategy.getId()));

        PageResult<StrategyView> result = strategyService.list(new PageQuery(), null, null, null);

        assertEquals(1, result.items().size());
        StrategyView view = result.items().get(0);
        assertEquals(8, view.ruleCount());
        assertEquals(BigDecimal.valueOf(100), view.weightSum());
        assertEquals(8, view.rules().size());
        assertEquals("MIN_TARDINESS", view.rules().get(0).code());
        assertEquals("OPTIMIZATION", view.rules().get(0).phase());
        assertEquals("SOFT", view.rules().get(0).type());
    }

    @Test
    void listAppliesEffectiveOnClosedWindow() {
        LocalDate effectiveOn = LocalDate.of(2026, 6, 1);
        stubListWithOneStrategy();

        strategyService.list(new PageQuery(), null, null, effectiveOn);

        LambdaQueryWrapper<Strategy> wrapper = captureListWrapper();
        assertTrue(wrapper.getSqlSegment().contains("effective_from"), wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSegment().contains("effective_to"), wrapper.getSqlSegment());
        assertEquals(2, wrapper.getParamNameValuePairs().values().stream()
                .filter(effectiveOn::equals).count(),
                "effectiveFrom 与 effectiveTo 两端都必须带上生效日期");
    }

    @Test
    void listWithoutEffectiveOnDoesNotFilterByWindow() {
        stubListWithOneStrategy();

        strategyService.list(new PageQuery(), null, null, null);

        LambdaQueryWrapper<Strategy> wrapper = captureListWrapper();
        assertFalse(wrapper.getSqlSegment().contains("effective_from"),
                "未传 effectiveOn 时不得附加生效窗口条件");
    }

    private void stubListWithOneStrategy() {
        Page<Strategy> page = new Page<>(1, 20);
        Strategy strategy = existingStrategy(UUID.randomUUID());
        page.setRecords(List.of(strategy));
        page.setTotal(1);
        when(strategyMapper.selectPage(any(), any())).thenReturn(page);
        when(strategyRuleMapper.selectList(any())).thenReturn(existingRules((UUID) strategy.getId()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<Strategy> captureListWrapper() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(strategyMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    private void stubNoDuplicateName() {
        when(strategyMapper.selectCount(any())).thenReturn(0L);
    }

    private static StrategyCreateCommand createCommand(List<StrategyRuleCommand> rules) {
        return new StrategyCreateCommand("STRATEGY-A", "FORWARD", 1, LocalDate.of(2026, 1, 1), null,
                Map.of(), Map.of(), rules);
    }

    private static Strategy existingStrategy(UUID id) {
        Strategy strategy = new Strategy();
        strategy.setId(id);
        strategy.setName("STRATEGY-A");
        strategy.setDirection("FORWARD");
        strategy.setStrategyVersion(1);
        strategy.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        strategy.setInitialRules(Map.of());
        strategy.setOptimizationRules(Map.of());
        strategy.setApplyCondition(Map.of());
        strategy.setVersion(1);
        return strategy;
    }

    private static List<StrategyRule> existingRules(UUID strategyId) {
        List<StrategyRule> rules = new ArrayList<>();
        int seqNo = 0;
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            StrategyRule rule = new StrategyRule();
            rule.setId(UUID.randomUUID());
            rule.setStrategyId(strategyId);
            rule.setRuleCode(entry.getKey());
            rule.setName(entry.getKey());
            rule.setRulePhase("OPTIMIZATION");
            rule.setRuleType("SOFT");
            rule.setWeight(BigDecimal.valueOf(entry.getValue()));
            rule.setParams(Map.of());
            rule.setEnabled(true);
            rule.setSeqNo(seqNo++);
            rule.setVersion(1);
            rules.add(rule);
        }
        return rules;
    }

    private static List<StrategyRule> ruleEntities(UUID strategyId, List<StrategyRuleCommand> commands) {
        List<StrategyRule> rules = new ArrayList<>();
        int seqNo = 0;
        for (StrategyRuleCommand command : commands) {
            StrategyRule rule = new StrategyRule();
            rule.setId(UUID.randomUUID());
            rule.setStrategyId(strategyId);
            rule.setRuleCode(command.code());
            rule.setName(command.code());
            rule.setRulePhase(StrategyService.PHASE_OPTIMIZATION);
            rule.setRuleType(StrategyService.TYPE_SOFT);
            rule.setWeight(command.weight());
            rule.setParams(command.params() == null ? Map.of() : command.params());
            rule.setEnabled(command.enabled() == null || command.enabled());
            rule.setSeqNo(seqNo++);
            rule.setVersion(1);
            rules.add(rule);
        }
        return rules;
    }

    private static List<StrategyRuleCommand> defaultRuleCommands() {
        List<StrategyRuleCommand> rules = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : RuleCode.defaultWeights().entrySet()) {
            rules.add(new StrategyRuleCommand(entry.getKey(), BigDecimal.valueOf(entry.getValue()),
                    Map.of(), true));
        }
        return rules;
    }
}
