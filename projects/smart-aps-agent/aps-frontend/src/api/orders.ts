import http, { idempotencyHeaders, withPageQuery } from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type {
  AtpRequest,
  AtpResult,
  Order,
  OrderCreateRequest,
  OrderFreezeRequest,
  OrderQuery,
  OrderUpdateRequest,
  Sandbox,
  SandboxApplyResult,
  SandboxCompare,
  SandboxCreateRequest,
  Strategy,
  StrategyCreateRequest,
  StrategyDetail,
  StrategyUpdateRequest
} from '@/types/orders'

export async function listOrders(query: PageQuery & OrderQuery = {}): Promise<PageResult<Order>> {
  const response = await http.get<PageResult<Order>>('/orders', {
    params: withPageQuery(query, {
      orderNo: query.orderNo,
      customerId: query.customerId,
      materialCode: query.materialCode,
      status: query.status,
      dueDateFrom: query.dueDateFrom,
      dueDateTo: query.dueDateTo
    })
  })
  return response.data
}

export async function createOrder(
  payload: OrderCreateRequest,
  idempotencyKey?: string
): Promise<Order> {
  const response = await http.post<Order>('/orders', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function getOrder(orderNo: string): Promise<Order> {
  const response = await http.get<Order>(`/orders/${encodeURIComponent(orderNo)}`)
  return response.data
}

/** 更新订单：契约未定义该端点，Mock 先行；仅 DRAFT 可编辑。 */
export async function updateOrder(
  orderNo: string,
  payload: OrderUpdateRequest,
  idempotencyKey?: string
): Promise<Order> {
  const response = await http.put<Order>(`/orders/${encodeURIComponent(orderNo)}`, payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function confirmOrder(orderNo: string, idempotencyKey?: string): Promise<Order> {
  const response = await http.put<Order>(`/orders/${encodeURIComponent(orderNo)}/confirm`, null, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function freezeOrder(
  orderNo: string,
  payload: OrderFreezeRequest,
  idempotencyKey?: string
): Promise<Order> {
  const response = await http.put<Order>(`/orders/${encodeURIComponent(orderNo)}/freeze`, payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** 解冻：契约冻结版只有 freeze 端点，按 body.freezeStatus=UNFROZEN 收敛。 */
export async function unfreezeOrder(
  orderNo: string,
  version: number,
  idempotencyKey?: string
): Promise<Order> {
  return freezeOrder(orderNo, { freezeStatus: 'UNFROZEN', version }, idempotencyKey)
}

export async function queryAtp(payload: AtpRequest, idempotencyKey?: string): Promise<AtpResult> {
  const response = await http.post<AtpResult>('/orders/atp', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function listStrategies(query: PageQuery = {}): Promise<PageResult<Strategy>> {
  const response = await http.get<PageResult<Strategy>>('/strategies', {
    params: withPageQuery(query)
  })
  return response.data
}

export async function createStrategy(
  payload: StrategyCreateRequest,
  idempotencyKey?: string
): Promise<Strategy> {
  const response = await http.post<Strategy>('/strategies', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** 策略详情：C19 约定 rules 为结构化规则数组（strategy_rule 为准）。 */
export async function getStrategy(strategyId: string): Promise<StrategyDetail> {
  const response = await http.get<StrategyDetail>(`/strategies/${encodeURIComponent(strategyId)}`)
  return response.data
}

/** 更新策略：rules 全量替换，version 用于乐观锁（冲突返回 40901）。 */
export async function updateStrategy(
  strategyId: string,
  payload: StrategyUpdateRequest,
  idempotencyKey?: string
): Promise<StrategyDetail> {
  const response = await http.put<StrategyDetail>(
    `/strategies/${encodeURIComponent(strategyId)}`,
    payload,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

export async function cloneStrategy(
  strategyId: string,
  idempotencyKey?: string
): Promise<Strategy> {
  const response = await http.post<Strategy>(
    `/strategies/${encodeURIComponent(strategyId)}/clone`,
    null,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

export async function listSandboxes(
  query: PageQuery & { type?: string } = {}
): Promise<PageResult<Sandbox>> {
  const response = await http.get<PageResult<Sandbox>>('/sandboxes', {
    params: withPageQuery(query, { type: query.type })
  })
  return response.data
}

export async function createSandbox(
  payload: SandboxCreateRequest,
  idempotencyKey?: string
): Promise<Sandbox> {
  const response = await http.post<Sandbox>('/sandboxes', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

/** C32 `POST /sandboxes/{id}/apply`：Idempotency-Key 必传，reason 写入审计。 */
export async function applySandbox(
  sandboxId: string,
  reason: string,
  idempotencyKey: string
): Promise<SandboxApplyResult> {
  const response = await http.post<SandboxApplyResult>(
    `/sandboxes/${encodeURIComponent(sandboxId)}/apply`,
    { reason },
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

export async function compareSandbox(sandboxId: string, targetId: string): Promise<SandboxCompare> {
  const response = await http.get<SandboxCompare>(
    `/sandboxes/${encodeURIComponent(sandboxId)}/compare`,
    { params: { targetId } }
  )
  return response.data
}
