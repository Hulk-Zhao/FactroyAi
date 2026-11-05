<script setup lang="ts">
import { computed } from 'vue'

import { StatusTag } from '@/components/common'
import type { Material } from '@/types/materials'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    material?: Material | null
  }>(),
  {
    material: null
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  deactivate: [material: Material]
  edit: [material: Material]
}>()

const extAttrsText = computed(() =>
  props.material?.extAttrs && Object.keys(props.material.extAttrs).length > 0
    ? JSON.stringify(props.material.extAttrs, null, 2)
    : '无'
)

const industryAttrsText = computed(() =>
  props.material?.industryAttrs && Object.keys(props.material.industryAttrs).length > 0
    ? JSON.stringify(props.material.industryAttrs, null, 2)
    : '无'
)

function close() {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    title="物料详情"
    size="520px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="material">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="物料编码">{{ material.code }}</el-descriptions-item>
        <el-descriptions-item label="物料名称">{{ material.name }}</el-descriptions-item>
        <el-descriptions-item label="物料类型">
          <StatusTag enum-key="materialType" :value="material.type" />
        </el-descriptions-item>
        <el-descriptions-item label="规格型号">{{ material.spec || '无' }}</el-descriptions-item>
        <el-descriptions-item label="计量单位">{{ material.unit || '无' }}</el-descriptions-item>
        <el-descriptions-item label="物料分类">
          <el-tooltip v-if="material.categoryId" content="内部分类 ID，不对外展示" placement="top">
            <span>已维护</span>
          </el-tooltip>
          <span v-else class="none-text">无</span>
        </el-descriptions-item>
        <el-descriptions-item label="安全库存">
          {{ material.safetyStock ?? '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="最小起订量">{{ material.moq ?? '无' }}</el-descriptions-item>
        <el-descriptions-item label="采购提前期">
          {{ material.leadTimeDays ?? '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="默认供应商">
          <el-tooltip v-if="material.defaultSupplierId" content="内部供应商 ID，名称待档案接入" placement="top">
            <span>已维护</span>
          </el-tooltip>
          <span v-else class="none-text">无</span>
        </el-descriptions-item>
        <el-descriptions-item label="版本号">{{ material.version }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">
          {{ material.createdAt || '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="更新时间">
          {{ material.updatedAt || '无' }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="section-title">扩展属性</div>
      <pre class="json-block">{{ extAttrsText }}</pre>

      <div class="section-title">行业属性</div>
      <pre class="json-block">{{ industryAttrsText }}</pre>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="停用为软删除语义：物料从列表隐藏，历史 BOM/订单引用保留；被引用时后端将返回 42200。"
      />

      <div class="drawer-actions">
        <el-button @click="emit('edit', material)">编辑</el-button>
        <el-button type="danger" plain @click="emit('deactivate', material)">停用物料</el-button>
        <el-button type="primary" @click="close">关闭</el-button>
      </div>
    </template>
    <el-empty v-else description="未选择物料" />
  </el-drawer>
</template>

<style scoped>
.none-text {
  color: var(--text-3);
}

.section-title {
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.json-block {
  margin: 0 0 16px;
  padding: 12px;
  max-height: 220px;
  overflow: auto;
  border-radius: 4px;
  background: var(--bg-soft);
  color: var(--text-2);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
}
</style>
