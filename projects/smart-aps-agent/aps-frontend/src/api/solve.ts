import { Client, type IMessage } from '@stomp/stompjs'

import http, { idempotencyHeaders } from './http'
import type {
  AgentEventRequest,
  ExplainResult,
  NlToDslRequest,
  NlToDslResult,
  SolveProgress,
  SolveSubmitRequest,
  SolveTaskDetail,
  WhatIfRequest,
  WhatIfResult
} from '@/types/solve'

/** C20：POST /solve-tasks 返回 200 + SolveTaskDetail（异步求解）。 */
export async function submitSolveTask(
  payload: SolveSubmitRequest,
  idempotencyKey?: string
): Promise<SolveTaskDetail> {
  const response = await http.post<SolveTaskDetail>('/solve-tasks', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function getSolveTask(taskId: string | number): Promise<SolveTaskDetail> {
  const response = await http.get<SolveTaskDetail>(`/solve-tasks/${encodeURIComponent(taskId)}`)
  return response.data
}

export async function cancelSolveTask(
  taskId: string | number,
  idempotencyKey?: string
): Promise<SolveTaskDetail> {
  const response = await http.post<SolveTaskDetail>(
    `/solve-tasks/${encodeURIComponent(taskId)}/cancel`,
    null,
    { headers: idempotencyHeaders(idempotencyKey) }
  )
  return response.data
}

/** STOMP 通道状态；DISABLED 表示 Mock 模式无 WS 服务端，由页面轮询兜底。 */
export type SolveProgressChannelState = 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' | 'DISABLED'

export interface SolveProgressHandlers {
  onMessage: (progress: SolveProgress) => void
  onStateChange?: (state: SolveProgressChannelState) => void
  onError?: (message: string) => void
}

export interface SolveProgressClient {
  disconnect: () => void
}

function isMockMode(): boolean {
  return import.meta.env.VITE_ENABLE_MOCK === 'true'
}

/** WS 地址：优先 VITE_WS_BASE_URL（网关/独立端口），否则同源 /ws。 */
export function resolveSolveWsUrl(taskId: string | number): string {
  const base = (import.meta.env.VITE_WS_BASE_URL as string | undefined)?.trim()
  if (base) {
    return `${base.replace(/\/+$/, '')}/ws/solve-tasks/${encodeURIComponent(taskId)}`
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/solve-tasks/${encodeURIComponent(taskId)}`
}

/**
 * 订阅 C20 进度：连接 `/ws/solve-tasks/{id}`，订阅 `/topic/solve-tasks/{id}`。
 * STOMP 只推送、不替代查询；断线/连接中由调用方按 2 秒轮询 GET /solve-tasks/{id}。
 */
export function createSolveProgressClient(
  taskId: string | number,
  handlers: SolveProgressHandlers
): SolveProgressClient {
  if (isMockMode()) {
    handlers.onStateChange?.('DISABLED')
    return { disconnect: () => undefined }
  }

  const client = new Client({
    brokerURL: resolveSolveWsUrl(taskId),
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: () => undefined
  })
  const topic = `/topic/solve-tasks/${taskId}`
  let closed = false

  handlers.onStateChange?.('CONNECTING')

  client.onConnect = () => {
    handlers.onStateChange?.('CONNECTED')
    client.subscribe(topic, (frame: IMessage) => {
      try {
        const message = JSON.parse(frame.body) as SolveProgress
        if (Number(message.taskId) !== Number(taskId)) {
          return
        }
        handlers.onMessage(message)
      } catch {
        handlers.onError?.('STOMP 进度消息解析失败')
      }
    })
  }
  client.onWebSocketClose = () => {
    if (!closed) {
      handlers.onStateChange?.('DISCONNECTED')
    }
  }
  client.onWebSocketError = () => {
    if (!closed) {
      handlers.onStateChange?.('DISCONNECTED')
    }
  }
  client.onStompError = (frame) => {
    handlers.onError?.(frame.headers['message'] ?? 'STOMP 通道异常')
    if (!closed) {
      handlers.onStateChange?.('DISCONNECTED')
    }
  }

  client.activate()

  return {
    disconnect: () => {
      closed = true
      void client.deactivate()
    }
  }
}

export async function reportAgentEvent(
  payload: AgentEventRequest,
  idempotencyKey?: string
): Promise<void> {
  await http.post('/agent/events', payload, { headers: idempotencyHeaders(idempotencyKey) })
}

export async function nlToDsl(
  payload: NlToDslRequest,
  idempotencyKey?: string
): Promise<NlToDslResult> {
  const response = await http.post<NlToDslResult>('/agent/nl-to-dsl', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}

export async function explainTask(taskId: string | number): Promise<ExplainResult> {
  const response = await http.get<ExplainResult>(`/agent/explain/${encodeURIComponent(taskId)}`)
  return response.data
}

export async function whatIf(
  payload: WhatIfRequest,
  idempotencyKey?: string
): Promise<WhatIfResult> {
  const response = await http.post<WhatIfResult>('/agent/whatif', payload, {
    headers: idempotencyHeaders(idempotencyKey)
  })
  return response.data
}
