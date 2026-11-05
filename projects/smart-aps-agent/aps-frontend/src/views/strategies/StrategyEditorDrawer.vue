<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { createStrategy, updateStrategy } from '@/api/orders'
import { getEnumOptions } from '@/constants/enums'
import type {
  InitialDimensionRule,
  InitialSortDimension,
  OrderSource,
  StrategyCreateRequest,
  StrategyDetail,
  StrategyDirection,
  StrategyRule
} from '@/types/orders'

import {
  INITIAL_DIMENSION_CATALOG,
  MVP_SOFT_RULE_CODES,
  SOFT_WEIGHT_TOTAL,
  STRATEGY_RULE_CATALOG,
  buildDefaultInitialDimensions,
  buildDefaultStrategyRules,
  findRuleCatalogItem,
  sumEnabledSoftWeights
} from './rule-catalog'

const props = withDefaults(
  defineProps<{
    visible: boolean
    mode: 'create' | 'edit'
    strategy?: StrategyDetail | null
  }>(),
  {
    strategy: null
  }
)

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  saved: []
}>()

const directionOptions = getEnumOptions('strategyDirection')
const orderSourceOptions = getEnumOptions('orderSource')

interface EditorForm {
  name: string
  direction: StrategyDirection
  strategyVersion: number
  effectiveFrom: string | null
  effectiveTo: string | null
  applyProductLines: string
  applyOrderSources: OrderSource[]
  defaultSetupMinutes: number
  dimensions: InitialDimensionRule[]
  rules: StrategyRule[]
}

const form = reactive<EditorForm>({
  name: '',
  direction: 'FORWARD',
  strategyVersion: 1,
  effectiveFrom: todayString(),
  effectiveTo: null,
  applyProductLines: '',
  applyOrderSources: [],
  defaultSetupMinutes: 30,
  dimensions: buildDefaultInitialDimensions(),
  rules: buildDefaultStrategyRules()
})

const saving = ref(false)
const validationError = ref('')

const softWeightSum = computed(() => sumEnabledSoftWeights(form.rules))
const softWeightOk = computed(() => Math.abs(softWeightSum.value - SOFT_WEIGHT_TOTAL) < 1e-6)
const dimensionWeightSum = computed(() =>
  form.dimensions.reduce((total, item) => total + (Number(item.weight) || 0), 0)
)
const title = computed(() => (props.mode === 'edit' ? '编辑策略' : '新建策略'))

watch(
  () => props.visible,
  (visible) => {
    if (visible) {
      resetForm()
    }
  }
)

function todayString(): string {
  const date = new Date()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function splitList(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item.length > 0)
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.filter((item): item is string => typeof item === 'string')
}

/** 解析初排维度：优先使用 dimensions，其次回退到 sequence（旧 initial_rules 形态）。 */
function parseDimensions(initialRules?: Record<string, unknown>): InitialDimensionRule[] {
  const defaults = buildDefaultInitialDimensions()
  if (!isRecord(initialRules)) {
    return defaults
  }
  const rawDimensions = initialRules.dimensions
  if (Array.isArray(rawDimensions)) {
    return defaults.map((fallback) => {
      const matched = rawDimensions.find(
        (entry) => isRecord(entry) && entry.dimension === fallback.dimension
      )
      if (!matched || !isRecord(matched)) {
        return fallback
      }
      return {
        dimension: fallback.dimension,
        direction: matched.direction === 'DESC' ? 'DESC' : 'ASC',
        weight: Number(matched.weight) || 0
      }
    })
  }
  const sequence = readStringArray(initialRules.sequence) as InitialSortDimension[]
  if (sequence.length === 0) {
    return defaults
  }
  const weights = [40, 30, 20, 10, 5]
  return defaults.map((fallback) => {
    const index = sequence.indexOf(fallback.dimension)
    return {
      ...fallback,
      weight: index >= 0 ? (weights[index] ?? 0) : 0
    }
  })
}

function parseRules(source: { rules?: StrategyRule[] } | null): StrategyRule[] {
  if (!source?.rules || source.rules.length === 0) {
    return buildDefaultStrategyRules()
  }
  return source.rules
    .map((rule, index) => ({
      ...rule,
      name: rule.name ?? findRuleCatalogItem(rule.code)?.name ?? rule.code,
      phase: rule.phase ?? 'OPTIMIZATION',
      type: rule.type ?? 'SOFT',
      weight: Number(rule.weight) || 0,
      enabled: rule.enabled !== false,
      seqNo: index + 1,
      params: rule.params ?? {}
    }))
    .sort((left, right) => left.seqNo - right.seqNo)
}

