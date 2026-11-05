package com.hengce.aps.solver.timefold.model;

import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.HardSoftScore;
import com.hengce.aps.solver.timefold.dependency.TaskDependency;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Timefold 计划解：以资源为单位的 List Variable 承载任务序列。
 *
 * <p>{@code calendarFallback}/{@code dependencyFallback}/{@code setupMatrixFallback}
 * 记录 C12 §17.6 的降级事实，随求解结果写入 {@code SolveMetrics.extra}。</p>
 */
@PlanningSolution
public class SchedulePlan {

    @ValueRangeProvider(id = "startTimeRange")
    @ProblemFactCollectionProperty
    private List<LocalDateTime> startTimes = new ArrayList<>();

    @ProblemFactCollectionProperty
    private List<TaskDependency> dependencies = new ArrayList<>();

    @PlanningEntityCollectionProperty
    private List<ResourcePlan> resources = new ArrayList<>();

    @PlanningEntityCollectionProperty
    @ValueRangeProvider(id = "taskRange")
    private List<TaskPlan> tasks = new ArrayList<>();

    @PlanningScore
    private HardSoftScore score;

    private boolean calendarFallback;

    private boolean dependencyFallback;

    private boolean setupMatrixFallback;

    /**
     * 初始解是否来自既有排产结果（全部任务都已初始化）；为 {@code true} 时跳过构造启发式，
     * 直接进入局部搜索改进。
     */
    private boolean warmStarted;

    /**
     * Timefold 克隆所需的无参构造。
     */
    public SchedulePlan() {
    }

    /**
     * 构造计划解。
     *
     * @param startTimes 开始时间候选
     * @param resources 资源计划
     * @param tasks 任务计划
     * @param dependencies 依赖边
     */
    public SchedulePlan(
            List<LocalDateTime> startTimes,
            List<ResourcePlan> resources,
            List<TaskPlan> tasks,
            List<TaskDependency> dependencies) {
        this.startTimes = startTimes == null ? new ArrayList<>() : startTimes;
        this.resources = resources == null ? new ArrayList<>() : resources;
        this.tasks = tasks == null ? new ArrayList<>() : tasks;
        this.dependencies = dependencies == null ? new ArrayList<>() : dependencies;
    }

    /**
     * 兼容不含依赖边的构造，等价于「无依赖」。
     *
     * @param startTimes 开始时间候选
     * @param resources 资源计划
     * @param tasks 任务计划
     */
    public SchedulePlan(
            List<LocalDateTime> startTimes,
            List<ResourcePlan> resources,
            List<TaskPlan> tasks) {
        this(startTimes, resources, tasks, List.of());
    }

    public List<LocalDateTime> getStartTimes() {
        return startTimes;
    }

    public void setStartTimes(List<LocalDateTime> startTimes) {
        this.startTimes = startTimes == null ? new ArrayList<>() : startTimes;
    }

    public List<TaskDependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<TaskDependency> dependencies) {
        this.dependencies = dependencies == null ? new ArrayList<>() : dependencies;
    }

    public List<ResourcePlan> getResources() {
        return resources;
    }

    public void setResources(List<ResourcePlan> resources) {
        this.resources = resources == null ? new ArrayList<>() : resources;
    }

    public List<TaskPlan> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskPlan> tasks) {
        this.tasks = tasks == null ? new ArrayList<>() : tasks;
    }

    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }

    public boolean isCalendarFallback() {
        return calendarFallback;
    }

    public void setCalendarFallback(boolean calendarFallback) {
        this.calendarFallback = calendarFallback;
    }

    public boolean isDependencyFallback() {
        return dependencyFallback;
    }

    public void setDependencyFallback(boolean dependencyFallback) {
        this.dependencyFallback = dependencyFallback;
    }

    public boolean isSetupMatrixFallback() {
        return setupMatrixFallback;
    }

    public void setSetupMatrixFallback(boolean setupMatrixFallback) {
        this.setupMatrixFallback = setupMatrixFallback;
    }

    public boolean isWarmStarted() {
        return warmStarted;
    }

    public void setWarmStarted(boolean warmStarted) {
        this.warmStarted = warmStarted;
    }
}
