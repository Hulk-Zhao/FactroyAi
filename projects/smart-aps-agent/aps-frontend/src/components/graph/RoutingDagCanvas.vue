<script setup lang="ts">
/**
 * 工艺路线 DAG 画布（Vue Flow）。
 * 选型理由：节点拖拽、连线交互、自定义节点渲染均为内置能力，适合“编辑器”而非只读图表；
 * 相比 ECharts graph 需要自行实现拖拽/连线命中，开发成本更低且可访问性更好。
 */
import { ref, watch } from 'vue'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type {
  Connection,
  Edge,
  EdgeMouseEvent,
  Node,
  NodeDragEvent,
  NodeMouseEvent
} from '@vue-flow/core'
import { Handle, Position } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

import type { RoutingEdge, RoutingNode, RoutingNodePosition } from '@/types/routings'

const props = withDefaults(
  defineProps<{
    nodes: RoutingNode[]
    edges: RoutingEdge[]
    readonly?: boolean
    selectedNodeId?: string | null
    selectedEdgeId?: string | null
    issueNodeIds?: string[]
    issueEdgeIds?: string[]
  }>(),
  {
    readonly: false,
    selectedNodeId: null,
    selectedEdgeId: null,
    issueNodeIds: () => [],
    issueEdgeIds: () => []
  }
)

const emit = defineEmits<{
  'node-select': [id: string]
  'edge-select': [id: string]
  connect: [payload: { fromSeqNo: number; toSeqNo: number }]
  'position-change': [payload: { id: string; position: RoutingNodePosition }]
  'canvas-click': []
}>()

const flowNodes = ref<Node[]>([])
const flowEdges = ref<Edge[]>([])
const { fitView } = useVueFlow()

const tokenCache = new Map<string, string>()

/** 设计令牌 → 具体色值：Vue Flow 的 SVG 标签样式需要实色，统一走 :root 解析。 */
function tokenColor(name: string): string {
  let value = tokenCache.get(name)
  if (value === undefined) {
    value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
    tokenCache.set(name, value)
  }
  return value
}

function toFlowNode(node: RoutingNode): Node {
  return {
    id: node.id,
    type: 'routing',
    position: { x: node.position.x, y: node.position.y },
    data: node,
    class: [
      props.selectedNodeId === node.id ? 'flow-node-selected' : '',
      props.issueNodeIds.includes(node.id) ? 'flow-node-invalid' : ''
    ].join(' ')
  }
}

function toFlowEdge(edge: RoutingEdge): Edge {
  const source = props.nodes.find((node) => node.seqNo === edge.fromSeqNo)
  const target = props.nodes.find((node) => node.seqNo === edge.toSeqNo)
  return {
    id: edge.id,
    source: source?.id ?? `missing-from-${edge.fromSeqNo}`,
    target: target?.id ?? `missing-to-${edge.toSeqNo}`,
    type: 'smoothstep',
    markerEnd: MarkerType.ArrowClosed,
    label: edge.lagMinutes > 0 ? `${edge.dependType} +${edge.lagMinutes}min` : edge.dependType,
    labelStyle: {
      fontSize: '12px',
      fill: tokenColor('--text-2'),
      fontWeight: 600
    },
    labelBgStyle: {
      fill: tokenColor('--bg-soft'),
      stroke: tokenColor('--border')
    },
    labelBgPadding: [6, 3],
    labelBgBorderRadius: 4,
    class: [
      props.selectedEdgeId === edge.id ? 'flow-edge-selected' : '',
      props.issueEdgeIds.includes(edge.id) ? 'flow-edge-invalid' : ''
    ].join(' ')
  }
}

watch(
  () => props.nodes,
  (nodes) => {
    flowNodes.value = nodes.map(toFlowNode)
    flowEdges.value = props.edges.map(toFlowEdge)
  },
  { deep: true, immediate: true }
)

watch(
  () => props.edges,
  (edges) => {
    flowEdges.value = edges.map(toFlowEdge)
  },
  { deep: true, immediate: true }
)

watch(
  () => props.selectedNodeId,
  () => {
    flowNodes.value = props.nodes.map(toFlowNode)
  }
)

watch(
  () => props.selectedEdgeId,
  () => {
    flowEdges.value = props.edges.map(toFlowEdge)
  }
)

function onNodeClick(event: NodeMouseEvent) {
  emit('node-select', event.node.id)
}

function onEdgeClick(event: EdgeMouseEvent) {
  emit('edge-select', event.edge.id)
}