function resetForm() {
  validationError.value = ''
  const strategy = props.mode === 'edit' ? props.strategy : null
  const initialRules = strategy?.initialRules as Record<string, unknown> | undefined
  const applyCondition = strategy?.applyCondition as Record<string, unknown> | undefined

  form.name = strategy?.name ?? ''
  form.direction = strategy?.direction ?? 'FORWARD'
  form.strategyVersion = strategy?.strategyVersion ?? 1
  form.effectiveFrom = strategy?.effectiveFrom ?? todayString()
  form.effectiveTo = strategy?.effectiveTo ?? null
  form.defaultSetupMinutes = isRecord(initialRules)
    ? Number(initialRules.defaultSetupMinutes) || 30
    : 30
  form.dimensions = parseDimensions(initialRules)
  form.rules = parseRules(strategy)
  form.applyProductLines = isRecord(applyCondition)
    ? readStringArray(applyCondition.productLines).join(', ')
    : ''
  form.applyOrderSources = isRecord(applyCondition)
    ? (readStringArray(applyCondition.orderSources) as OrderSource[])
    : []
}

function isCodeTaken(code: string, currentRow: StrategyRule): boolean {
  return form.rules.some((rule) => rule !== currentRow && rule.code === code)
}

function onRuleCodeChange(row: StrategyRule) {
  const item = findRuleCatalogItem(row.code)
  row.name = item?.name ?? row.code
  row.type = item?.type ?? 'SOFT'
  if (row.type === 'HARD') {
    row.weight = 0
  }
}

function onRuleTypeChange(row: StrategyRule) {
  if (row.type === 'HARD') {
    row.weight = 0
  }
}

function addRule() {
  const unused = STRATEGY_RULE_CATALOG.find(
    (item) => !form.rules.some((rule) => rule.code === item.code)
  )
  if (!unused) {
    ElMessage.warning('规则字典中的规则已全部添加')
    return
  }
  form.rules.push({
    code: unused.code,
    name: unused.name,
    phase: 'OPTIMIZATION',
    type: unused.type,
    weight: 0,
    params: {},
    enabled: true,
    seqNo: form.rules.length + 1
  })
}

function removeRule(index: number) {
  form.rules.splice(index, 1)
}

/** 前端预校验：与 C19「权重和=100」及 BR-SS-001-04「初排权重和不得为 0」保持一致。 */
function validate(): string | null {
  if (!form.name.trim()) {
    return '策略名称不能为空'
  }
  if (!form.effectiveFrom) {
    return '生效日期不能为空'
  }
  if (form.effectiveTo && form.effectiveFrom && form.effectiveTo < form.effectiveFrom) {
    return '失效日期不能早于生效日期'
  }
  if (!form.rules.some((rule) => rule.enabled && rule.type === 'SOFT')) {
    return '至少需要一条启用的软约束规则'
  }
  const codes = new Set<string>()
  for (const rule of form.rules) {
    if (!rule.code) {
      return '存在未选择规则代码的行'
    }
    if (codes.has(rule.code)) {
      return `规则 ${rule.code} 重复，同一策略下规则代码必须唯一`
    }
    codes.add(rule.code)
  }
  if (!softWeightOk.value) {
    return `软约束权重之和必须为 ${SOFT_WEIGHT_TOTAL}，当前为 ${formatWeight(softWeightSum.value)}`
  }
  if (dimensionWeightSum.value <= 0) {
    return '初排维度权重之和必须大于 0'
  }
  return null
}

