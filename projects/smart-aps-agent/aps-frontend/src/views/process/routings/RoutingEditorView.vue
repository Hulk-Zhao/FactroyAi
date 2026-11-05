<script setup lang="ts">
/**
 * 工艺路线 DAG 编辑器。
 * 范围：节点/边增删、属性编辑、自动布局、保存前就地校验、ACTIVE 只读。
 * C15：节点资源引用统一使用 UUID（primaryResourceId/secondaryResourceIds），
 * 边按 seqNo 表达（fromSeqNo/toSeqNo），保存使用 PUT /routings/{id}/graph 且 version 必填。
 * 后端未就绪时由契约 Mock（VITE_ENABLE_MOCK=true）提供数据。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { createIdempotencyKey } from '@/api/http'
import { listResources } from '@/api/materials'
import {
  getRouting,
  listOperationTemplates,
  saveRoutingGraph,
  toRoutingEdgePayload,
  toRoutingNodePayload
} from '@/api/routings'
import { StatusTag } from '@/components/common'
import RoutingDagCanvas from '@/components/graph/RoutingDagCanvas.vue'
import {
  autoLayoutNodes,
  nodeLabel,
  validateRoutingGraph,
  wouldCreateCycle
} from '@/components/graph/routing-graph'
import { getEnumOptions } from '@/constants/enums'
import type { Resource } from '@/types/materials'
import type {
  OperationTemplate,
  PrimaryResourceMode,
  Routing,
  RoutingEdge,
  RoutingEdgePayload,
  RoutingGraph,
  RoutingGraphResponse,
  RoutingNode,
  RoutingNodePayload,
  SecondaryResourceMode
} from '@/types/routings'

import RoutingVersionDialog from './RoutingVersionDialog.vue'

/** 服务端节点按契约返回，id/position 为 Mock 可能附带的编辑附加字段。 */
interface EditorNodePayload extends RoutingNodePayload {
  id?: string
  position?: { x: number; y: number }
}

interface EditorEdgePayload extends RoutingEdgePayload {
  id?: string
}

const route = useRoute()
const router = useRouter()
const dependTypeOptions = getEnumOptions('dependType')

const routingId = computed(() => String(route.params.id ?? ''))
const graph = ref<RoutingGraph | null>(null)
const nodes = ref<RoutingNode[]>([])
const edges = ref<RoutingEdge[]>([])
const templates = ref<OperationTemplate[]>([])
const resources = ref<Resource[]>([])

const loading = ref(false)
const loadError = ref('')
const saveError = ref('')
const validationError = ref('')
const saving = ref(false)
const versionDialogVisible = ref(false)

const selectedNodeId = ref<string | null>(null)
const selectedEdgeId = ref<string | null>(null)
const canvasRef = ref<InstanceType<typeof RoutingDagCanvas> | null>(null)
const seqNoBeforeEdit = ref<number | null>(null)

const selectedNode = computed(
  () => nodes.value.find((node) => node.id === selectedNodeId.value) ?? null
)
const selectedEdge = computed(
  () => edges.value.find((edge) => edge.id === selectedEdgeId.value) ?? null
)

const readonly = computed(() => graph.value !== null && graph.value.status !== 'DRAFT')
const readonlyReason = computed(() => {
  if (!graph.value) {
    return ''
  }
  return graph.value.status === 'ACTIVE' ? '生效（ACTIVE）版本只读' : '历史（INACTIVE）版本只读'
})

const issues = computed(() => validateRoutingGraph(nodes.value, edges.value, templates.value))
const errorIssues = computed(() => issues.value.filter((issue) => issue.level === 'error'))
const issueNodeIds = computed(() => issues.value.flatMap((issue) => issue.nodeIds ?? []))
const issueEdgeIds = computed(() => issues.value.flatMap((issue) => issue.edgeIds ?? []))

function resourceCodeOf(resourceId: string | null): string | null {
  if (!resourceId) {
    return null
  }
  return resources.value.find((item) => item.id === resourceId)?.code ?? null
}

