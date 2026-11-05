/**
 * 工艺路线 DAG 通用能力：前端就地校验与自动布局。
 * C15：边按 seqNo 表达（fromSeqNo/toSeqNo），节点资源使用 UUID。
 * 与服务端对齐：routing_edge 唯一键 (routing_id, from, to, depend_type)。
 */
import type { OperationTemplate, RoutingEdge, RoutingNode } from '@/types/routings'

export type IssueLevel = 'error' | 'warning'

export interface ValidationIssue {
  code:
    | 'empty'
    | 'cycle'
    | 'isolated'
    | 'duplicate-edge'
    | 'duplicate-seq'
    | 'missing-template'
    | 'invalid-edge'
    | 'self-loop'
  level: IssueLevel
  message: string
  nodeIds?: string[]
  edgeIds?: string[]
}

export function nodeLabel(node: RoutingNode): string {
  const name = node.operationName || node.operationCode || '未命名工序'
  return `${node.seqNo} · ${name}`
}

function nodeBySeqNo(nodes: RoutingNode[]): Map<number, RoutingNode> {
  return new Map(nodes.map((node) => [node.seqNo, node]))
}

/** DFS 找环，返回环上的 seqNo 路径（首尾重复）；无环返回 null。 */
export function findCycle(nodes: RoutingNode[], edges: RoutingEdge[]): number[] | null {
  const seqNos = new Set(nodes.map((node) => node.seqNo))
  const adjacency = new Map<number, number[]>()
  seqNos.forEach((seqNo) => adjacency.set(seqNo, []))
  edges.forEach((edge) => {
    if (seqNos.has(edge.fromSeqNo) && seqNos.has(edge.toSeqNo)) {
      adjacency.get(edge.fromSeqNo)?.push(edge.toSeqNo)
    }
  })

  const visiting = new Set<number>()
  const visited = new Set<number>()
  const stack: number[] = []

  function dfs(seqNo: number): number[] | null {
    if (visiting.has(seqNo)) {
      const start = stack.indexOf(seqNo)
      return [...stack.slice(start), seqNo]
    }
    if (visited.has(seqNo)) {
      return null
    }
    visiting.add(seqNo)
    stack.push(seqNo)
    for (const next of adjacency.get(seqNo) ?? []) {
      const cycle = dfs(next)
      if (cycle) {
        return cycle
      }
    }
    stack.pop()
    visiting.delete(seqNo)
    visited.add(seqNo)
    return null
  }

  for (const seqNo of seqNos) {
    const cycle = dfs(seqNo)
    if (cycle) {
      return cycle
    }
  }
  return null
}

/** 供新增连线时即时判断是否会成环（按 seqNo）。 */
export function wouldCreateCycle(
  nodes: RoutingNode[],
  edges: RoutingEdge[],
  fromSeqNo: number,
  toSeqNo: number
): boolean {
  if (fromSeqNo === toSeqNo) {
    return true
  }
  const probe = [
    ...edges,
    { id: '__probe__', fromSeqNo, toSeqNo, dependType: 'FS' as const, lagMinutes: 0 }
  ]
  return findCycle(nodes, probe) !== null
}

/**
 * 保存前校验：空图、环、孤立节点、重复单向边、seq_no 冲突、缺模版、非法边。
 * error 阻断保存；warning 仅提示。
 */
