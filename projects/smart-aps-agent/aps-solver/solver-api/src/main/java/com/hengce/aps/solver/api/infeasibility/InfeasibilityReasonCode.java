package com.hengce.aps.solver.api.infeasibility;

/**
 * C51 阻塞任务原因码。
 */
public enum InfeasibilityReasonCode {
    UNASSIGNED,
    NO_ROUTING,
    NO_RESOURCE,
    RESOURCE_WINDOW_SHORT,
    FROZEN_CONFLICT,
    HORIZON_EXCEEDED,
    MATERIAL_MISSING,
    OTHER
}
