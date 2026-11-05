import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  createSolveTracker,
  isTerminalSolveStatus
} from '../../aps-frontend/src/api/solveTracker.ts'

function createHarness({ snapshot, snapshotError = false }) {
  const events = {
    detail: [],
    progress: [],
    terminal: [],
    channelState: [],
    warning: [],
    intervalStarted: 0,
    intervalCleared: 0,
    disconnects: 0
  }

  const scheduler = {
    active: new Map(),
    nextId: 0,
    setInterval(callback) {
      const id = ++scheduler.nextId
      scheduler.active.set(id, callback)
      events.intervalStarted += 1
      return id
    },
    clearInterval(id) {
      scheduler.active.delete(id)
      events.intervalCleared += 1
    }
  }

  let handlers = null
  let detailCalls = 0
  const dependencies = {
    scheduler,
    pollIntervalMs: 2000,
    async getDetail(taskId) {
      detailCalls += 1
      if (snapshotError) {
        throw new Error('snapshot unavailable')
      }
      return snapshot
    },
    subscribe(taskId, nextHandlers) {
      handlers = nextHandlers
      return {
        disconnect() {
          events.disconnects += 1
        }
      }
    }
  }

  const tracker = createSolveTracker({
    onDetail: (next) => events.detail.push(next),
    onProgress: (next) => events.progress.push(next),
    onTerminal: (next) => events.terminal.push(next),
    onChannelState: (state) => events.channelState.push(state),
    onWarning: (message) => events.warning.push(message)
  }, dependencies)

  return { tracker, events, scheduler, handlers: () => handlers, detailCalls: () => detailCalls }
}

const submittedTask = {
  id: 42,
  sandboxId: 'a2a2a2a2-0000-4000-8000-000000000001',
  taskNo: 'SOLVE-1',
  engine: 'TIMEFOLD',
  status: 'PENDING',
  progress: 0,
  phase: 'QUEUED',
  bestScore: null,
  resultTaskId: null,
  errorCode: null,
  message: null,
  elapsedMs: 0,
  degraded: false,
  metrics: {},
  createdAt: '2026-11-04T00:00:00Z',
  startedAt: null,
  finishedAt: null,
  updatedAt: null
}

const terminalSnapshot = {
  ...submittedTask,
  status: 'FAILED',
  phase: 'FAILED',
  errorCode: 'ENGINE_ERROR',
  message: '引擎不可用',
  degraded: true,
  finishedAt: '2026-11-04T00:00:05Z'
}

test('零 STOMP 帧时，CONNECTED 后的快照让页面直接进入终态并停止轮询', async () => {
  const harness = createHarness({ snapshot: terminalSnapshot })
  harness.tracker.start(submittedTask)

  // 只建立连接，一个业务帧都不推
  harness.handlers().onStateChange('CONNECTED')
  await new Promise((resolve) => setTimeout(resolve, 50))

  assert.equal(harness.events.progress.length, 0, '本用例不应收到任何 STOMP 帧')
  const latest = harness.events.detail.at(-1)
  assert.equal(latest.status, 'FAILED')
  assert.equal(latest.phase, 'FAILED', '终态阶段必须来自快照，而非旧帧')
  assert.equal(harness.events.terminal.length, 1, '快照命中终态必须触发终态回调')
  assert.equal(harness.scheduler.active.size, 0, '命中终态后必须停止轮询')
  assert.equal(harness.events.disconnects, 1, '终态后应断开订阅')
})

test('快照为 RUNNING 时停掉轮询，但继续接收推送帧', async () => {
  const runningSnapshot = { ...submittedTask, status: 'RUNNING', phase: 'SOLVING', progress: 0.4 }
  const harness = createHarness({ snapshot: runningSnapshot })
  harness.tracker.start(submittedTask)
  harness.handlers().onStateChange('CONNECTED')
  await new Promise((resolve) => setTimeout(resolve, 50))

  assert.equal(harness.events.detail.at(-1).status, 'RUNNING')
  assert.equal(harness.scheduler.active.size, 0, '快照成功后无需持续轮询')
  assert.equal(harness.events.terminal.length, 0)

  harness.handlers().onMessage({ taskId: 42, status: 'RUNNING', progress: 0.6, phase: 'SOLVING' })
  assert.equal(harness.events.progress.length, 1, '推送仍是主路径')
})

test('快照失败时保留 2 秒轮询兜底（不能因为连上就停轮询）', async () => {
  const harness = createHarness({ snapshotError: true })
  harness.tracker.start(submittedTask)
  harness.handlers().onStateChange('CONNECTED')
  await new Promise((resolve) => setTimeout(resolve, 50))

  assert.equal(harness.events.progress.length, 0)
  assert.equal(harness.events.terminal.length, 0)
  assert.equal(harness.scheduler.active.size, 1, '快照失败后轮询必须继续，避免页面永久空白')
  assert.ok(harness.detailCalls() >= 1)
})

test('终态判定只认四个终态', () => {
  for (const status of ['SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT']) {
    assert.equal(isTerminalSolveStatus(status), true, status)
  }
  for (const status of ['PENDING', 'RUNNING', null, undefined, '']) {
    assert.equal(isTerminalSolveStatus(status), false, String(status))
  }
})
