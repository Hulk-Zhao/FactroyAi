package com.hengce.aps.solver.timefold.constraint;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.hengce.aps.solver.api.SchedulingResource;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C21 求解期锁定硬约束测试：TIME_WINDOW 闭区间、RESOURCE 白名单、FROZEN 优先级与缺字段静默。
 *
 * <p>用 {@link SolutionManager} 对固定赋值只更新影子变量并算分（不做求解），避免求解器把违规任务
 * 挪出窗口后“假通过”。每个用例都断言 {@code hardScore} 的准确前后值。</p>
 */
class TaskLockConstraintTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 5, 8, 0);

    private static final UUID RESOURCE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static final UUID RESOURCE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final SolverFactory<SchedulePlan> SOLVER_FACTORY = SolverFactory.create(
            new SolverConfig()
                    .withSolutionClass(SchedulePlan.class)
                    .withEntityClasses(ResourcePlan.class, TaskPlan.class)
                    .withConstraintProviderClass(ApsConstraintProvider.class));

    private static final SolutionManager<SchedulePlan, HardSoftScore> SOLUTION_MANAGER =
            SolutionManager.create(SOLVER_FACTORY);

    @Test
    void timeWindowLockAllowsTaskInsideClosedWindowIncludingEndpoints() {
        TaskPlan task = task(RESOURCE_A, BASE, 60L);
        task.setLockType(ConstraintCodes.LOCK_TIME_WINDOW);
        task.setLockedFrom(BASE);
        task.setLockedTo(BASE.plusSeconds(60));

        HardSoftScore score = score(task, RESOURCE_A);

        assertEquals(0, score.hardScore(), "start==from、end==to 落在含端点的闭区间内，不应惩罚");
    }

    @Test
    void timeWindowLockPenalizesTaskStartingBeforeWindow() {
        TaskPlan task = task(RESOURCE_A, BASE, 60L);
        assertEquals(0, score(task, RESOURCE_A).hardScore(), "未加锁基线为 0");
        task.setLockType(ConstraintCodes.LOCK_TIME_WINDOW);
        task.setLockedFrom(BASE.plusMinutes(10));
        task.setLockedTo(BASE.plusHours(2));

        HardSoftScore score = score(task, RESOURCE_A);

        assertEquals(-1, score.hardScore(), "开始时间早于 lockedFrom 应记 1 个硬违规");
    }

    @Test
    void timeWindowLockPenalizesTaskEndingAfterWindow() {
        TaskPlan task = task(RESOURCE_A, BASE, 3600L);
        assertEquals(0, score(task, RESOURCE_A).hardScore(), "未加锁基线为 0");
        task.setLockType(ConstraintCodes.LOCK_TIME_WINDOW);
        task.setLockedFrom(BASE.minusHours(1));
        task.setLockedTo(BASE.plusMinutes(30));

        HardSoftScore score = score(task, RESOURCE_A);

        assertEquals(-1, score.hardScore(), "结束时间晚于 lockedTo 应记 1 个硬违规");
    }

    @Test
    void resourceLockAllowsPrimaryResourceInLockedSet() {
        TaskPlan task = task(RESOURCE_A, BASE, 60L);
        task.setLockType(ConstraintCodes.LOCK_RESOURCE);
        task.setLockedResourceIds(List.of(RESOURCE_A));

        HardSoftScore score = score(task, RESOURCE_A);

        assertEquals(0, score.hardScore());
    }

    @Test
    void resourceLockPenalizesPrimaryResourceOutsideLockedSet() {
        TaskPlan task = task(RESOURCE_A, BASE, 60L);
        assertEquals(0, score(task, RESOURCE_A).hardScore(), "未加锁基线为 0");
        task.setLockType(ConstraintCodes.LOCK_RESOURCE);
        task.setLockedResourceIds(List.of(RESOURCE_B));

        HardSoftScore score = score(task, RESOURCE_A);

        assertEquals(-1, score.hardScore(), "主资源不在 lockedResourceIds 中应记 1 个硬违规");
    }

    @Test
    void missingOrNoneLockFieldsDoNotPenalize() {
        TaskPlan timeWindowWithoutBounds = task(RESOURCE_A, BASE, 60L);
        timeWindowWithoutBounds.setLockType(ConstraintCodes.LOCK_TIME_WINDOW);

        TaskPlan resourceWithoutIds = task(RESOURCE_A, BASE, 60L);
        resourceWithoutIds.setLockType(ConstraintCodes.LOCK_RESOURCE);

        TaskPlan noneWithBounds = task(RESOURCE_A, BASE, 60L);
        noneWithBounds.setLockType(ConstraintCodes.LOCK_NONE);
        noneWithBounds.setLockedFrom(BASE.plusHours(1));
        noneWithBounds.setLockedTo(BASE.plusHours(2));
        noneWithBounds.setLockedResourceIds(List.of(RESOURCE_B));

        assertEquals(0, score(timeWindowWithoutBounds, RESOURCE_A).hardScore());
        assertEquals(0, score(resourceWithoutIds, RESOURCE_A).hardScore());
        assertEquals(0, score(noneWithBounds, RESOURCE_A).hardScore());
    }

    @Test
    void frozenLockTypeHonorsPinnedAndDoesNotStackOtherLocks() {
        TaskPlan consistent = frozenTask(RESOURCE_A, BASE, 60L, RESOURCE_A, BASE);
        consistent.setLockType(ConstraintCodes.LOCK_FROZEN);
        consistent.setLockedFrom(BASE.plusHours(1));
        consistent.setLockedTo(BASE.plusHours(2));
        consistent.setLockedResourceIds(List.of(RESOURCE_B));

        TaskPlan violated = frozenTask(RESOURCE_A, BASE, 60L, RESOURCE_A, BASE.minusHours(1));
        violated.setLockType(ConstraintCodes.LOCK_FROZEN);
        violated.setLockedFrom(BASE.plusHours(1));
        violated.setLockedTo(BASE.plusHours(2));
        violated.setLockedResourceIds(List.of(RESOURCE_B));

        assertEquals(0, score(consistent, RESOURCE_A).hardScore(),
                "FROZEN 且 pinned 未被移动时不惩罚，残留的窗/资源锁字段不叠加");
        assertEquals(-1, score(violated, RESOURCE_A).hardScore(),
                "FROZEN 下只按 pinnedStartTime 记 1 个硬违规，不叠加 TIME_WINDOW");
        assertTrue(score(violated, RESOURCE_A).hardScore() < 0);
    }

    @Test
    void explicitLockTypeTakesPrecedenceOverLegacyFrozenBoolean() {
        TaskPlan explicitWindow = frozenTask(RESOURCE_A, BASE, 60L, RESOURCE_A, BASE.minusHours(1));
        explicitWindow.setLockType(ConstraintCodes.LOCK_TIME_WINDOW);
        explicitWindow.setLockedFrom(BASE.minusHours(1));
        explicitWindow.setLockedTo(BASE.plusHours(1));

        TaskPlan legacyFrozenOnly = frozenTask(RESOURCE_A, BASE, 60L, RESOURCE_A, BASE.minusHours(1));

        assertEquals(0, score(explicitWindow, RESOURCE_A).hardScore(),
                "C21 优先级 lockType > frozen:boolean：时间窗满足时不按 pinned 判罚");
        assertEquals(-1, score(legacyFrozenOnly, RESOURCE_A).hardScore(),
                "lockType 缺省时 frozen:boolean 等价 FROZEN，pinned 被移动记 1 个硬违规");
    }

    private static TaskPlan task(UUID primaryResourceId, LocalDateTime start, long runSeconds) {
        TaskPlan task = new TaskPlan(UUID.randomUUID(), "MO-LOCK", 1, "CUT", null,
                primaryResourceId, List.of(), BigDecimal.ONE, 0L, runSeconds,
                false, null, null);
        task.setStartTime(start);
        return task;
    }

    private static TaskPlan frozenTask(UUID primaryResourceId, LocalDateTime start, long runSeconds,
                                       UUID pinnedResourceId, LocalDateTime pinnedStartTime) {
        TaskPlan task = new TaskPlan(UUID.randomUUID(), "MO-LOCK", 1, "CUT", null,
                primaryResourceId, List.of(), BigDecimal.ONE, 0L, runSeconds,
                true, pinnedResourceId, pinnedStartTime);
        task.setStartTime(start);
        return task;
    }

    private static HardSoftScore score(TaskPlan task, UUID resourceId) {
        SchedulingResource source = new SchedulingResource(resourceId, "R-" + resourceId, "MACHINE",
                null, null, null, null, BigDecimal.ONE, null, "ACTIVE", List.of());
        ResourcePlan resourcePlan = new ResourcePlan(source);
        resourcePlan.setTasks(new ArrayList<>(List.of(task)));
        SchedulePlan plan = new SchedulePlan(
                new ArrayList<>(List.of(task.getStartTime())),
                List.of(resourcePlan),
                List.of(task),
                List.of());
        return SOLUTION_MANAGER.update(plan);
    }
}