/** 详情响应转成图响应形状；图端点本身不返回 status/version，必须由详情补齐。 */
function toGraphResponse(detail: Routing): RoutingGraphResponse {
  return {
    routingId: detail.id,
    nodes: detail.nodes,
    edges: detail.edges,
    productCode: detail.productCode,
    productName: detail.productName,
    routingVersion: detail.routingVersion,
    effectiveDate: detail.effectiveDate,
    expiryDate: detail.expiryDate ?? null,
    status: detail.status,
    version: detail.version,
    updatedAt: detail.updatedAt
  }
}

/** 把契约响应转换为编辑器节点/边（补前端 id、position，并由资源 UUID 反查 code）。 */
function toEditorGraph(detail: RoutingGraphResponse, previous: RoutingNode[] = []): RoutingGraph {
  const payloadNodes = detail.nodes as EditorNodePayload[]
  const editorNodes: RoutingNode[] = payloadNodes.map((payload, index) => {
    const prior = previous.find((node) => node.seqNo === payload.seqNo)
    return {
      ...payload,
      // 后端种子节点未维护工时（返回 null），编辑器统一按 0 处理。
      standardSeconds: payload.standardSeconds ?? 0,
      setupSeconds: payload.setupSeconds ?? 0,
      id: payload.id ?? `node-${payload.seqNo}-${index}`,
      primaryResourceCode: payload.primaryResourceCode ?? resourceCodeOf(payload.primaryResourceId),
      // 模式以响应回显为准，禁止用 primaryResourceId 是否为空反推。
      primaryResourceMode: payload.primaryResourceMode ?? 'INHERIT',
      // 辅助资源：缺键=回退模板，出现数组（含 []）=显式声明。
      secondaryResourceMode: payload.secondaryResourceIds === undefined ? 'INHERIT' : 'EXPLICIT',
      secondaryResourceIds: [...(payload.secondaryResourceIds ?? [])],
      position: payload.position ?? prior?.position ?? { x: 80, y: 80 }
    }
  })
  const payloadEdges = detail.edges as EditorEdgePayload[]
  const editorEdges: RoutingEdge[] = payloadEdges.map((payload, index) => ({
    ...payload,
    id: payload.id ?? `edge-${payload.fromSeqNo}-${payload.toSeqNo}-${payload.dependType}-${index}`
  }))
  const hasPositions = payloadNodes.length > 0 && payloadNodes.every((payload) => payload.position)
  const laidOut = hasPositions ? editorNodes : autoLayoutNodes(editorNodes, editorEdges)
  return {
    id: detail.routingId,
    routingId: detail.routingId,
    productCode: detail.productCode ?? '',
    productName: detail.productName,
    routingVersion: detail.routingVersion ?? '',
    effectiveDate: detail.effectiveDate ?? '',
    expiryDate: detail.expiryDate ?? null,
    status: detail.status ?? 'DRAFT',
    version: detail.version ?? 1,
    updatedAt: detail.updatedAt,
    nodes: laidOut,
    edges: editorEdges
  }
}

function applyGraph(detail: RoutingGraphResponse, previous: RoutingNode[] = []) {
  const next = toEditorGraph(detail, previous)
  graph.value = next
  nodes.value = next.nodes
  edges.value = next.edges
}

function nodeName(seqNo: number): string {
  const node = nodes.value.find((item) => item.seqNo === seqNo)
  return node ? nodeLabel(node) : `seq_no=${seqNo}`
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [detail, templatePage, resourcePage] = await Promise.all([
      getRouting(routingId.value),
      listOperationTemplates({ page: 1, size: 200 }),
      listResources({ page: 1, size: 200 })
    ])
    templates.value = templatePage.items
    resources.value = resourcePage.items
    applyGraph(toGraphResponse(detail))
    selectedNodeId.value = null
    selectedEdgeId.value = null
  } catch (exception) {
    loadError.value = exception instanceof Error ? exception.message : '工艺路线加载失败'
  } finally {
    loading.value = false
  }
}

function selectNode(id: string) {
  selectedNodeId.value = id
  selectedEdgeId.value = null
}

function selectEdge(id: string) {
  selectedEdgeId.value = id
  selectedNodeId.value = null
}

