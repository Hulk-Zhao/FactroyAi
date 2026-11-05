/**
 * 计划版本类型（M2-FE-07 / 契约 C24）。
 *
 * <p>字段来源：`PlanVersionService` 的 `PlanActionView` / `PlanDetailView` 与
 * `docs/api/openapi/gantt.yaml` 的 `PlanAction`。OpenAPI 的 `PlanAction` 只列了部分字段，
 * 后端实际响应还包含 `sandboxId/taskCount/approver/reason/rollbackOf`，页面的详情面板需要这些字段，
 * 因此在此按真实响应建模（未新增任何契约外字段）。</p>
 */
export type PlanStatus = 'DRAFT' | 'REVIEW' | 'PUBLISHED' | 'ARCHIVED'

/** 发布/回滚响应（C24 PlanAction）。 */
export interface PlanAction {
  planId: string
  planNo?: string | null
  version: number
  targetVersionNo?: number | null
  status: PlanStatus
  publishedAt?: string | null
  /** 发布为回填任务数，回滚为重指向任务数，重复发布为 0。 */
  affectedTaskCount: number
  sandboxId?: string
  /** 发布那一刻固化的快照任务数。 */
  taskCount?: number
  /** 审批人；未传/空白时为 null（后端不会回退成当前登录用户）。 */
  approver?: string | null
  reason?: string | null
  rollbackOf?: string | null
}

/** 快照任务行：来自 plan_version.snapshot.tasks，不是当前 task 表。 */
export interface PlanSnapshotTask {
  taskId: string
  orderNo: string | null
  operationIndex: number | null
  status: string
  startTime: string | null
  endTime: string | null
  resourceId: string | null
  qty: number | null
}

/** 版本详情（GET /plans/{id}）。 */
export interface PlanDetail {
  action: PlanAction
  tasks: PlanSnapshotTask[]
}

/**
 * 版本列表行（GET /plans 的 items[]，C24 §24.6 第 8 条）。
 * 只有 9 个字段，不含 tasks[]/snapshot；明细走 GET /plans/{id}。
 */
export interface PlanListItem {
  planId: string
  planNo: string
  /** 业务版本号 version_no，不是乐观锁列 plan_version.version。 */
  version: number
  status: PlanStatus
  publishedAt: string | null
  /** 发布操作人，与 approver 是两个独立字段，不得互相顶替。 */
  publishedBy: string | null
  /** 审批人；未填时为 null，页面显示为「—」。 */
  approver: string | null
  taskCount: number
  rollbackOf: string | null
}

/** 列表查询参数；后端不提供 sort，排序固定 published_at DESC, id DESC。 */
export interface PlanListParams {
  sandboxId: string
  page?: number
  size?: number
}

/** 发布请求；approver 仅在非空白时发送，空白保持“未传”语义。 */
export interface PlanPublishRequest {
  reason?: string
  approver?: string
}

/** 回滚请求。 */
export interface PlanRollbackRequest {
  targetVersionNo: number
  reason?: string
}

/** C35 `POST /plans/compare` 单项；metrics 为 solve_task.metrics 原样透传。 */
export interface PlanCompareItem {
  planId: string
  planNo: string
  version: number
  scoreHard: number | null
  scoreSoft: number | null
  taskCount: number
  /** 不重命名、不裁剪键：页面原样展示，不做美化重排。 */
  metrics: Record<string, unknown>
}

/** C35 择优响应：items 已按 SCORE_HARD_DESC,SCORE_SOFT_DESC,PLAN_ID_ASC 排序。 */
export interface PlanCompareResult {
  items: PlanCompareItem[]
  recommendedPlanId: string | null
  tieBreak: string
}
