package com.hengce.aps.solver.timefold.model;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.variable.PlanningListVariable;
import com.hengce.aps.solver.api.SchedulingResource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Timefold 计划资源：以 List Variable 持有资源上的任务序列。
 */
@PlanningEntity
public class ResourcePlan {

    private SchedulingResource source;

    @PlanningListVariable(valueRangeProviderRefs = "taskRange")
    private List<TaskPlan> tasks = new ArrayList<>();

    /**
     * Timefold 克隆所需的无参构造。
     */
    public ResourcePlan() {
    }

    /**
     * 构造资源计划。
     *
     * @param source 资源快照
     */
    public ResourcePlan(SchedulingResource source) {
        this.source = source;
    }

    /**
     * 资源主键。
     *
     * @return 资源主键
     */
    public UUID getResourceId() {
        return source == null ? null : source.resourceId();
    }

    /**
     * 资源编码。
     *
     * @return 资源编码
     */
    public String getCode() {
        return source == null ? null : source.code();
    }

    public SchedulingResource getSource() {
        return source;
    }

    public void setSource(SchedulingResource source) {
        this.source = source;
    }

    public List<TaskPlan> getTasks() {
        return tasks;
    }

    public void setTasks(List<TaskPlan> tasks) {
        this.tasks = tasks == null ? new ArrayList<>() : tasks;
    }
}