function clearSelection() {
  selectedNodeId.value = null
  selectedEdgeId.value = null
}

function nextNodeId(): string {
  return `node-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`
}

function nextEdgeId(): string {
  return `edge-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`
}

function addNode() {
  if (readonly.value) {
    return
  }
  const template = templates.value[0]
  const seqNo = nodes.value.reduce((max, node) => Math.max(max, node.seqNo), 0) + 10
  const id = nextNodeId()
  nodes.value.push({
    id,
    seqNo,
    operationTemplateId: template?.id ?? null,
    operationCode: template?.code ?? '',
    operationName: template?.name ?? `新工序 ${seqNo}`,
    standardSeconds: template?.standardSeconds ?? 0,
    setupSeconds: template?.setupSeconds ?? 0,
    primaryResourceId: null,
    primaryResourceMode: 'INHERIT',
    primaryResourceCode: null,
    secondaryResourceMode: 'INHERIT',
    secondaryResourceIds: [],
    optional: false,
    position: { x: 120 + nodes.value.length * 24, y: 140 + nodes.value.length * 24 }
  })
  selectNode(id)
}

function removeNode(node: RoutingNode) {
  if (readonly.value) {
    return
  }
  nodes.value = nodes.value.filter((item) => item.id !== node.id)
  edges.value = edges.value.filter(
    (edge) => edge.fromSeqNo !== node.seqNo && edge.toSeqNo !== node.seqNo
  )
  if (selectedNodeId.value === node.id) {
    clearSelection()
  }
}

/** 记住编辑前的 seqNo，change 时同步所有引用该序号边的端点。 */
function markSeqNoBeforeEdit(node: RoutingNode) {
  seqNoBeforeEdit.value = node.seqNo
}

function onSeqNoChange(node: RoutingNode, current?: number) {
  if (readonly.value) {
    return
  }
  const previous = seqNoBeforeEdit.value
  if (typeof current !== 'number' || !Number.isFinite(current)) {
    if (previous !== null) {
      node.seqNo = previous
    }
    return
  }
  if (previous === null || previous === current) {
    seqNoBeforeEdit.value = current
    return
  }
  node.seqNo = current
  edges.value.forEach((edge) => {
    if (edge.fromSeqNo === previous) {
      edge.fromSeqNo = current
    }
    if (edge.toSeqNo === previous) {
      edge.toSeqNo = current
    }
  })
  seqNoBeforeEdit.value = current
}

function onTemplateChange(node: RoutingNode, templateId: string | null | undefined) {
  const template = templates.value.find((item) => item.id === templateId)
  node.operationTemplateId = template?.id ?? null
  if (!template) {
    node.primaryResourceId = null
    node.primaryResourceCode = null
    node.primaryResourceMode = 'INHERIT'
    node.secondaryResourceMode = 'INHERIT'
    node.secondaryResourceIds = []
    return
  }
  node.operationCode = template.code
  node.operationName = template.name
  node.standardSeconds = template.standardSeconds
  node.setupSeconds = template.setupSeconds
  // 换模板即回到「继承模板」语义，不把模板值复制成显式覆盖。
  node.primaryResourceId = null
  node.primaryResourceCode = null
  node.primaryResourceMode = 'INHERIT'
  node.secondaryResourceMode = 'INHERIT'
  node.secondaryResourceIds = []
}

function templatePrimaryResourceCode(node: RoutingNode): string | null {
  const template = templates.value.find((item) => item.id === node.operationTemplateId)
  return resourceCodeOf(template?.primaryResourceId ?? null)
}

function onPrimaryResourceModeChange(node: RoutingNode, mode: PrimaryResourceMode) {
  node.primaryResourceMode = mode
  if (mode !== 'FIXED') {
    node.primaryResourceId = null
    node.primaryResourceCode = null
  }
}

function onSecondaryResourceModeChange(node: RoutingNode, mode: SecondaryResourceMode) {
  node.secondaryResourceMode = mode
  if (mode === 'EXPLICIT' && !Array.isArray(node.secondaryResourceIds)) {
    node.secondaryResourceIds = []
  }
}

