package com.hengce.aps.service.schedule.infeasibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository.JustificationRow;
import com.hengce.aps.solver.api.AvailabilityWindow;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.SchedulingOperation;
import com.hengce.aps.solver.api.SchedulingOrder;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.api.TaskAssignment;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReasonCode;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityReport;
import com.hengce.aps.solver.api.infeasibility.InfeasibilityVerdict;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B1 归因内核单测：证据可复算、verdict 不越权、候选结构可产出。
 */
class InfeasibilityEvidenceTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static final OffsetDateTime BASE_UTC = BASE.atOffset(ZoneOffset.UTC);

    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final String TENANT = "tenant-b1";

    private static final String STATUS_TIMEOUT = "TIMEOUT";

    private static final String STATUS_FAILED = "FAILED";

    private static final String STATUS_SOLVED = "SOLVED";

    @Test
    void healthyProblemMustYieldNoEvidence() {
        ScheduleProblem problem = problem(
                List.of(order("O1", BigDecimal.ONE, null, List.of(operation(10, RESOURCE_ID)))), 
                List.of(resource()),
                List.of());
        EvidenceBundle bundle = EvidenceCollector.collect(problem, List.of(), false);
        assertThat(bundle.isEmpty()).isTrue();
        assertThat(bundle.infeasibilityProven()).isFalse();
        assertThat(InfeasibilityAnalyzer.decideVerdict(STATUS_TIMEOUT, bundle))
                .isEqualTo(InfeasibilityVerdict.NO_EVIDENCE);
    }

    @Test
    void budgetTimeoutWithSoftViolationsMustStayPartial() {
        ScheduleProblem problem = problem(
                List.of(order("O1", BigDecimal.ONE, null, List.of(operation(10, RESOURCE_ID)))), 
                List.of(resource()),
                List.of());
        JustificationRow soft = new JustificationRow(UUID.randomUUID(), 1L, "MIN_TARDINESS",
                "最小拖期", "SOFT", List.of(1L), "拖期 12 小时", 5,
                BigDecimal.valueOf(12), OffsetDateTime.now(ZoneOffset.UTC));
        EvidenceBundle bundle = EvidenceCollector.collect(problem, List.of(soft), false);
        assertThat(bundle.isEmpty()).isFalse();
        assertThat(bundle.infeasibilityProven()).isFalse();
        assertThat(InfeasibilityAnalyzer.decideVerdict(STATUS_TIMEOUT, bundle))
                .isEqualTo(InfeasibilityVerdict.PARTIAL_EVIDENCE);
    }

    @Test
    void windowShortMustProveInfeasibleAndOfferExtendCandidate() {
        ScheduleProblem problem = problem(
                List.of(order("O1", BigDecimal.TEN, null, List.of(operation(10, RESOURCE_ID)))), 
                List.of(resource(window(BASE, BASE.plusHours(2)))),
                List.of());
        EvidenceBundle bundle = EvidenceCollector.collect(problem, List.of(), false);
        assertThat(bundle.infeasibilityProven()).isTrue();
        assertThat(bundle.blockingTasks()).hasSize(1);
        assertThat(bundle.blockingTasks().get(0).reasonCode())
                .isEqualTo(InfeasibilityReasonCode.RESOURCE_WINDOW_SHORT);
        assertThat(bundle.resourceWindows()).hasSize(1);
        assertThat(InfeasibilityAnalyzer.decideVerdict(STATUS_FAILED, bundle))
                .isEqualTo(InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND);
        List<RepairCandidate> candidates = RepairAdvisor.advise(bundle, problem);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).kind()).isEqualTo(RepairKind.EXTEND_CALENDAR);
        assertThat(candidates.get(0).params()).containsKeys("resourceId", "from", "to");
    }

    @Test
    void notBeforeBeyondHorizonMustProveInfeasible() {
        ScheduleProblem problem = problem(
                List.of(order("O1", BigDecimal.ONE, BASE.plusHours(200),
                        List.of(operation(10, RESOURCE_ID)))), 
                List.of(resource()),
                List.of());
        EvidenceBundle bundle = EvidenceCollector.collect(problem, List.of(), false);
        assertThat(bundle.infeasibilityProven()).isTrue();
        assertThat(bundle.blockingTasks().get(0).reasonCode())
                .isEqualTo(InfeasibilityReasonCode.HORIZON_EXCEEDED);
        assertThat(InfeasibilityAnalyzer.decideVerdict(STATUS_TIMEOUT, bundle))
                .isEqualTo(InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND);
    }

    @Test
    void frozenOverlapMustProduceConflictPairAndUnfreezeCandidate() {
        TaskAssignment first = frozenAssignment(1L, 10, BASE, BASE.plusHours(2));
        TaskAssignment second = frozenAssignment(2L, 20, BASE.plusHours(1), BASE.plusHours(3));
        ScheduleProblem problem = problem(List.of(), List.of(resource()),
                List.of(first, second));
        EvidenceBundle bundle = EvidenceCollector.collect(problem, List.of(), false);
        assertThat(bundle.infeasibilityProven()).isTrue();
        assertThat(bundle.conflictPairs()).hasSize(1);
        InfeasibilityReport.ConflictPair pair = bundle.conflictPairs().get(0);
        assertThat(pair.taskA()).isEqualTo(1L);
        assertThat(pair.taskB()).isEqualTo(2L);
        assertThat(pair.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(bundle.blockingTasks()).hasSize(2);
        assertThat(bundle.blockingTasks().get(0).reasonCode())
                .isEqualTo(InfeasibilityReasonCode.FROZEN_CONFLICT);
        List<RepairCandidate> candidates = RepairAdvisor.advise(bundle, problem);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).kind()).isEqualTo(RepairKind.UNFREEZE_TASKS);
        assertThat(candidates.get(0).taskIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void moreThanEightBlockingTasksMustBeTruncated() {
        List<SchedulingOrder> orders = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            orders.add(order("O" + index, BigDecimal.TEN, null,
                    List.of(operation(10, RESOURCE_ID))));
        }
        ScheduleProblem problem = problem(orders,
                List.of(resource(window(BASE, BASE.plusHours(1)))), List.of());
        EvidenceBundle bundle = EvidenceCollector.collect(problem, List.of(), false);
        assertThat(bundle.truncated()).isTrue();
        assertThat(bundle.blockingTasks()).hasSize(8);
    }

    @Test
    void evaluateNoEvidenceMustHaveNullGeneratedAtAndDisabledLlm() {
        ScheduleProblem problem = problem(
                List.of(order("O1", BigDecimal.ONE, null, List.of(operation(10, RESOURCE_ID)))), 
                List.of(resource()),
                List.of());
        InfeasibilityAnalyzer.AnalyzeInput input = new InfeasibilityAnalyzer.AnalyzeInput(
                1L, STATUS_TIMEOUT, null, null, Map.of(), problem, false, false, List.of(), null, null);
        InfeasibilityAnalysis analysis = InfeasibilityAnalyzer.evaluate(input);
        InfeasibilityReport report = analysis.report();
        assertThat(report.verdict()).isEqualTo(InfeasibilityVerdict.NO_EVIDENCE);
        assertThat(report.generatedAt()).isNull();
        assertThat(report.narrative()).isNull();
        assertThat(report.llmStatus()).isEqualTo(InfeasibilityReport.LlmStatus.DISABLED);
        assertThat(report.blockingTasks()).isEmpty();
        assertThat(analysis.repairCandidates()).isEmpty();
    }

    @Test
    void evaluateFailedWithProofMustReturnNoFeasibleSolution() {
        ScheduleProblem problem = problem(
                List.of(order("O1", BigDecimal.TEN, null, List.of(operation(10, RESOURCE_ID)))), 
                List.of(resource(window(BASE, BASE.plusHours(2)))),
                List.of());
        InfeasibilityAnalyzer.AnalyzeInput input = new InfeasibilityAnalyzer.AnalyzeInput(
                1L, STATUS_FAILED, Long.valueOf(-4), Long.valueOf(0), Map.of(
                        "terminationReason", "HARD_LIMIT", "unassignedTaskCount", 1),
                problem, false, false, List.of(), 7L, null);
        InfeasibilityAnalysis analysis = InfeasibilityAnalyzer.evaluate(input);
        InfeasibilityReport report = analysis.report();
        assertThat(report.verdict()).isEqualTo(InfeasibilityVerdict.NO_FEASIBLE_SOLUTION_FOUND);
        assertThat(report.generatedAt()).isNotNull();
        assertThat(report.narrative()).contains("结构性不可行");
        assertThat(report.llmStatus()).isEqualTo(InfeasibilityReport.LlmStatus.DISABLED);
        assertThat(report.candidateSeed()).isEqualTo(7L);
        assertThat(analysis.repairCandidates()).hasSize(1);
    }

    @Test
    void evaluateSolvedMustNeverClaimInfeasible() {
        ScheduleProblem problem = problem(
                List.of(order("O1", BigDecimal.TEN, null, List.of(operation(10, RESOURCE_ID)))), 
                List.of(resource(window(BASE, BASE.plusHours(2)))),
                List.of());
        InfeasibilityAnalyzer.AnalyzeInput input = new InfeasibilityAnalyzer.AnalyzeInput(
                1L, STATUS_SOLVED, Long.valueOf(0), Long.valueOf(0), Map.of(),
                problem, false, false, List.of(), null, null);
        InfeasibilityAnalysis analysis = InfeasibilityAnalyzer.evaluate(input);
        assertThat(analysis.report().verdict()).isEqualTo(InfeasibilityVerdict.NO_EVIDENCE);
        assertThat(analysis.report().generatedAt()).isNull();
    }

    @Test
    void replayFailureMustDegradeToNoEvidence() {
        InfeasibilityAnalyzer.AnalyzeInput input = new InfeasibilityAnalyzer.AnalyzeInput(
                1L, STATUS_FAILED, null, null, Map.of(), null, false, false, List.of(), null,
                "题面重放失败: no resources");
        InfeasibilityAnalysis analysis = InfeasibilityAnalyzer.evaluate(input);
        assertThat(analysis.report().verdict()).isEqualTo(InfeasibilityVerdict.NO_EVIDENCE);
        assertThat(analysis.report().generatedAt()).isNull();
        assertThat(analysis.report().assumptions())
                .anyMatch(line -> line.contains("题面重放失败"));
    }

    private static ScheduleProblem problem(List<SchedulingOrder> orders,
                                         List<SchedulingResource> resources,
                                         List<TaskAssignment> frozenAssignments) {
        return new ScheduleProblem(TENANT, ScheduleProblem.DEFAULT_SANDBOX_ID, 1L, null,
                "FORWARD", orders, resources, frozenAssignments, List.of(), List.of(),
                BASE, List.of());
    }

    private static SchedulingOrder order(String orderNo, BigDecimal qty, LocalDateTime notBefore,
                                        List<SchedulingOperation> operations) {
        return new SchedulingOrder(UUID.randomUUID(), orderNo, "M-" + orderNo, null, qty,
                BASE.toLocalDate(), "NORMAL", "UNFROZEN", null, operations, notBefore);
    }

    private static SchedulingOperation operation(int operationIndex, UUID primaryResourceId) {
        return new SchedulingOperation(operationIndex, "OP" + operationIndex,
                "工序" + operationIndex, null, BigDecimal.ONE, BigDecimal.ZERO,
                primaryResourceId, null, List.of(), false);
    }

    private static SchedulingResource resource(AvailabilityWindow... windows) {
        return new SchedulingResource(RESOURCE_ID, "R1", "PRIMARY", null, "UNIT_PER_HOUR",
                BigDecimal.ONE, 1, BigDecimal.ONE, null, "ACTIVE", List.of(windows));
    }

    private static AvailabilityWindow window(LocalDateTime start, LocalDateTime end) {
        return new AvailabilityWindow(start.atOffset(ZoneOffset.UTC), end.atOffset(ZoneOffset.UTC));
    }

    private static TaskAssignment frozenAssignment(long taskId, int operationIndex,
                                                  LocalDateTime start, LocalDateTime end) {
        return new TaskAssignment(taskId, UUID.fromString("00000000-0000-0000-0000-00000000b001"),
                "OF", operationIndex, "工序" + operationIndex, null, RESOURCE_ID, "R1", List.of(),
                start.atOffset(ZoneOffset.UTC), start.atOffset(ZoneOffset.UTC),
                start.atOffset(ZoneOffset.UTC), end.atOffset(ZoneOffset.UTC),
                BigDecimal.ONE, 1, "FROZEN", true, 0, "FROZEN", null, null, List.of());
    }
}