function formatWeight(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function buildPayload(): StrategyCreateRequest {
  const orderedDimensions = [...form.dimensions]
    .filter((item) => Number(item.weight) > 0)
    .sort((left, right) => Number(right.weight) - Number(left.weight))
  return {
    name: form.name.trim(),
    direction: form.direction,
    strategyVersion: form.strategyVersion,
    effectiveFrom: form.effectiveFrom ?? '',
    effectiveTo: form.effectiveTo || null,
    initialRules: {
      sequence: orderedDimensions.map((item) => item.dimension),
      dimensions: form.dimensions.map((item) => ({ ...item })),
      defaultSetupMinutes: form.defaultSetupMinutes
    },
    applyCondition: {
      productLines: splitList(form.applyProductLines),
      orderSources: [...form.applyOrderSources]
    },
    rules: form.rules.map((rule, index) => ({
      ...rule,
      seqNo: index + 1,
      weight: rule.type === 'HARD' ? 0 : Number(rule.weight) || 0
    }))
  }
}

async function onSave() {
  const error = validate()
  validationError.value = error ?? ''
  if (error) {
    ElMessage.error(error)
    return
  }
  saving.value = true
  try {
    const payload = buildPayload()
    if (props.mode === 'edit' && props.strategy) {
      await updateStrategy(props.strategy.id, {
        ...payload,
        rules: payload.rules ?? [],
        version: props.strategy.version
      })
      ElMessage.success('策略已保存')
    } else {
      await createStrategy(payload)
      ElMessage.success('策略已创建')
    }
    emit('saved')
    emit('update:visible', false)
  } catch (exception) {
    validationError.value = exception instanceof Error ? exception.message : '策略保存失败'
  } finally {
    saving.value = false
  }
}

function onClose() {
  emit('update:visible', false)
}

function sumOf(items: InitialDimensionRule[]): number {
  return items.reduce((total, item) => total + (Number(item.weight) || 0), 0)
}

function softRuleCount(): number {
  return form.rules.filter((rule) => rule.type === 'SOFT').length
}

function mvpRuleMissing(): boolean {
  return MVP_SOFT_RULE_CODES.some((code) => !form.rules.some((rule) => rule.code === code))
}
</script>

<template>
  <el-drawer
    :model-value="props.visible"
    :title="title"
    size="760px"
    destroy-on-close
    @update:model-value="emit('update:visible', $event)"
  >
    <el-alert
      v-if="validationError"
      class="editor-alert"
      type="error"
      :title="validationError"
      :closable="false"
      show-icon
    />

    <el-form label-width="110px" @submit.prevent>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="策略名称" required>
            <el-input v-model="form.name" maxlength="128" placeholder="例如：交期优先策略" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="排产方向">
            <el-select v-model="form.direction">
              <el-option
                v-for="option in directionOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="业务版本">
            <el-input-number v-model="form.strategyVersion" :min="1" :step="1" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="默认换型(分)">
            <el-input-number v-model="form.defaultSetupMinutes" :min="0" :step="5" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="生效日期" required>
            <el-date-picker
              v-model="form.effectiveFrom"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择生效日期"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="失效日期">
            <el-date-picker
              v-model="form.effectiveTo"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="留空表示长期有效"
            />
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="适用产品线">
            <el-input
              v-model="form.applyProductLines"
              placeholder="多个产品线以英文逗号分隔，留空表示不限制"
            />
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="适用订单来源">
            <el-select v-model="form.applyOrderSources" multiple placeholder="留空表示不限制">
              <el-option
                v-for="option in orderSourceOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>

    <el-divider content-position="left">初排维度加权（BR-SS-001）</el-divider>
    <el-table :data="form.dimensions" border size="small">
      <el-table-column label="维度" min-width="120">
        <template #default="{ row }">
          {{
            INITIAL_DIMENSION_CATALOG.find((item) => item.dimension === row.dimension)?.label ??
            row.dimension
          }}
        </template>
      </el-table-column>
      <el-table-column label="方向" width="120">
        <template #default="{ row }">
          <el-select v-model="row.direction" size="small">
            <el-option label="升序 ASC" value="ASC" />
            <el-option label="降序 DESC" value="DESC" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="权重" width="180">
        <template #default="{ row }">
          <el-input-number v-model="row.weight" :min="0" :max="100" :step="1" size="small" />
        </template>
      </el-table-column>
    </el-table>
    <div class="weight-summary">
      初排维度权重和：<el-tag :type="dimensionWeightSum > 0 ? 'success' : 'danger'" size="small">
        {{ sumOf(form.dimensions) }}
      </el-tag>
      <span class="hint">（BR-SS-001-04：权重和不得为 0）</span>
    </div>

    <el-divider content-position="left">优化规则权重（C19：软约束权重和须为 100）</el-divider>
    <div class="weight-summary">
      <el-tag :type="softWeightOk ? 'success' : 'danger'">
        软约束权重和：{{ formatWeight(softWeightSum) }} / {{ SOFT_WEIGHT_TOTAL }}
      </el-tag>
      <span class="hint">
        （共 {{ softRuleCount() }} 条 SOFT 规则，MVP 8 条核心规则
        {{ mvpRuleMissing() ? '未全部覆盖' : '已覆盖' }}）
      </span>
      <el-button class="add-rule" size="small" type="primary" plain @click="addRule">
        新增规则
      </el-button>
    </div>

    <el-table :data="form.rules" border size="small" row-key="code">
      <el-table-column label="规则代码" min-width="220">
        <template #default="{ row }">
          <el-select v-model="row.code" size="small" filterable @change="onRuleCodeChange(row)">
            <el-option
              v-for="item in STRATEGY_RULE_CATALOG"
              :key="item.code"
              :label="`${item.code} · ${item.name}`"
              :value="item.code"
              :disabled="isCodeTaken(item.code, row)"
            />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="约束类型" width="130">
        <template #default="{ row }">
          <el-select v-model="row.type" size="small" @change="onRuleTypeChange(row)">
            <el-option label="软约束 SOFT" value="SOFT" />
            <el-option label="硬约束 HARD" value="HARD" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="权重" width="140">
        <template #default="{ row }">
          <el-input-number
            v-model="row.weight"
            :min="0"
            :max="100"
            :step="0.5"
            size="small"
            :disabled="row.type === 'HARD' || !row.enabled"
          />
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" size="small" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ $index }">
          <el-button link type="danger" @click="removeRule($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="drawer-footer">
      <el-button @click="onClose">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave"> 保存策略 </el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.editor-alert {
  margin-bottom: var(--space-3);
}

.weight-summary {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: var(--space-2) 0 var(--space-3);
  font-size: var(--font-sm);
}

.hint {
  color: var(--text-3);
  font-size: var(--font-xs);
}

.add-rule {
  margin-left: auto;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-2);
  margin-top: var(--space-5);
}
</style>