export function validateRoutingGraph(
  nodes: RoutingNode[],
  edges: RoutingEdge[],
  templates: OperationTemplate[] = []
): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const bySeqNo = nodeBySeqNo(nodes)

  if (nodes.length === 0) {
    issues.push({ code: 'empty', level: 'error', message: '工艺路线至少需要一个工序节点' })
  }

  const cycle = findCycle(nodes, edges)
  if (cycle) {
    const labels = cycle.map((seqNo) => {
      const node = bySeqNo.get(seqNo)
      return node ? nodeLabel(node) : `seq_no=${seqNo}`
    })
    issues.push({
      code: 'cycle',
      level: 'error',
      message: `存在循环依赖：${labels.join(' → ')}`,
      nodeIds: cycle.flatMap((seqNo) => (bySeqNo.get(seqNo) ? [bySeqNo.get(seqNo)?.id ?? ''] : []))
    })
  }

  if (nodes.length > 1) {
    const incoming = new Set(edges.map((edge) => edge.toSeqNo))
    const outgoing = new Set(edges.map((edge) => edge.fromSeqNo))
    const isolated = nodes.filter((node) => !incoming.has(node.seqNo) && !outgoing.has(node.seqNo))
    if (isolated.length > 0) {
      issues.push({
        code: 'isolated',
        level: 'error',
        message: `存在孤立工序（无前序也无后续）：${isolated.map((node) => nodeLabel(node)).join('、')}`,
        nodeIds: isolated.map((node) => node.id)
      })
    }
  }

  const edgeKeys = new Map<string, RoutingEdge[]>()
  edges.forEach((edge) => {
    const key = `${edge.fromSeqNo}->${edge.toSeqNo}:${edge.dependType}`
    edgeKeys.set(key, [...(edgeKeys.get(key) ?? []), edge])
  })
  const duplicateEdges: RoutingEdge[] = []
  edgeKeys.forEach((group) => {
    if (group.length > 1) {
      duplicateEdges.push(...group)
    }
  })
  if (duplicateEdges.length > 0) {
    issues.push({
      code: 'duplicate-edge',
      level: 'error',
      message: `存在重复单向边（同一 from/to 与依赖类型）：${duplicateEdges
        .map((edge) => `${edge.fromSeqNo} → ${edge.toSeqNo}（${edge.dependType}）`)
        .join('；')}`,
      edgeIds: duplicateEdges.map((edge) => edge.id)
    })
  }

  const seqGroups = new Map<number, RoutingNode[]>()
  nodes.forEach((node) => {
    seqGroups.set(node.seqNo, [...(seqGroups.get(node.seqNo) ?? []), node])
  })
  const duplicateSeq = [...seqGroups.entries()].filter(([, group]) => group.length > 1)
  if (duplicateSeq.length > 0) {
    issues.push({
      code: 'duplicate-seq',
      level: 'error',
      message: `seq_no 冲突：${duplicateSeq
        .map(
          ([seqNo, group]) =>
            `seq_no=${seqNo}（${group.map((node) => node.operationName || node.id).join('、')}）`
        )
        .join('；')}`,
      nodeIds: duplicateSeq.flatMap(([, group]) => group.map((node) => node.id))
    })
  }

  const templateIds = new Set(templates.map((template) => template.id))
  const missingTemplate = nodes.filter(
    (node) =>
      !node.operationTemplateId ||
      (templates.length > 0 && !templateIds.has(node.operationTemplateId))
  )
  if (missingTemplate.length > 0) {
    issues.push({
      code: 'missing-template',
      level: 'error',
      message: `工序缺少有效模版：${missingTemplate.map((node) => nodeLabel(node)).join('、')}`,
      nodeIds: missingTemplate.map((node) => node.id)
    })
  }

  const invalidEdges = edges.filter((edge) => {
    if (!bySeqNo.has(edge.fromSeqNo) || !bySeqNo.has(edge.toSeqNo)) {
      return true
    }
    if (edge.fromSeqNo === edge.toSeqNo) {
      return true
    }
    return edge.lagMinutes < 0
  })
  if (invalidEdges.length > 0) {
    const selfLoop = invalidEdges.some((edge) => edge.fromSeqNo === edge.toSeqNo)
    issues.push({
      code: selfLoop ? 'self-loop' : 'invalid-edge',
      level: 'error',
      message: '存在非法连线：seqNo 不存在、自环或 lag_minutes 小于 0',
      edgeIds: invalidEdges.map((edge) => edge.id)
    })
  }

  return issues
}

/** 按 DAG 拓扑层级做自动布局；有环时退化为按 seq_no 顺序分列。 */
export function autoLayoutNodes(nodes: RoutingNode[], edges: RoutingEdge[]): RoutingNode[] {
  const seqNos = new Set(nodes.map((node) => node.seqNo))
  const adjacency = new Map<number, number[]>()
  const indegree = new Map<number, number>()
  seqNos.forEach((seqNo) => {
    adjacency.set(seqNo, [])
    indegree.set(seqNo, 0)
  })
  edges.forEach((edge) => {
    if (!seqNos.has(edge.fromSeqNo) || !seqNos.has(edge.toSeqNo)) {
      return
    }
    adjacency.get(edge.fromSeqNo)?.push(edge.toSeqNo)
    indegree.set(edge.toSeqNo, (indegree.get(edge.toSeqNo) ?? 0) + 1)
  })

  const level = new Map<number, number>()
  const queue: number[] = []
  nodes.forEach((node) => {
    if ((indegree.get(node.seqNo) ?? 0) === 0) {
      level.set(node.seqNo, 0)
      queue.push(node.seqNo)
    }
  })

  while (queue.length > 0) {
    const current = queue.shift() as number
    for (const next of adjacency.get(current) ?? []) {
      level.set(next, Math.max(level.get(next) ?? 0, (level.get(current) ?? 0) + 1))
      const remaining = (indegree.get(next) ?? 0) - 1
      indegree.set(next, remaining)
      if (remaining === 0) {
        queue.push(next)
      }
    }
  }

  const hasCycle = nodes.some((node) => !level.has(node.seqNo))
  const sorted = nodes
    .slice()
    .sort((left, right) => left.seqNo - right.seqNo || left.id.localeCompare(right.id))

  const groups = new Map<number, RoutingNode[]>()
  sorted.forEach((node, index) => {
    const column = hasCycle ? index : (level.get(node.seqNo) ?? 0)
    groups.set(column, [...(groups.get(column) ?? []), node])
  })

  return nodes.map((node) => {
    const column = hasCycle
      ? sorted.findIndex((item) => item.id === node.id)
      : (level.get(node.seqNo) ?? 0)
    const group = groups.get(column) ?? []
    const row = group.findIndex((item) => item.id === node.id)
    return {
      ...node,
      position: {
        x: 80 + column * 300,
        y: 80 + Math.max(row, 0) * 150
      }
    }
  })
}