function onConnect(connection: Connection) {
  if (!connection.source || !connection.target) {
    return
  }
  const from = props.nodes.find((node) => node.id === connection.source)
  const to = props.nodes.find((node) => node.id === connection.target)
  if (!from || !to) {
    return
  }
  emit('connect', { fromSeqNo: from.seqNo, toSeqNo: to.seqNo })
}

function onNodeDragStop(event: NodeDragEvent) {
  emit('position-change', {
    id: event.node.id,
    position: { x: Math.round(event.node.position.x), y: Math.round(event.node.position.y) }
  })
}

function fit() {
  void fitView({ padding: 0.2, duration: 300 })
}

defineExpose({ fit })

function hasIssue(id: string): boolean {
  return props.issueNodeIds.includes(id)
}
</script>

<template>
  <div class="dag-canvas">
    <VueFlow
      v-model:nodes="flowNodes"
      v-model:edges="flowEdges"
      :nodes-draggable="!readonly"
      :nodes-connectable="!readonly"
      :elements-selectable="true"
      :min-zoom="0.3"
      :max-zoom="1.5"
      fit-view-on-init
      @node-click="onNodeClick"
      @edge-click="onEdgeClick"
      @connect="onConnect"
      @node-drag-stop="onNodeDragStop"
      @pane-click="emit('canvas-click')"
    >
      <template #node-routing="{ data }">
        <div
          class="routing-node"
          :class="{
            'is-selected': selectedNodeId === data.id,
            'is-invalid': hasIssue(data.id),
            'is-readonly': readonly
          }"
        >
          <Handle type="target" :position="Position.Left" />
          <div class="node-seq">{{ data.seqNo }}</div>
          <div class="node-main">
            <div class="node-title">
              {{ data.operationName || data.operationCode || '未命名工序' }}
            </div>
            <div class="node-meta">{{ data.operationCode || '—' }}</div>
            <div class="node-meta">
              标准 {{ data.standardSeconds }}s · 换型 {{ data.setupSeconds }}s
            </div>
            <div class="node-tags">
              <span class="node-tag">{{ data.primaryResourceCode || '未分配主资源' }}</span>
              <span v-if="data.secondaryResourceIds?.length" class="node-tag">
                +{{ data.secondaryResourceIds.length }} 辅资源
              </span>
              <span v-if="data.optional" class="node-tag node-tag-optional">可选</span>
              <span v-if="!data.operationTemplateId" class="node-tag node-tag-error">缺模版</span>
            </div>
          </div>
          <Handle type="source" :position="Position.Right" />
        </div>
      </template>
    </VueFlow>

    <div v-if="nodes.length === 0" class="canvas-empty">
      <p>尚未添加工序节点</p>
      <p class="canvas-empty-tip">点击工具栏「添加节点」开始设计 DAG</p>
    </div>

    <div class="canvas-toolbar">
      <el-button size="small" @click="fit">适应画布</el-button>
    </div>
  </div>
</template>

<style scoped>
.dag-canvas {
  position: relative;
  height: 560px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--bg-soft);
  overflow: hidden;
}

.canvas-toolbar {
  position: absolute;
  right: 12px;
  bottom: 12px;
  z-index: 2;
}

.canvas-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  pointer-events: none;
}

.canvas-empty-tip {
  font-size: 12px;
  margin-top: 4px;
}

.routing-node {
  display: flex;
  align-items: stretch;
  min-width: 210px;
  max-width: 240px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
  box-shadow: var(--shadow-sm);
}

.routing-node.is-selected {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 18%, transparent);
}

.routing-node.is-invalid {
  border-color: var(--el-color-danger);
}

.routing-node.is-readonly {
  background: var(--bg-soft);
}

.node-seq {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  background: var(--border-strong);
  color: var(--text);
  font-weight: 700;
  font-size: 13px;
}

.node-main {
  padding: 8px 10px;
  flex: 1;
  min-width: 0;
}

.node-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.node-tag {
  padding: 0 4px;
  border-radius: 3px;
  font-size: 10px;
  line-height: 16px;
  background: var(--bg-soft);
  color: var(--text-2);
}

.node-tag-optional {
  background: var(--ok-bg);
  color: var(--ok);
}

.node-tag-error {
  background: var(--danger-bg);
  color: var(--danger);
}

:deep(.flow-node-invalid .routing-node) {
  border-color: var(--el-color-danger);
}

:deep(.flow-edge-invalid .vue-flow__edge-path) {
  stroke: var(--el-color-danger);
  stroke-width: 2;
}
</style>
