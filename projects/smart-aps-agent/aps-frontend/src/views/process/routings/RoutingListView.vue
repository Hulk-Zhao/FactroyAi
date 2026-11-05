<script setup lang="ts">
/**
 * 工艺路线列表：按产品物料、版本、状态筛选；支持新建空白版本或复制现有版本。
 * 路径参数按 contract-freeze C6 使用 UUID。
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { listMaterials } from '@/api/materials'
import { listRoutings } from '@/api/routings'
import { DataTable, StatusTag } from '@/components/common'
import MaterialSelect from '@/components/master-data/MaterialSelect.vue'
import { getEnumOptions } from '@/constants/enums'
import type { Material } from '@/types/materials'
import type { Routing, RoutingQuery, RoutingStatus } from '@/types/routings'

import RoutingVersionDialog from './RoutingVersionDialog.vue'

const router = useRouter()
const statusOptions = getEnumOptions('routingStatus')

const filters = reactive<{
  productCode: string
  routingVersion: string
  status: RoutingStatus | ''
}>({ productCode: '', routingVersion: '', status: '' })

const rows = ref<Routing[]>([])
/** 工艺路线接口的 productName 可能为空，用物料主数据补齐名称。 */
const materialMap = ref<Record<string, Material>>({})
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const dialogVisible = ref(false)
const sourceRouting = ref<Routing | null>(null)

async function loadRoutings() {
  loading.value = true
  error.value = ''
  try {
    const query: RoutingQuery & { page: number; size: number; sort: string } = {
      page: page.value,
      size: size.value,
      sort: 'updatedAt,desc',
      productCode: filters.productCode || undefined,
      version: filters.routingVersion.trim() || undefined,
      status: filters.status || undefined
    }
    const result = await listRoutings(query)
    rows.value = result.items
    total.value = result.total
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '工艺路线加载失败'
  } finally {
    loading.value = false
  }
}

function materialNameOf(row: Routing): string {
  return row.productName || materialMap.value[row.productCode]?.name || '物料名称待接入'
}

async function loadMaterialMap() {
  try {
    const result = await listMaterials({ page: 1, size: 200, sort: 'code,asc' })
    const map: Record<string, Material> = {}
    result.items.forEach((item) => {
      map[item.code] = item
    })
    materialMap.value = map
  } catch {
    // 物料主数据不可用时保留接口返回值
  }
}

function search() {
  page.value = 1
  void loadRoutings()
}

function resetFilters() {
  filters.productCode = ''
  filters.routingVersion = ''
  filters.status = ''
  search()
}

function openNewVersion(row: Routing) {
  sourceRouting.value = row
  dialogVisible.value = true
}

function openEditor(row: Routing) {
  void router.push({ name: 'process-routing-editor', params: { id: row.id } })
}

function onCreated(routing: Routing) {
  ElMessage.success('草稿版本已创建，进入编辑器完善 DAG')
  void router.push({ name: 'process-routing-editor', params: { id: routing.id } })
}

onMounted(() => {
  void loadMaterialMap()
  void loadRoutings()
})
</script>

<template>
  <div>
    <h2 class="page-title">工艺路线</h2>

    <el-card shadow="never" class="page-card">
      <el-form :inline="true" @submit.prevent="search">
        <el-form-item label="物料">
          <MaterialSelect
            v-model="filters.productCode"
            placeholder="按物料名称/编码搜索"
            style="width: 240px"
          />
        </el-form-item>
        <el-form-item label="工艺路线版本">
          <el-input
            v-model="filters.routingVersion"
            clearable
            placeholder="如 V1.0"
            style="width: 150px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部" style="width: 140px">
            <el-option
              v-for="item in statusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <DataTable
      v-model:page="page"
      v-model:size="size"
      :data="rows"
      :loading="loading"
      :error="error"
      :total="total"
      row-key="id"
      empty-text="暂无工艺路线"
      @change="loadRoutings"
      @retry="loadRoutings"
    >
      <el-table-column prop="productName" label="物料名称" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ materialNameOf(row) }}</template>
      </el-table-column>
      <el-table-column prop="productCode" label="物料编码" min-width="160">
        <template #default="{ row }">
          <span class="secondary-text">{{ row.productCode }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="routingVersion" label="工艺路线版本" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusTag enum-key="routingStatus" :value="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="工序数/依赖数" width="130">
        <template #default="{ row }">{{ row.nodes.length }} / {{ row.edges.length }}</template>
      </el-table-column>
      <el-table-column prop="effectiveDate" label="生效日期" width="120" />
      <el-table-column prop="expiryDate" label="失效日期" width="120">
        <template #default="{ row }">{{ row.expiryDate || '无' }}</template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEditor(row)">
            {{ row.status === 'ACTIVE' ? '查看' : '编辑' }}
          </el-button>
          <el-button link type="primary" @click="openNewVersion(row)">新建版本</el-button>
        </template>
      </el-table-column>
    </DataTable>

    <RoutingVersionDialog
      v-model="dialogVisible"
      :source="sourceRouting"
      :default-product-code="filters.productCode"
      @saved="onCreated"
    />
  </div>
</template>

<style scoped>
.secondary-text {
  color: var(--text-3);
  font-size: var(--font-xs);
}
</style>
