/**
 * 枚举与状态标签映射。
 * 取值唯一来源：docs/db/schema.md 第 4.1 节 CHECK 取值；禁止在本文件新增未登记枚举。
 * 展示文案仅为中文翻译，不改变契约取值。
 */
export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export interface EnumOption {
  value: string
  label: string
  tagType: TagType
}

export const enumDefinitions = {
  materialType: [
    { value: 'FINISHED', label: '成品', tagType: 'primary' },
    { value: 'SEMI', label: '半成品', tagType: 'success' },
    { value: 'RAW', label: '原材料', tagType: 'info' },
    { value: 'AUX', label: '辅料', tagType: 'warning' },
    { value: 'PACK', label: '包装材料', tagType: 'info' }
  ],
  bomStatus: [
    { value: 'DRAFT', label: '草稿', tagType: 'info' },
    { value: 'ACTIVE', label: '生效', tagType: 'success' },
    { value: 'INACTIVE', label: '失效', tagType: 'info' }
  ],
  routingStatus: [
    { value: 'DRAFT', label: '草稿', tagType: 'info' },
    { value: 'ACTIVE', label: '生效', tagType: 'success' },
    { value: 'INACTIVE', label: '失效', tagType: 'info' }
  ],
  resourceType: [
    { value: 'PRIMARY', label: '主资源', tagType: 'primary' },
    { value: 'SECONDARY', label: '辅资源', tagType: 'success' },
    { value: 'HUMAN', label: '人力', tagType: 'warning' },
    { value: 'TOOL', label: '工具', tagType: 'info' }
  ],
  capacityMode: [
    { value: 'UNIT_PER_HOUR', label: '件/小时', tagType: 'primary' },
    { value: 'SECONDS_PER_UNIT', label: '秒/件', tagType: 'success' },
    { value: 'BATCH_SECONDS', label: '批次秒', tagType: 'warning' }
  ],
  calendarType: [
    { value: 'FACTORY', label: '全厂', tagType: 'primary' },
    { value: 'RESOURCE', label: '资源', tagType: 'success' },
    { value: 'SHIFT', label: '班次', tagType: 'info' }
  ],
  dependType: [
    { value: 'FS', label: '完成-开始', tagType: 'primary' },
    { value: 'SS', label: '开始-开始', tagType: 'success' },
    { value: 'FF', label: '完成-完成', tagType: 'warning' }
  ],
  orderSource: [
    { value: 'ERP_SYNC', label: 'ERP 同步', tagType: 'primary' },
    { value: 'MANUAL', label: '手工创建', tagType: 'success' },
    { value: 'AGENT', label: 'Agent 创建', tagType: 'warning' }
  ],
  orderPriority: [
    { value: 'URGENT', label: '加急', tagType: 'danger' },
    { value: 'HIGH', label: '高', tagType: 'warning' },
    { value: 'NORMAL', label: '普通', tagType: 'primary' },
    { value: 'LOW', label: '低', tagType: 'info' }
  ],
  orderStatus: [
    { value: 'DRAFT', label: '草稿', tagType: 'info' },
    { value: 'CONFIRMED', label: '已确认', tagType: 'primary' },
    { value: 'SCHEDULED', label: '已排产', tagType: 'warning' },
    { value: 'RELEASED', label: '已下达', tagType: 'success' },
    { value: 'IN_PROGRESS', label: '生产中', tagType: 'primary' },
    { value: 'COMPLETED', label: '已完成', tagType: 'success' },
    { value: 'CANCELLED', label: '已取消', tagType: 'info' }
  ],
  freezeStatus: [
    { value: 'UNFROZEN', label: '未冻结', tagType: 'info' },
    { value: 'FROZEN', label: '已冻结', tagType: 'warning' },
    { value: 'LOCKED_TIME_WINDOW', label: '锁定时间窗', tagType: 'danger' },
    { value: 'LOCKED_RESOURCE', label: '锁定资源', tagType: 'danger' }
  ],
  taskStatus: [
    { value: 'PLANNED', label: '已计划', tagType: 'primary' },
    { value: 'FROZEN', label: '已冻结', tagType: 'warning' },
    { value: 'IN_PROGRESS', label: '进行中', tagType: 'success' },
    { value: 'COMPLETED', label: '已完成', tagType: 'info' }
  ],
  strategyDirection: [
    { value: 'FORWARD', label: '正排', tagType: 'primary' },
    { value: 'BACKWARD', label: '倒排', tagType: 'success' },
    { value: 'BOTTLENECK', label: '瓶颈驱动', tagType: 'warning' }
  ],
  rulePhase: [
    { value: 'INITIAL', label: '初排', tagType: 'primary' },
    { value: 'OPTIMIZATION', label: '优化', tagType: 'success' }
  ],
  ruleType: [
    { value: 'HARD', label: '硬约束', tagType: 'danger' },
    { value: 'SOFT', label: '软约束', tagType: 'info' }
  ],
  solveEngine: [
    { value: 'TIMEFOLD', label: 'Timefold', tagType: 'primary' },
    { value: 'ORTOOLS', label: 'OR-Tools', tagType: 'success' },
    { value: 'RULE_BASED', label: '规则引擎', tagType: 'info' }
  ],
  /** 计划版本状态（C24 / V3 CHECK：DRAFT/REVIEW/PUBLISHED/ARCHIVED）。 */
  planStatus: [
    { value: 'DRAFT', label: '草稿', tagType: 'info' },
    { value: 'REVIEW', label: '评审中', tagType: 'warning' },
    { value: 'PUBLISHED', label: '已发布', tagType: 'success' },
    { value: 'ARCHIVED', label: '已归档', tagType: 'info' }
  ],
  solveTaskStatus: [
    { value: 'PENDING', label: '等待中', tagType: 'info' },
    { value: 'RUNNING', label: '求解中', tagType: 'primary' },
    { value: 'SOLVED', label: '已求解', tagType: 'success' },
    { value: 'FAILED', label: '失败', tagType: 'danger' },
    { value: 'CANCELLED', label: '已取消', tagType: 'info' },
    { value: 'TIMEOUT', label: '超时', tagType: 'warning' }
  ],
  sandboxType: [
    { value: 'BASELINE', label: '基线场景', tagType: 'primary' },
    { value: 'WHAT_IF', label: '推演场景', tagType: 'warning' },
    { value: 'ARCHIVED', label: '已归档', tagType: 'info' }
  ],
  taskAdjustType: [
    { value: 'SPLIT', label: '拆分', tagType: 'warning' },
    { value: 'MERGE', label: '合并', tagType: 'warning' },
    { value: 'MOVE', label: '移动', tagType: 'primary' },
    { value: 'LOCK', label: '锁定', tagType: 'danger' },
    { value: 'FREEZE', label: '冻结', tagType: 'warning' },
    { value: 'UNFREEZE', label: '解冻', tagType: 'success' }
  ],
  userStatus: [
    { value: 'ACTIVE', label: '启用', tagType: 'success' },
    { value: 'DISABLED', label: '禁用', tagType: 'info' },
    { value: 'LOCKED', label: '锁定', tagType: 'danger' }
  ],
  tenantStatus: [
    { value: 'ACTIVE', label: '启用', tagType: 'success' },
    { value: 'SUSPENDED', label: '暂停', tagType: 'warning' }
  ],
  customerPriority: [
    { value: 'VIP', label: 'VIP', tagType: 'danger' },
    { value: 'NORMAL', label: '普通', tagType: 'primary' },
    { value: 'LOW', label: '低', tagType: 'info' }
  ],
  supplierQualityGrade: [
    { value: 'A', label: 'A 级', tagType: 'success' },
    { value: 'B', label: 'B 级', tagType: 'warning' },
    { value: 'C', label: 'C 级', tagType: 'danger' }
  ]
} as const satisfies Record<string, readonly EnumOption[]>

export type EnumKey = keyof typeof enumDefinitions

export function getEnumOptions(key: EnumKey): readonly EnumOption[] {
  return enumDefinitions[key] as readonly EnumOption[]
}

export function getEnumOption(key: EnumKey, value?: string | null): EnumOption | undefined {
  if (value === undefined || value === null) {
    return undefined
  }
  return getEnumOptions(key).find((option) => option.value === value)
}

export function getEnumLabel(key: EnumKey, value?: string | null): string {
  return getEnumOption(key, value)?.label ?? value ?? '—'
}

export function getEnumTagType(key: EnumKey, value?: string | null): TagType {
  return getEnumOption(key, value)?.tagType ?? 'info'
}
