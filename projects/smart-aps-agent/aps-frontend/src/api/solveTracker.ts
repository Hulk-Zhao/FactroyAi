import type { SolveProgress, SolveTaskDetail, SolveTaskStatus } from '../types/solve'
import type { SolveProgressChannelState } from './solve'

/** 终态集合：GET 快照一旦落到这些状态，页面必须停止轮询。 */
export const TERMINAL_SOLVE_STATUSES: readonly SolveTaskStatus[] = [
  'SOLVED',
  'FAILED',
  'CANCELLED',
  'TIMEOUT'
]

export function isTerminalSolveStatus(status?: string | null): boolean {
  return status != null && TERMINAL_SOLVE_STATUSES.includes(status as SolveTaskStatus)
}

export interface SolveTrackerCallbacks {
  onDetail: (detail: SolveTaskDetail) => void
  onProgress: (progress: SolveProgress) => void
  /** 快照确认进入终态：丢弃早于终态的推送帧，避免页面停在旧阶段。 */
  onTerminal?: (detail: SolveTaskDetail) => void
  onChannelState?: (state: SolveProgressChannelState) => void
  onWarning?: (message: string) => void
}

export interface SolveSubscriptionHandlers {
  onMessage: (progress: SolveProgress) => void
  onStateChange?: (state: SolveProgressChannelState) => void
  onError?: (message: string) => void
}

export interface SolveTrackerDependencies {
  getDetail: (taskId: number) => Promise<SolveTaskDetail>
  subscribe: (taskId: number, handlers: SolveSubscriptionHandlers) => { disconnect: () => void }
  pollIntervalMs?: number
  scheduler?: {
    setInterval: (callback: () => void, ms: number) => number
    clearInterval: (id: number) => void
  }
}

export interface SolveTracker {
  start: (task: SolveTaskDetail) => void
  refreshNow: () => Promise<void>
  stop: () => void
}

const DEFAULT_POLL_INTERVAL_MS = 2000

/**
 * 进度订阅编排：STOMP 推送为主路径，`GET /solve-tasks/{id}` 为确定性兜底。
 *
 * <p>{@code CONNECTED} 之后**无条件**补一次快照（即使已经收到首帧），
 * 快照是事实源：命中终态立即停止轮询并断开订阅；快照失败则保留 2 秒轮询兜底，
 * 因此"零 STOMP 帧 + 任务已终态"也能进入终态展示。</p>
 */
export function createSolveTracker(
  callbacks: SolveTrackerCallbacks,
  dependencies: SolveTrackerDependencies
): SolveTracker {
  const pollIntervalMs = dependencies.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS
  const scheduler = dependencies.scheduler ?? {
    setInterval: (callback: () => void, ms: number) =>
      globalThis.setInterval(callback, ms) as unknown as number,
    clearInterval: (id: number) => globalThis.clearInterval(id as unknown as never)
  }

  let detail: SolveTaskDetail | null = null
  let subscription: { disconnect: () => void } | null = null
  let pollTimer: number | null = null
  let snapshotInFlight = false
  let stopped = false

  function startPolling() {
    if (pollTimer !== null || stopped) {
      return
    }
    pollTimer = scheduler.setInterval(() => {
      void fetchSnapshot()
    }, pollIntervalMs)
  }

  function stopPolling() {
    if (pollTimer === null) {
      return
    }
    scheduler.clearInterval(pollTimer)
    pollTimer = null
  }

  function stop() {
    if (stopped) {
      return
    }
    stopped = true
    stopPolling()
    subscription?.disconnect()
    subscription = null
  }

  /** @returns 快照是否成功取到（失败时轮询继续兜底） */
  async function fetchSnapshot(): Promise<boolean> {
    if (!detail || snapshotInFlight) {
      return false
    }
    snapshotInFlight = true
    try {
      const snapshot = await dependencies.getDetail(detail.id)
      if (stopped) {
        return true
      }
      detail = snapshot
      callbacks.onDetail(snapshot)
      if (isTerminalSolveStatus(snapshot.status)) {
        callbacks.onTerminal?.(snapshot)
        stop()
      }
      return true
    } catch {
      return false
    } finally {
      snapshotInFlight = false
    }
  }

  function handleChannelState(state: SolveProgressChannelState) {
    callbacks.onChannelState?.(state)
    if (state === 'CONNECTED') {
      // 连接建立后无条件补快照：不依赖是否已收到首帧，收到过也要取。
      void fetchSnapshot().then((snapshotFetched) => {
        if (snapshotFetched && !stopped) {
          stopPolling()
        }
      })
    } else {
      startPolling()
    }
  }

  return {
    start(task: SolveTaskDetail) {
      stopped = false
      detail = task
      callbacks.onDetail(task)
      startPolling()
      subscription = dependencies.subscribe(task.id, {
        onMessage: (progress) => {
          callbacks.onProgress(progress)
          if (isTerminalSolveStatus(progress.status)) {
            void fetchSnapshot()
          }
        },
        onStateChange: handleChannelState,
        onError: (message) => callbacks.onWarning?.(message)
      })
    },
    async refreshNow() {
      await fetchSnapshot()
    },
    stop
  }
}
