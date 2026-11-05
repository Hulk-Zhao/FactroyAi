/**
 * 导入中心常量：实体与列映射、文件限制、错误码可读提示。
 * 列定义与 aps-service ImportEntity 保持同序同键，仅用于错误行字段定位与预览表头。
 */

export const IMPORT_MAX_FILE_BYTES = 5 * 1024 * 1024

export const IMPORT_MAX_ROWS = 5000

export const IMPORT_ACCEPTED_EXTENSION = '.xlsx'

export interface ImportEntityColumn {
  key: string
  header: string
  required?: boolean
}

export interface ImportEntityOption {
  slug: string
  label: string
  columns: ImportEntityColumn[]
}

/** 与后端 ImportEntity 一致的 10 类实体（C38 追加 customers）。 */
export const IMPORT_ENTITY_OPTIONS: ImportEntityOption[] = [
  {
    slug: 'customers',
    label: '客户',
    columns: [
      { key: 'code', header: '客户编码', required: true },
      { key: 'name', header: '客户名称', required: true },
      { key: 'priority', header: '优先级' },
      { key: 'leadTimeDays', header: '交付提前期(天)' },
      { key: 'paymentTerms', header: '付款条件' },
      { key: 'blacklisted', header: '是否黑名单' }
    ]
  },
  {
    slug: 'materials',
    label: '物料',
    columns: [
      { key: 'code', header: '物料编码', required: true },
      { key: 'name', header: '物料名称', required: true },
      { key: 'type', header: '物料类型', required: true },
      { key: 'spec', header: '规格型号' },
      { key: 'unit', header: '基本单位', required: true },
      { key: 'safetyStock', header: '安全库存' },
      { key: 'moq', header: '最小起订量' },
      { key: 'leadTimeDays', header: '采购提前期(天)' }
    ]
  },
  {
    slug: 'boms',
    label: 'BOM',
    columns: [
      { key: 'parentMaterialCode', header: '父件物料编码', required: true },
      { key: 'bomVersion', header: 'BOM版本', required: true },
      { key: 'effectiveDate', header: '生效日期', required: true },
      { key: 'expiryDate', header: '失效日期' },
      { key: 'isPanel', header: '是否拼板' },
      { key: 'panelSize', header: '拼板数量' },
      { key: 'componentMaterialCode', header: '子件物料编码', required: true },
      { key: 'componentQty', header: '子件数量', required: true },
      { key: 'scrapRate', header: '损耗率(%)' }
    ]
  },
  {
    slug: 'resources',
    label: '资源',
    columns: [
      { key: 'code', header: '资源编码', required: true },
      { key: 'name', header: '资源名称', required: true },
      { key: 'type', header: '资源类型', required: true },
      { key: 'capacityMode', header: '产能模式', required: true },
      { key: 'capacityValue', header: '产能值' },
      { key: 'batchCapacity', header: '批量' },
      { key: 'efficiency', header: '效率系数' },
      { key: 'setupSeconds', header: '换型工时(秒)' },
      { key: 'teardownSeconds', header: '收尾工时(秒)' }
    ]
  },
  {
    slug: 'calendars',
    label: '日历',
    columns: [
      { key: 'name', header: '日历名称', required: true },
      { key: 'calendarVersion', header: '日历版本', required: true },
      { key: 'type', header: '日历类型', required: true },
      { key: 'resourceCode', header: '资源编码' },
      { key: 'effectiveFrom', header: '生效日期', required: true },
      { key: 'effectiveTo', header: '失效日期' },
      { key: 'shiftsJson', header: '班次JSON', required: true },
      { key: 'holidaysJson', header: '节假日JSON' }
    ]
  },
  {
    slug: 'operationTemplates',
    label: '工序模版',
    columns: [
      { key: 'code', header: '工序编码', required: true },
      { key: 'name', header: '工序名称', required: true },
      { key: 'primaryResourceCode', header: '主资源编码' },
      { key: 'standardSeconds', header: '标准工时(秒)' },
      { key: 'setupSeconds', header: '换型工时(秒)' },
      { key: 'teardownSeconds', header: '收尾工时(秒)' },
      { key: 'templateVersion', header: '模版版本' }
    ]
  },
  {
    slug: 'routings',
    label: '工艺路线',
    columns: [
      { key: 'productCode', header: '产品物料编码', required: true },
      { key: 'routingVersion', header: '路线版本', required: true },
      { key: 'effectiveDate', header: '生效日期', required: true },
      { key: 'expiryDate', header: '失效日期' },
      { key: 'seqNo', header: '节点序号' },
      { key: 'fromSeqNo', header: '前驱节点序号' },
      { key: 'operationCode', header: '工序编码' },
      { key: 'primaryResourceCode', header: '主资源编码' }
    ]
  },
  {
    slug: 'orders',
    label: '订单',
    columns: [
      { key: 'orderNo', header: '订单号', required: true },
      { key: 'source', header: '订单来源' },
      { key: 'materialCode', header: '产品物料编码', required: true },
      { key: 'qty', header: '数量', required: true },
      { key: 'dueDate', header: '交期', required: true },
      { key: 'priority', header: '优先级' },
      { key: 'remark', header: '备注' },
      // C48：第 8 列可选；旧 7 列模板仍兼容，缺列按“未指定客户”处理。
      { key: 'customerCode', header: '客户编码' }
    ]
  },
  {
    slug: 'strategies',
    label: '策略',
    columns: [
      { key: 'name', header: '策略名称', required: true },
      { key: 'direction', header: '排产方向', required: true },
      { key: 'strategyVersion', header: '策略版本', required: true },
      { key: 'effectiveFrom', header: '生效日期', required: true },
      { key: 'effectiveTo', header: '失效日期' }
    ]
  },
  {
    slug: 'suppliers',
    label: '供应商',
    columns: [
      { key: 'code', header: '供应商编码', required: true },
      { key: 'name', header: '供应商名称', required: true },
      { key: 'leadTimeDays', header: '采购提前期(天)' },
      { key: 'moq', header: '最小起订量' },
      { key: 'qualityGrade', header: '质量等级' },
      { key: 'blacklisted', header: '是否黑名单' }
    ]
  }
]

/** 错误码语义提示；展示时叠加后端原始 message，不改变后端语义。 */
export const IMPORT_ERROR_HINTS: Record<number, string> = {
  40000: '请求参数不合法',
  40300: '无权访问该资源',
  40400: '资源不存在',
  40901: '任务状态冲突',
  42200: '校验未通过或文件超出限制'
}

export function findImportEntity(slug: string): ImportEntityOption | undefined {
  return IMPORT_ENTITY_OPTIONS.find((entity) => entity.slug === slug)
}

export function importColumnLabel(slug: string, field: string): string {
  if (!field || field === 'header') {
    return '表头'
  }
  return findImportEntity(slug)?.columns.find((column) => column.key === field)?.header ?? field
}
