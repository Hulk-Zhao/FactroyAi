import http, { idempotencyHeaders } from './http'
import type {
  PlanAction,
  PlanCompareResult,
  PlanDetail,
  PlanListParams,
  PlanListItem,
  PlanPublishRequest,
  PlanRollbackRequest
} from '@/types/plans'
import type { PageResult } from '@/types/common'

/**
 * 计划版本 API（C24）。
 *
 * <p>命名避开 `api/gantt.ts` 已有的 `publishPlan/rollbackPlan`：`api/index.ts` 采用 `export *`，
 * 同名导出会冲突。</p>
 */

/** 查询版本详情：返回元信息与发布那一刻的不可变快照任务。 */
export async function getPlanVersionDetail(planId: string): Promise<PlanDetail> {
  const response = await http.get<PlanDetail>(`/plans/${encodeURIComponent(planId)}`)
  return response.data
}

/**
 * 分页查询场景内的计划版本（C24 §24.6 第 8 条）。
 *
 * <p>排序固定 `published_at DESC, id DESC`（PG 默认 NULLS FIRST，DRAFT/REVIEW 在最前），
 * 不提供 sort 参数，前端也不二次排序。</p>
 */
export async function listPlanVersions(params: PlanListParams): Promise<PageResult<PlanListItem>> {
  const response = await http.get<PageResult<PlanListItem>>('/plans', { params })
  return response.data
}

/**
 * 发布版本。
 *
 * <p>approver 只在非空白时进入请求体；空白表示“未传”，后端落 NULL，
 * 不得由前端补成当前登录用户。</p>
 */
export async function publishPlanVersion(
  planId: string,
  payload: PlanPublishRequest = {},
  idempotencyKey?: string
): Promise<PlanAction> {
  const body: PlanPublishRequest = {}
  const reason = payload.reason?.trim()
  if (reason) {
    body.reason = reason
  }
  const approver = payload.approver?.trim()
  if (approver) {
    body.approver = approver
  }
  const response = await http.post<PlanAction>(
    `/plans/${encodeURIComponent(planId)}/publish`,
    body,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

/** 回滚到指定版本号：后端新建版本，不物理回退。 */
export async function rollbackPlanVersion(
  planId: string,
  payload: PlanRollbackRequest,
  idempotencyKey?: string
): Promise<PlanAction> {
  const body: PlanRollbackRequest = { targetVersionNo: payload.targetVersionNo }
  const reason = payload.reason?.trim()
  if (reason) {
    body.reason = reason
  }
  const response = await http.post<PlanAction>(
    `/plans/${encodeURIComponent(planId)}/rollback`,
    body,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

/**
 * C35 `POST /plans/compare`：2–3 个 planId，去重后再判个数。
 * 响应 items 已按后端排序规则排好，recommendedPlanId + tieBreak 直接展示。
 */
export async function comparePlans(planIds: string[]): Promise<PlanCompareResult> {
  const response = await http.post<PlanCompareResult>('/plans/compare', { planIds })
  return response.data
}
