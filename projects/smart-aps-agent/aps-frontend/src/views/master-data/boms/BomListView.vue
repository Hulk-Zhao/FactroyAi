<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { activateBom, listBoms } from '@/api/boms'
import { listMaterials } from '@/api/materials'
import { StatusTag } from '@/components/common'
import DataTable from '@/components/common/DataTable.vue'
import MaterialSelect from '@/components/master-data/MaterialSelect.vue'
import { getEnumLabel, getEnumOptions } from '@/constants/enums'
import type { Bom, BomStatus } from '@/types/boms'
import type { Material } from '@/types/materials'

import BomDetailDrawer from './BomDetailDrawer.vue'
import BomFormDialog from './BomFormDialog.vue'
import BomTreePanel from './BomTreePanel.vue'

const parentCode = ref<string | null>(null)
const statusFilter = ref<BomStatus | ''>('')
const bomStatusOptions = getEnumOptions('bomStatus')

const boms = ref<Bom[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const selectedId = ref<string | null>(null)
const treeRefreshToken = ref(0)
const materialNames = ref<Record<string, string>>({})

const detailVisible = ref(false)
const detailBom = ref<Bom | null>(null)
const formVisible = ref(false)
const formParentCode = ref<string | null>(null)
const formSource = ref<Bom | null>(null)
const lastSavedId = ref<string | null>(null)

const selectedBom = computed(() => boms.value.find((item) => item.id === selectedId.value) ?? null)

async function loadBoms() {
  loading.value = true
  error.value = ''
  try {
    const result = await listBoms({
      page: page.value,
      size: size.value,
      parentMaterialCode: parentCode.value || undefined,
      status: statusFilter.value || undefined
    })
    boms.value = result.items
    total.value = result.total
    const fallback =
      lastSavedId.value && result.items.some((item) => item.id === lastSavedId.value)
        ? lastSavedId.value
        : (result.items[0]?.id ?? null)
    lastSavedId.value = null
    if (!selectedId.value || !result.items.some((item) => item.id === selectedId.value)) {
      selectedId.value = fallback
    }
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : 'BOM 加载失败'
  } finally {
    loading.value = false
  }
}

async function loadMaterialNames() {
  try {
    const result = await listMaterials({ page: 1, size: 200, sort: 'code,asc' })
    const names: Record<string, string> = {}
    result.items.forEach((item: Material) => {
      names[item.code] = item.name
    })
    materialNames.value = names
  } catch {
    // 名称缺失时表格回退显示编码，不影响主流程
  }
}

function materialNameOf(code: string): string {
  return materialNames.value[code] || '物料名称待接入'
}

function search() {
  page.value = 1
  selectedId.value = null
  void loadBoms()
}

function resetFilters() {
  parentCode.value = null
  statusFilter.value = ''
  search()
}

function onRowClick(row: Bom) {
  selectedId.value = row.id
}

function openDetail(row: Bom) {
  detailBom.value = row
  detailVisible.value = true
}

function openCreate() {
  formParentCode.value = parentCode.value
  formSource.value = null
  formVisible.value = true
}

function openNewVersion(row: Bom) {
  formParentCode.value = row.parentMaterialCode
  formSource.value = row
  formVisible.value = true
}

function onSaved(bom: Bom) {
  lastSavedId.value = bom.id
  selectedId.value = bom.id
  void loadBoms()
  treeRefreshToken.value += 1
}

async function activate(row: Bom) {
  if (row.status === 'ACTIVE') {
    ElMessage.info('该版本已是生效状态')
    return
  }
  let activeVersion: Bom | undefined
  try {
    const activeResult = await listBoms({
      page: 1,
      size: 200,
      parentMaterialCode: row.parentMaterialCode,
      status: 'ACTIVE'
    })
    activeVersion = activeResult.items.find((item) => item.id !== row.id)
  } catch {
    return
  }
  const message = activeVersion
    ? `父件 ${row.parentMaterialCode} 已有生效版本 ${activeVersion.bomVersion}。按 C8「同一父件仅允许一个 ACTIVE」，激活 ${row.bomVersion} 将使其失效，是否继续？`
    : `确认将 ${row.bomVersion} 激活为生效版本？`
  try {
    await ElMessageBox.confirm(message, 'ACTIVE 版本唯一性检查', {
      type: 'warning',
      confirmButtonText: '激活',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await activateBom(row.id)
    ElMessage.success('BOM 版本已激活')
    await loadBoms()
    treeRefreshToken.value += 1
  } catch {
    // 统一拦截器已提示
  }
}

onMounted(() => {
  void loadMaterialNames()
  void loadBoms()
})
</script>

<template>
  <div>
    <h2 class="page-title">BOM 管理</h2>

    <el-card shadow="never" class="page-card">
      <el-form :inline="true" @submit.prevent="search">
        <el-form-item label="父件物料">
          <div style="width: 280px">
            <MaterialSelect v-model="parentCode" placeholder="按父件物料查询（可空）" />
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="statusFilter" clearable placeholder="全部" style="width: 130px">
            <el-option
              v-for="item in bomStatusOptions"
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

    <el-row :gutter="16">
      <el-col :span="11">
        <DataTable
          v-model:page="page"
          v-model:size="size"
          :data="boms"
          :loading="loading"
          :error="error"
          :total="total"
          row-key="id"
          empty-text="暂无 BOM 版本"
          @change="loadBoms"
          @retry="loadBoms"
        >
          <template #toolbar>
            <el-button type="primary" @click="openCreate">新建 BOM</el-button>
          </template>

          <el-table-column label="父件物料" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="primary-text">{{ materialNameOf(row.parentMaterialCode) }}</div>
              <div class="secondary-text">{{ row.parentMaterialCode }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="bomVersion" label="BOM 版本" width="110" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <StatusTag enum-key="bomStatus" :value="row.status" />
            </template>
          </el-table-column>
          <el-table-column prop="effectiveDate" label="生效日期" width="120" />
          <el-table-column label="组件数" width="90">
            <template #default="{ row }">{{ row.items.length }}</template>
          </el-table-column>
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="onRowClick(row)">结构</el-button>
              <el-button link type="primary" @click.stop="openDetail(row)">明细</el-button>
              <el-button
                link
                type="success"
                :disabled="row.status === 'ACTIVE'"
                @click.stop="activate(row)"
              >
                激活
              </el-button>
              <el-button link type="primary" @click.stop="openNewVersion(row)">新版本</el-button>
            </template>
          </el-table-column>
        </DataTable>
      </el-col>

      <el-col :span="13">
        <BomTreePanel :bom-id="selectedId" :refresh-token="treeRefreshToken" />
      </el-col>
    </el-row>

    <div class="selected-hint">
      当前选中：
      <span v-if="selectedBom">
        {{ materialNameOf(selectedBom.parentMaterialCode) }}（{{
          selectedBom.parentMaterialCode
        }}） / {{ selectedBom.bomVersion }} / {{ getEnumLabel('bomStatus', selectedBom.status) }}
      </span>
      <span v-else>未选择 BOM 版本</span>
    </div>

    <BomFormDialog
      v-model="formVisible"
      :parent-code="formParentCode"
      :source="formSource"
      @saved="onSaved"
    />

    <BomDetailDrawer v-model="detailVisible" :bom="detailBom" :material-names="materialNames" />
  </div>
</template>

<style scoped>
.selected-hint {
  margin-top: 12px;
  color: var(--text-2);
  font-size: 13px;
}

.primary-text {
  color: var(--text);
}

.secondary-text {
  color: var(--text-3);
  font-size: var(--font-xs);
}
</style>
