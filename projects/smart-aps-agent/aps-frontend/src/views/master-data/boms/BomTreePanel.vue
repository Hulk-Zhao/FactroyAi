<script setup lang="ts">
import { ref, watch } from 'vue'

import { getBomTree } from '@/api/boms'
import { PageState } from '@/components/common'
import type { BomTreeNode } from '@/types/boms'

interface BomTreeItem extends BomTreeNode {
  nodeKey: string
  children?: BomTreeItem[]
}

const props = withDefaults(
  defineProps<{
    bomId?: string | null
    refreshToken?: number
  }>(),
  {
    bomId: null,
    refreshToken: 0
  }
)

const treeData = ref<BomTreeItem[]>([])
const loading = ref(false)
const error = ref('')
const expandAll = ref(true)
const renderKey = ref(0)

function withNodeKey(nodes: BomTreeNode[], path: string): BomTreeItem[] {
  return nodes.map((node, index) => ({
    ...node,
    nodeKey: `${path}/${node.bomId || node.parentMaterialCode}-${node.parentMaterialCode}-${index}`,
    children: node.children?.length ? withNodeKey(node.children, `${path}/${index}`) : []
  }))
}

async function loadTree() {
  if (!props.bomId) {
    treeData.value = []
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    const tree = await getBomTree(props.bomId)
    treeData.value = withNodeKey([tree], tree.bomId || 'root')
    renderKey.value += 1
  } catch (exception) {
    treeData.value = []
    error.value = exception instanceof Error ? exception.message : 'BOM 树加载失败'
  } finally {
    loading.value = false
  }
}

function toggleExpand() {
  expandAll.value = !expandAll.value
  renderKey.value += 1
}

watch(
  () => [props.bomId, props.refreshToken],
  () => {
    void loadTree()
  },
  { immediate: true }
)
</script>

<template>
  <el-card shadow="never" class="bom-tree">
    <template #header>
      <div class="tree-header">
        <span class="tree-title">BOM 结构树</span>
        <div class="tree-actions">
          <el-button link type="primary" :disabled="!bomId" @click="toggleExpand">
            {{ expandAll ? '收起全部' : '展开全部' }}
          </el-button>
          <el-button link type="primary" :disabled="!bomId" @click="loadTree">刷新</el-button>
        </div>
      </div>
    </template>

    <PageState v-if="error" state="error" :description="error" @retry="loadTree" />
    <el-empty v-else-if="!bomId" description="请先选择左侧 BOM 版本查看结构树" />
    <el-empty v-else-if="!loading && treeData.length === 0" description="该 BOM 暂无结构数据" />
    <el-tree
      v-else
      :key="renderKey"
      v-loading="loading"
      :data="treeData"
      node-key="nodeKey"
      :props="{ children: 'children', label: 'parentMaterialCode' }"
      :default-expand-all="expandAll"
      :expand-on-click-node="false"
    >
      <template #default="{ data }">
        <div class="tree-node">
          <span class="tree-name">{{ data.materialName || '物料名称待接入' }}</span>
          <span class="tree-code">{{ data.parentMaterialCode }}</span>
          <el-tag size="small" type="info">用量 {{ data.qtyPerUnit }}</el-tag>
          <el-tag size="small" type="primary">L{{ data.level }}</el-tag>
        </div>
      </template>
    </el-tree>

    <div class="tree-hint">默认展开 5 层，超过后由契约侧截断；子件无 BOM 时作为叶子节点展示。</div>
  </el-card>
</template>

<style scoped>
.bom-tree {
  height: 100%;
}

.tree-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tree-title {
  font-size: 15px;
  font-weight: 600;
}

.tree-actions {
  display: flex;
  gap: 8px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.tree-name {
  font-weight: 600;
  color: var(--text);
}

.tree-code {
  color: var(--text-3);
  font-size: var(--font-xs);
}

.tree-hint {
  margin-top: 12px;
  color: var(--text-3);
  font-size: 12px;
}
</style>
