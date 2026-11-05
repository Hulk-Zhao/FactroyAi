import type { AuditAction, AuditResourceType } from '@/types/system'

/** C31 审计 action 闭集（16 个），顺序与契约一致，筛选下拉直接消费。 */
export const AUDIT_ACTIONS: Array<{ value: AuditAction; label: string }> = [
  { value: 'ORDER_FROZEN', label: '订单冻结' },
  { value: 'ORDER_PRIORITY_CHANGED', label: '订单优先级变更' },
  { value: 'ORDER_RELEASED', label: '订单下达' },
  { value: 'AUTH_LOGIN', label: '登录成功' },
  { value: 'AUTH_LOGIN_FAILED', label: '登录失败' },
  { value: 'USER_CREATED', label: '创建用户' },
  { value: 'USER_UPDATED', label: '修改用户' },
  { value: 'ROLE_CREATED', label: '创建角色' },
  { value: 'ROLE_PERMISSION_CHANGED', label: '角色权限变更' },
  { value: 'PLAN_PUBLISHED', label: '计划发布' },
  { value: 'PLAN_ROLLBACK', label: '计划回滚' },
  { value: 'PLAN_DISPATCHED', label: '计划下发' },
  { value: 'SANDBOX_CREATED', label: '场景创建' },
  { value: 'SANDBOX_APPLIED', label: '场景应用' },
  { value: 'IMPORT_EXECUTED', label: '导入执行' },
  { value: 'TASK_ADJUSTED', label: '任务调整' }
]

/** C31 resourceType 闭集（7 个）。 */
export const AUDIT_RESOURCE_TYPES: Array<{ value: AuditResourceType; label: string }> = [
  { value: 'USER', label: '用户' },
  { value: 'ROLE', label: '角色' },
  { value: 'ORDER', label: '订单' },
  { value: 'PLAN', label: '计划' },
  { value: 'SANDBOX', label: '场景' },
  { value: 'TASK', label: '任务' },
  { value: 'IMPORT', label: '导入' }
]

const ACTION_LABELS = new Map(AUDIT_ACTIONS.map((item) => [item.value, item.label]))
const RESOURCE_LABELS = new Map(AUDIT_RESOURCE_TYPES.map((item) => [item.value, item.label]))

export function auditActionLabel(action: string): string {
  return ACTION_LABELS.get(action as AuditAction) ?? action
}

export function auditResourceLabel(type: string): string {
  return RESOURCE_LABELS.get(type as AuditResourceType) ?? type
}