function isFixedWithoutResource(node: RoutingNode): boolean {
  return (node.primaryResourceMode ?? 'INHERIT') === 'FIXED' && !node.primaryResourceId
}

function onPrimaryResourceChange(node: RoutingNode, resourceId: string | null | undefined) {
  node.primaryResourceId = resourceId ?? null
  node.primaryResourceCode = resourceCodeOf(node.primaryResourceId)
}

function onPositionChange(payload: { id: string; position: { x: number; y: number } }) {
  const node = nodes.value.find((item) => item.id === payload.id)
  if (node) {
    node.position = payload.position
  }
}

function onConnect(payload: { fromSeqNo: number; toSeqNo: number }) {
  if (readonly.value) {
    return
  }
  if (payload.fromSeqNo === payload.toSeqNo) {
    ElMessage.warning('不允许工序自依赖')
    return
  }
  const duplicate = edges.value.find(
    (edge) =>
      edge.fromSeqNo === payload.fromSeqNo &&
      edge.toSeqNo === payload.toSeqNo &&
      edge.dependType === 'FS'
  )
  if (duplicate) {
    ElMessage.warning('相同方向的 FS 依赖边已存在')
    return
  }
  if (wouldCreateCycle(nodes.value, edges.value, payload.fromSeqNo, payload.toSeqNo)) {
    ElMessage.error('该连线会形成环，已阻止创建')
    return
  }
  const id = nextEdgeId()
  edges.value.push({
    id,
    fromSeqNo: payload.fromSeqNo,
    toSeqNo: payload.toSeqNo,
    dependType: 'FS',
    lagMinutes: 0
  })
  selectEdge(id)
}

function removeEdge(edge: RoutingEdge) {
  if (readonly.value) {
    return
  }
  edges.value = edges.value.filter((item) => item.id !== edge.id)
  if (selectedEdgeId.value === edge.id) {
    clearSelection()
  }
}

async function runAutoLayout() {
  if (readonly.value) {
    return
  }
  nodes.value = autoLayoutNodes(nodes.value, edges.value)
  await nextTick()
  canvasRef.value?.fit()
}

async function save() {
  if (readonly.value || !graph.value) {
    return
  }
  if (errorIssues.value.length > 0) {
    validationError.value = `存在 ${errorIssues.value.length} 项校验错误，请先修复再保存（详见右侧校验结果）`
    ElMessage.error(validationError.value)
    return
  }
  const fixedWithoutResource = nodes.value.filter(isFixedWithoutResource)
  if (fixedWithoutResource.length > 0) {
    validationError.value = fixedWithoutResource
      .map(
        (node) =>
          `工序「${node.seqNo} · ${node.operationName || node.operationCode}」主资源模式为 FIXED 但未选择资源`
      )
      .join('；')
    ElMessage.error(validationError.value)
    return
  }
  validationError.value = ''
  saving.value = true
  saveError.value = ''
  try {
    await saveRoutingGraph(
      graph.value.id,
      {
        nodes: nodes.value.map(toRoutingNodePayload),
        edges: edges.value.map(toRoutingEdgePayload),
        version: graph.value.version
      },
      createIdempotencyKey('routing')
    )
    // 图保存响应不含 status/version，重新读详情以刷新乐观锁版本与状态。
    const fresh = await getRouting(graph.value.id)
    applyGraph(toGraphResponse(fresh), nodes.value)
    clearSelection()
    ElMessage.success('工艺路线已保存')
  } catch (exception) {
    saveError.value = exception instanceof Error ? exception.message : '保存失败'
  } finally {
    saving.value = false
  }
}

function validateNow() {
  if (errorIssues.value.length > 0) {
    ElMessage.error(`发现 ${errorIssues.value.length} 项校验错误，详见右侧校验结果`)
    return
  }
  ElMessage.success('校验通过，可以保存')
}

function goBack() {
  void router.push({ name: 'process-routings' })
}

function onVersionSaved(routing: Routing) {
  void router.push({ name: 'process-routing-editor', params: { id: routing.id } })
}

onMounted(load)

// 同一路由组件内切换版本（如新建版本后跳转）时重新加载
watch(routingId, () => {
  void load()
})

