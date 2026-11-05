<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { deleteMaterial, listMaterials } from '@/api/materials'
import { DataTable, StatusTag } from '@/components/common'
import { getEnumOptions } from '@/constants/enums'
import type { Material, MaterialType } from '@/types/materials'

import MaterialDetailDrawer from './MaterialDetailDrawer.vue'
import MaterialFormDialog from './MaterialFormDialog.vue'

const materialTypeOptions = getEnumOptions('materialType')

const filters = reactive<{
  code: string
  name: string
  type: MaterialType | ''
}>({ code: '', name: '', type: '' })

const materials = ref<Material[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)

const formVisible = ref(false)
const editingMaterial = ref<Material | null>(null)
const detailVisible = ref(false)
const detailMaterial = ref<Material | null>(null)

async function loadMaterials() {
  loading.value = true
  error.value = ''
  try {
    const result = await listMaterials({
      page: page.value,
      size: size.value,
      sort: 'code,asc',
      code: filters.code.trim() || undefined,
      name: filters.name.trim() || undefined,
      type: filters.type || undefined
    })
    materials.value = result.items
    total.value = result.total
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '物料加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  void loadMaterials()
}

function resetFilters() {
  filters.code = ''
  filters.name = ''
  filters.type = ''
  search()
}

function openCreate() {
  editingMaterial.value = null
  formVisible.value = true
}

function openEdit(material: Material) {
  editingMaterial.value = material
  detailVisible.value = false
  formVisible.value = true
}

function openDetail(material: Material) {
  detailMaterial.value = material
  detailVisible.value = true
}

function onSaved(material: Material) {
  if (!editingMaterial.value) {
    page.value = 1
  }
  detailMaterial.value = material
  void loadMaterials()
}

async function deactivate(material: Material) {
  try {
    await ElMessageBox.confirm(
      `停用物料「${material.code}」将从列表隐藏（软删除），历史 BOM/订单引用保留；若被引用后端会拒绝。是否继续？`,
      '停用物料',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteMaterial(material.code)
    ElMessage.success('物料已停用')
    detailVisible.value = false
    await loadMaterials()
  } catch {
    // 统一拦截器已提示，保留列表以便重试
  }
}

onMounted(loadMaterials)
</script>

<template>
  <div>
    <h2 class="page-title">物料管理</h2>

    <el-card shadow="never" class="page-card">
      <el-form :inline="true" @submit.prevent="search">
        <el-form-item label="物料编码">
          <el-input
            v-model="filters.code"
            clearable
            placeholder="按物料编码模糊查询"
            style="width: 180px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="物料名称">
          <el-input
            v-model="filters.name"
            clearable
            placeholder="按物料名称模糊查询"
            style="width: 200px"
            @keyup.enter="search"
          />
        </el-form-item>
        <el-form-item label="物料类型">
          <el-select v-model="filters.type" clearable placeholder="全部" style="width: 150px">
            <el-option
              v-for="item in materialTypeOptions"
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
      :data="materials"
      :loading="loading"
      :error="error"
      :total="total"
      row-key="code"
      empty-text="暂无物料数据"
      @change="loadMaterials"
      @retry="loadMaterials"
    >
      <template #toolbar>
        <el-button type="primary" @click="openCreate">新建物料</el-button>
      </template>

      <el-table-column prop="name" label="物料名称" min-width="220" show-overflow-tooltip />
      <el-table-column label="规格型号" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.spec || '无' }}</template>
      </el-table-column>
      <el-table-column prop="code" label="物料编码" min-width="160">
        <template #default="{ row }">
          <span class="secondary-text">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column label="物料类型" width="110">
        <template #default="{ row }">
          <StatusTag enum-key="materialType" :value="row.type" />
        </template>
      </el-table-column>
      <el-table-column label="计量单位" width="100">
        <template #default="{ row }">{{ row.unit || '无' }}</template>
      </el-table-column>
      <el-table-column label="安全库存" width="110">
        <template #default="{ row }">{{ row.safetyStock ?? '无' }}</template>
      </el-table-column>
      <el-table-column label="采购提前期（天）" width="140">
        <template #default="{ row }">{{ row.leadTimeDays ?? '无' }}</template>
      </el-table-column>
      <el-table-column prop="version" label="版本号" width="80" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="deactivate(row)">停用</el-button>
        </template>
      </el-table-column>
    </DataTable>

    <MaterialFormDialog v-model="formVisible" :material="editingMaterial" @saved="onSaved" />

    <MaterialDetailDrawer
      v-model="detailVisible"
      :material="detailMaterial"
      @edit="openEdit"
      @deactivate="deactivate"
    />
  </div>
</template>

<style scoped>
.secondary-text {
  color: var(--text-3);
  font-size: var(--font-xs);
}
</style>