watch(errorIssues, (current) => {
  if (current.length === 0) {
    validationError.value = ''
  }
})
</script>

<template>
  <div>
    <el-page-header class="page-header" @back="goBack">
      <template #content>
        <span class="editor-title">
          {{
            graph
              ? `${graph.productName || graph.productCode} · ${graph.routingVersion}`
              : '工艺路线编辑'
          }}
        </span>
        <StatusTag v-if="graph" enum-key="routingStatus" :value="graph.status" />
      </template>
      <template #extra>
        <el-button :disabled="readonly" @click="addNode">添加节点</el-button>
        <el-button :disabled="readonly" @click="runAutoLayout">自动布局</el-button>
        <el-button @click="validateNow">校验</el-button>
        <el-button :disabled="readonly" :loading="saving" type="primary" @click="save"
          >保存</el-button
        >
        <el-button v-if="readonly" type="warning" @click="versionDialogVisible = true">
          新建版本
        </el-button>
      </template>
    </el-page-header>

    <el-alert
      v-if="readonly"
      class="readonly-alert"
      type="warning"
      show-icon
      :closable="false"
      :title="`${readonlyReason}，不允许编辑；请点击「新建版本」基于当前版本复制草稿。`"
    />
    <el-alert
      v-if="validationError"
      class="readonly-alert"
      type="error"
      show-icon
      :closable="false"
      :title="validationError"
    />
    <el-alert
      v-if="loadError"
      class="readonly-alert"
      type="error"
      show-icon
      :closable="false"
      :title="loadError"
    />
    <el-alert
      v-if="saveError"
      class="readonly-alert"
      type="error"
      show-icon
      :closable="false"
      :title="saveError"
    />

    <div v-loading="loading" class="editor-body">
      <div class="editor-main">
        <el-card shadow="never" class="graph-card">
          <RoutingDagCanvas
            ref="canvasRef"
            :nodes="nodes"
            :edges="edges"
            :readonly="readonly"
            :selected-node-id="selectedNodeId"
            :selected-edge-id="selectedEdgeId"
            :issue-node-ids="issueNodeIds"
            :issue-edge-ids="issueEdgeIds"
            @node-select="selectNode"
            @edge-select="selectEdge"
            @connect="onConnect"
            @position-change="onPositionChange"
            @canvas-click="clearSelection"
          />
        </el-card>

        <el-card v-if="graph" shadow="never" class="meta-card">
          <div class="meta-grid">
            <span>物料：{{ graph.productName || '物料名称待接入' }}（{{ graph.productCode }}）</span>
            <span>生效：{{ graph.effectiveDate }}</span>
            <span>失效：{{ graph.expiryDate || '无' }}</span>
            <span>更新时间：{{ graph.updatedAt }}</span>
            <span>节点/边：{{ nodes.length }} / {{ edges.length }}</span>
          </div>
        </el-card>
      </div>

      <div class="editor-side">
        <el-card v-if="selectedNode" shadow="never">
          <template #header>
            <div class="card-header">
              <span>工序节点属性</span>
              <el-button link type="danger" :disabled="readonly" @click="removeNode(selectedNode)">
                删除节点
              </el-button>
            </div>
          </template>
          <el-form label-width="92px" label-position="left" size="small">
            <el-form-item label="节点标识">
              <el-input :model-value="selectedNode.id" readonly />
            </el-form-item>
            <el-form-item label="seqNo">
              <el-input-number
                v-model="selectedNode.seqNo"
                :min="0"
                :step="10"
                :disabled="readonly"
                style="width: 100%"
                @focus="markSeqNoBeforeEdit(selectedNode)"
                @change="onSeqNoChange(selectedNode, $event)"
              />
            </el-form-item>
            <el-form-item label="工序模版">
              <el-select
                v-model="selectedNode.operationTemplateId"
                clearable
                filterable
                placeholder="选择工序模版"
                :disabled="readonly"
                style="width: 100%"
                @change="onTemplateChange(selectedNode, $event)"
              >
                <el-option
                  v-for="item in templates"
                  :key="item.id"
                  :label="`${item.code} · ${item.name}`"
                  :value="item.id"
                />
              </el-select>
              <div v-if="!selectedNode.operationTemplateId" class="field-tip is-error">
                未选择模版将无法保存
              </div>
            </el-form-item>
            <el-form-item label="工序编码">
              <el-input v-model="selectedNode.operationCode" :disabled="readonly" maxlength="64" />
            </el-form-item>
            <el-form-item label="工序名称">
              <el-input v-model="selectedNode.operationName" :disabled="readonly" maxlength="256" />
            </el-form-item>
            <el-form-item label="标准工时(s)">
              <el-input-number
                v-model="selectedNode.standardSeconds"
                :min="0"
                :step="1"
                :disabled="readonly"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="换型工时(s)">
              <el-input-number
                v-model="selectedNode.setupSeconds"
                :min="0"
                :step="1"
                :disabled="readonly"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="主资源模式">
              <el-radio-group
                :model-value="selectedNode.primaryResourceMode ?? 'INHERIT'"
                :disabled="readonly"
                @change="onPrimaryResourceModeChange(selectedNode, $event as PrimaryResourceMode)"
              >
                <el-radio-button label="INHERIT">继承模版</el-radio-button>
                <el-radio-button label="FLOAT">浮动</el-radio-button>
                <el-radio-button label="FIXED">固定资源</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item
              v-if="(selectedNode.primaryResourceMode ?? 'INHERIT') === 'INHERIT'"
              label="继承资源"
            >
              <div style="color: var(--el-text-color-secondary); font-size: 12px">
                沿用模版主资源：{{ templatePrimaryResourceCode(selectedNode) ?? '模版未配置' }}
              </div>
            </el-form-item>
            <el-form-item
              v-if="(selectedNode.primaryResourceMode ?? 'INHERIT') === 'FLOAT'"
              label="浮动说明"
            >
              <div style="color: var(--el-text-color-secondary); font-size: 12px">
                显式浮动，不指定主资源，由求解器分配；不会回退工序模版。
              </div>
            </el-form-item>
            <el-form-item
              v-if="(selectedNode.primaryResourceMode ?? 'INHERIT') === 'FIXED'"
              label="固定资源"
            >
              <el-select
                v-model="selectedNode.primaryResourceId"
                clearable
                filterable
                placeholder="选择主资源（必填）"
                :disabled="readonly"
                style="width: 100%"
                @change="onPrimaryResourceChange(selectedNode, $event)"
              >
                <el-option
                  v-for="item in resources"
                  :key="item.id"
                  :label="`${item.code} · ${item.name}`"
                  :value="item.id"
                />
              </el-select>
              <div
                v-if="isFixedWithoutResource(selectedNode)"
                style="color: var(--el-color-error); font-size: 12px"
              >
                FIXED 模式必须选择一个资源，否则无法保存
              </div>
            </el-form-item>
            <el-form-item label="辅助资源模式">
              <el-radio-group
                :model-value="selectedNode.secondaryResourceMode ?? 'INHERIT'"
                :disabled="readonly"
                @change="
                  onSecondaryResourceModeChange(selectedNode, $event as SecondaryResourceMode)
                "
              >
                <el-radio-button label="INHERIT">继承模版</el-radio-button>
                <el-radio-button label="EXPLICIT">显式指定</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item
              v-if="(selectedNode.secondaryResourceMode ?? 'INHERIT') === 'INHERIT'"
              label="继承辅助资源"
            >
              <div style="color: var(--el-text-color-secondary); font-size: 12px">
                不下发列表，沿用工序模版的辅助资源。
              </div>
            </el-form-item>
            <el-form-item v-else label="辅助资源">
              <el-select
                v-model="selectedNode.secondaryResourceIds"
                multiple
                clearable
                filterable
                placeholder="可多选；留空表示显式不使用辅助资源"
                :disabled="readonly"
                style="width: 100%"
              >
                <el-option
                  v-for="item in resources"
                  :key="item.id"
                  :label="`${item.code} · ${item.name}`"
                  :value="item.id"
                />
              </el-select>
              <div style="color: var(--el-text-color-secondary); font-size: 12px">
                当前为显式声明（空列表也会下发），区别于「继承模版」。
              </div>
            </el-form-item>
            <el-form-item label="可选工序">
              <el-switch v-model="selectedNode.optional" :disabled="readonly" />
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-else-if="selectedEdge" shadow="never">
          <template #header>
            <div class="card-header">
              <span>依赖边属性</span>
              <el-button link type="danger" :disabled="readonly" @click="removeEdge(selectedEdge)">
                删除连线
              </el-button>
            </div>
          </template>
          <el-form label-width="92px" label-position="left" size="small">
            <el-form-item label="前序工序">
              <span>{{ nodeName(selectedEdge.fromSeqNo) }}</span>
            </el-form-item>
            <el-form-item label="后序工序">
              <span>{{ nodeName(selectedEdge.toSeqNo) }}</span>
            </el-form-item>
            <el-form-item label="依赖类型">
              <el-select v-model="selectedEdge.dependType" :disabled="readonly" style="width: 100%">
                <el-option
                  v-for="item in dependTypeOptions"
                  :key="item.value"
                  :label="`${item.label}（${item.value}）`"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="滞后(min)">
              <el-input-number
                v-model="selectedEdge.lagMinutes"
                :min="0"
                :step="5"
                :disabled="readonly"
                style="width: 100%"
              />
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-else shadow="never">
          <el-empty description="点击画布中的工序节点或依赖边，编辑属性" :image-size="70" />
        </el-card>

        <el-card shadow="never" class="issue-card">
          <template #header>
            <div class="card-header">
              <span>校验结果（{{ issues.length }}）</span>
              <el-button link @click="validateNow">校验</el-button>
            </div>
          </template>
          <el-empty v-if="!issues.length" description="校验通过" :image-size="50" />
          <ul v-else class="issue-list">
            <li
              v-for="(issue, index) in issues"
              :key="`${issue.code}-${index}`"
              :class="issue.level"
            >
              <el-tag :type="issue.level === 'error' ? 'danger' : 'warning'" size="small">
                {{ issue.level === 'error' ? '错误' : '提示' }}
              </el-tag>
              <span class="issue-text">{{ issue.message }}</span>
            </li>
          </ul>
        </el-card>

        <el-card shadow="never">
          <template #header>
            <span>工序列表</span>
          </template>
          <div class="node-list">
            <button
              v-for="node in nodes"
              :key="node.id"
              type="button"
              class="node-list-item"
              :class="{ 'is-active': node.id === selectedNodeId }"
              @click="selectNode(node.id)"
            >
              <span class="node-seq">{{ node.seqNo }}</span>
              <span class="node-list-name">{{ node.operationName || '未命名工序' }}</span>
              <el-tag v-if="!node.operationTemplateId" type="danger" size="small">缺模版</el-tag>
              <el-tag v-if="node.optional" type="success" size="small">可选</el-tag>
            </button>
            <el-empty v-if="!nodes.length" description="暂无工序" :image-size="50" />
          </div>
        </el-card>
      </div>
    </div>

    <RoutingVersionDialog v-model="versionDialogVisible" :source="graph" @saved="onVersionSaved" />
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 12px;
}

.editor-title {
  margin-right: 8px;
  font-weight: 600;
  font-size: 16px;
}

.readonly-alert {
  margin-bottom: 12px;
}

.editor-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.editor-main {
  flex: 1;
  min-width: 0;
}

.editor-side {
  width: 400px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.graph-card,
.meta-card {
  margin-bottom: 12px;
}

.graph-card :deep(.el-card__body) {
  padding: 0;
}

.meta-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.field-tip {
  font-size: 12px;
  margin-top: 4px;
}

.field-tip.is-error {
  color: var(--el-color-danger);
}

.issue-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.issue-list li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.issue-text {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
}

.issue-list li.error .issue-text {
  color: var(--el-color-danger);
}

.node-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 220px;
  overflow: auto;
}

.node-list-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--card);
  cursor: pointer;
  text-align: left;
}

.node-list-item.is-active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.node-seq {
  min-width: 28px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--border-strong);
  color: var(--text);
  font-size: 11px;
  text-align: center;
}

.node-list-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
</style>
