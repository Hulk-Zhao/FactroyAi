/**
 * 契约 Mock 数据：字段严格按 docs/api/openapi/*.yaml 生成，
 * 枚举取值按 docs/db/schema.md 4.1 节 CHECK 取值。
 */
import type { AvailableTime, Bom, BomTreeNode, Material } from '@/types/materials'
import type { MrpAnalysisResult } from '@/types/mrp'
import type { Calendar } from '@/types/calendars'
import type { Resource } from '@/types/resources'
import type { OperationTemplate, Routing } from '@/types/routings'
import type { Order, Sandbox, Strategy } from '@/types/orders'
import type { SolveTaskDetail } from '@/types/solve'
import type { GanttOrderRow, GanttResourceRow, GanttTask } from '@/types/gantt'
import type { AuditLog, PermissionView, RoleView, SystemConfig, User } from '@/types/system'

const NOW = '2026-11-04T08:00:00Z'

const ID = {
  categoryElectrical: '11111111-1111-4111-8111-111111111111',
  supplierCell: '22222222-2222-4222-8222-222222222222',
  customerA: '33333333-3333-4333-8333-333333333333',
  resourceGroup: '44444444-4444-4444-8444-444444444444',
  calendar: '55555555-5555-4555-8555-555555555555',
  bom1: '66666666-6666-4666-8666-666666666666',
  bom2: '77777777-7777-4777-8777-777777777777',
  routing1: '88888888-8888-4888-8888-888888888888',
  routing2: '99999999-9999-4999-8999-999999999999',
  routing3: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  routing4: 'abababab-abab-4bab-8bab-abababababab',
  sandboxBaseline: '00000000-0000-0000-0000-000000000401',
  sandboxWhatIf: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  sandboxArchived: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  strategy1: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  strategy2: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  user1: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
  plan: '12121212-1212-4212-8212-121212121212'
} as const

/** C14：资源接口返回 id，Mock 使用稳定 UUID，避免每次刷新变化。 */
const RESOURCE_ID = {
  smt: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  age: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
  assy: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
  qc: '14141414-1414-4414-8414-141414141414',
  op: '15151515-1515-4515-8515-151515151515',
  tech: 'ffffffff-ffff-4fff-8fff-ffffffffffff',
  mill: '13131313-1313-4313-8313-131313131313'
} as const

/**
 * 客户主数据（与 seed-demo 的 CUS-001..004 对齐）：
 * 订单以 customerName + customerCode 展示业务名称，customerId 只作为外键保留，不在页面裸展示。
 */
const CUSTOMER = {
  northChina: {
    customerId: ID.customerA,
    customerCode: 'CUS-001',
    customerName: '华北储能客户 A'
  },
  eastChina: {
    customerId: '33333333-3333-4333-8333-333333333334',
    customerCode: 'CUS-002',
    customerName: '华东渠道客户 B'
  },
  southChina: {
    customerId: '33333333-3333-4333-8333-333333333335',
    customerCode: 'CUS-003',
    customerName: '华南系统集成客户 C'
  },
  southwest: {
    customerId: '33333333-3333-4333-8333-333333333336',
    customerCode: 'CUS-004',
    customerName: '西南分布式客户 D'
  }
} as const

/** C15：工序模版 UUID；节点通过 operationTemplateId 引用。 */
const TEMPLATE_ID = {
  smt: '1a1a1a1a-1a1a-4a1a-8a1a-1a1a1a1a1a1a',
  assy: '2b2b2b2b-2b2b-4b2b-8b2b-2b2b2b2b2b2b',
  age: '3c3c3c3c-3c3c-4c3c-8c3c-3c3c3c3c3c3c',
  qc: '4d4d4d4d-4d4d-4d4d-8d4d-4d4d4d4d4d4d'
} as const

export const materials: Material[] = [
  {
    code: 'FG-ESS-001',
    name: '工商业储能电池柜 ESS-100（液冷）',
    type: 'FINISHED',
    spec: '磷酸铁锂 1P52S，100kWh / 1000V，液冷',
    unit: '台',
    categoryId: ID.categoryElectrical,
    safetyStock: 5,
    moq: 10,
    leadTimeDays: 20,
    defaultSupplierId: null,
    extAttrs: { brand: 'HENGCE', tags: ['rohs', 'reach'] },
    industryAttrs: {
      configuration: '1P52S',
      energy_kwh: 100,
      cooling: '液冷',
      voltage_v: 1000,
      protection: 'IP55',
      standard: 'GB/T 36276'
    },
    version: 2,
    createdAt: '2026-10-01T02:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'SEMI-MOD-001',
    name: '1P16S 磷酸铁锂电池模组 51.2V/280Ah',
    type: 'SEMI',
    spec: '16 串 51.2V / 14.3kWh，激光焊接',
    unit: '组',
    categoryId: ID.categoryElectrical,
    safetyStock: 30,
    moq: 50,
    leadTimeDays: 5,
    defaultSupplierId: null,
    extAttrs: { production_line: 'MOD' },
    industryAttrs: {
      configuration: '1P16S',
      nominal_voltage_v: 51.2,
      capacity_ah: 280,
      energy_kwh: 14.3,
      welding: '激光焊接',
      busbar: '铝排'
    },
    version: 1,
    createdAt: '2026-10-02T02:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'SEMI-SMT-001',
    name: 'SMT 控制主板 PCBA（双面混装）',
    type: 'SEMI',
    spec: '双面混装 680 点，六层沉金板',
    unit: '块',
    categoryId: ID.categoryElectrical,
    safetyStock: 30,
    moq: 100,
    leadTimeDays: 3,
    defaultSupplierId: null,
    extAttrs: { production_line: 'SMT' },
    industryAttrs: {
      board_layers: 6,
      placement_points: 680,
      side: '双面',
      process: 'SMT 贴片+回流焊',
      test: 'AOI+ICT'
    },
    version: 1,
    createdAt: '2026-10-02T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'RAW-CELL-001',
    name: '磷酸铁锂方形电芯 3.2V/280Ah',
    type: 'RAW',
    spec: 'LF280K 方形铝壳，≥8000 次循环',
    unit: '只',
    categoryId: ID.categoryElectrical,
    safetyStock: 200,
    moq: 1000,
    leadTimeDays: 14,
    defaultSupplierId: ID.supplierCell,
    extAttrs: { customs_code: '8507.60' },
    industryAttrs: {
      material: '磷酸铁锂',
      cell_model: 'LF280K',
      nominal_capacity_ah: 280,
      nominal_voltage_v: 3.2,
      dimensions_mm: '173.9×71.7×207.2',
      grading_rule: '容量分档 A/B/C'
    },
    version: 3,
    createdAt: '2026-09-20T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'RAW-BMS-001',
    name: 'BMS 主控采集模块 16S（BCU-16S）',
    type: 'RAW',
    spec: '16 串，主动均衡 5A，CAN 2.0B',
    unit: '套',
    categoryId: ID.categoryElectrical,
    safetyStock: 0,
    moq: 50,
    leadTimeDays: 21,
    defaultSupplierId: ID.supplierCell,
    industryAttrs: {
      cell_count: 16,
      balancing: '主动均衡 5A',
      communication: 'CAN 2.0B',
      sampling_precision_mv: 2,
      functional_safety: 'ISO 26262 ASIL-B'
    },
    version: 1,
    createdAt: '2026-09-24T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'RAW-BMS-002',
    name: 'BMS 主控采集模块 32S（BCU-32S）',
    type: 'RAW',
    spec: '32 串，主动均衡 5A，CAN FD',
    unit: '套',
    categoryId: ID.categoryElectrical,
    safetyStock: 0,
    moq: 50,
    leadTimeDays: 30,
    defaultSupplierId: ID.supplierCell,
    industryAttrs: {
      cell_count: 32,
      balancing: '主动均衡 5A',
      communication: 'CAN FD',
      sampling_precision_mv: 2,
      functional_safety: 'ISO 26262 ASIL-B'
    },
    version: 1,
    createdAt: '2026-09-25T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'RAW-ELEC-002',
    name: '高压直流熔断器 FWH-500A',
    type: 'RAW',
    spec: 'DC 1000V / 500A，IEC 60269-7',
    unit: '只',
    categoryId: ID.categoryElectrical,
    safetyStock: 20,
    moq: 500,
    leadTimeDays: 10,
    defaultSupplierId: ID.supplierCell,
    industryAttrs: {
      rated_voltage_v: 1000,
      rated_current_a: 500,
      standard: 'IEC 60269-7',
      breaking_capacity_ka: 50,
      mounting: '螺栓式'
    },
    version: 1,
    createdAt: '2026-09-26T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'RAW-THM-001',
    name: 'EC 离心散热风机模组 48V',
    type: 'RAW',
    spec: '48V / 8500rpm，IP68，Φ250',
    unit: '套',
    categoryId: ID.categoryElectrical,
    safetyStock: 10,
    moq: 100,
    leadTimeDays: 15,
    defaultSupplierId: null,
    industryAttrs: {
      voltage_v: 48,
      speed_rpm: 8500,
      protection: 'IP68',
      diameter_mm: 250,
      airflow_m3h: 1200,
      noise_db: 58
    },
    version: 1,
    createdAt: '2026-09-27T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'RAW-THM-002',
    name: 'EC 轴流散热风机模组 48V（替代）',
    type: 'RAW',
    spec: '48V / 7200rpm，IP68，Φ250',
    unit: '套',
    categoryId: ID.categoryElectrical,
    safetyStock: 20,
    moq: 100,
    leadTimeDays: 10,
    defaultSupplierId: null,
    industryAttrs: {
      alternate_of: 'RAW-THM-001',
      voltage_v: 48,
      speed_rpm: 7200,
      protection: 'IP68',
      diameter_mm: 250,
      airflow_m3h: 1050,
      noise_db: 56
    },
    version: 1,
    createdAt: '2026-09-28T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'AUX-CHEM-001',
    name: '乙二醇冷却液（50% 水溶液）',
    type: 'AUX',
    spec: '50% 乙二醇水溶液，-40℃~+120℃，20L/桶',
    unit: '桶',
    categoryId: null,
    safetyStock: 20,
    moq: 200,
    leadTimeDays: 5,
    defaultSupplierId: null,
    industryAttrs: {
      concentration_pct: 50,
      freezing_point_c: -40,
      boiling_point_c: 120,
      volume_l: 20,
      standard: 'GB 29743'
    },
    version: 1,
    createdAt: '2026-09-29T03:00:00Z',
    updatedAt: NOW
  },
  {
    code: 'RAW-PKG-001',
    name: '铭牌与安全标识套件',
    type: 'RAW',
    spec: 'PET 耐候，UL94 V-0，含铭牌/警示/追溯码',
    unit: '套',
    categoryId: null,
    safetyStock: 200,
    moq: 5000,
    leadTimeDays: 5,
    defaultSupplierId: null,
    industryAttrs: {
      material: 'PET',
      flame_retardant: 'UL94 V-0',
      weathering: '户外 5 年',
      items: '铭牌+警示标签+追溯二维码'
    },
    version: 1,
    createdAt: '2026-09-30T03:00:00Z',
    updatedAt: NOW
  }
]

export const boms: Bom[] = [
  {
    id: ID.bom1,
    parentMaterialCode: 'FG-ESS-001',
    bomVersion: 'V1.0',
    effectiveDate: '2026-10-01',
    expiryDate: null,
    status: 'ACTIVE',
    isPanel: false,
    panelSize: null,
    items: [
      {
        materialCode: 'SEMI-MOD-001',
        qtyPerUnit: 1,
        scrapRate: 0.01,
        position: 'A01',
        substitutes: [],
        isPhantom: false
      },
      {
        materialCode: 'SEMI-SMT-001',
        qtyPerUnit: 1,
        scrapRate: 0.005,
        position: 'A02',
        substitutes: [],
        isPhantom: false
      },
      {
        materialCode: 'RAW-BMS-002',
        qtyPerUnit: 1,
        scrapRate: 0,
        position: 'A03',
        substitutes: [],
        isPhantom: false
      },
      {
        materialCode: 'RAW-ELEC-002',
        qtyPerUnit: 2,
        scrapRate: 0,
        position: 'A04',
        substitutes: [],
        isPhantom: false
      },
      {
        materialCode: 'RAW-THM-001',
        qtyPerUnit: 2,
        scrapRate: 0.01,
        position: 'A05',
        substitutes: ['RAW-THM-002'],
        isPhantom: false
      },
      {
        materialCode: 'AUX-CHEM-001',
        qtyPerUnit: 2,
        scrapRate: 0,
        position: 'A06',
        substitutes: [],
        isPhantom: false
      },
      {
        materialCode: 'RAW-PKG-001',
        qtyPerUnit: 1,
        scrapRate: 0,
        position: 'A07',
        substitutes: [],
        isPhantom: false
      }
    ],
    version: 1
  },
  {
    id: ID.bom2,
    parentMaterialCode: 'SEMI-MOD-001',
    bomVersion: 'V1.0',
    effectiveDate: '2026-10-01',
    expiryDate: null,
    status: 'ACTIVE',
    isPanel: false,
    panelSize: null,
    items: [
      {
        materialCode: 'RAW-CELL-001',
        qtyPerUnit: 16,
        scrapRate: 0.005,
        position: 'CELL-01',
        substitutes: [],
        isPhantom: false
      },
      {
        materialCode: 'RAW-BMS-001',
        qtyPerUnit: 1,
        scrapRate: 0,
        position: 'BMS-01',
        substitutes: [],
        isPhantom: false
      }
    ],
    version: 2
  }
]

export const bomTree: BomTreeNode = {
  bomId: ID.bom1,
  parentMaterialCode: 'FG-ESS-001',
  materialName: '工商业储能电池柜 ESS-100（液冷）',
  qtyPerUnit: 1,
  level: 1,
  children: [
    {
      bomId: ID.bom2,
      parentMaterialCode: 'SEMI-MOD-001',
      materialName: '1P16S 磷酸铁锂电池模组 51.2V/280Ah',
      qtyPerUnit: 1,
      level: 2,
      children: [
        {
          bomId: ID.bom2,
          parentMaterialCode: 'RAW-CELL-001',
          materialName: '磷酸铁锂方形电芯 3.2V/280Ah',
          qtyPerUnit: 16,
          level: 3,
          children: []
        },
        {
          bomId: ID.bom2,
          parentMaterialCode: 'RAW-BMS-001',
          materialName: 'BMS 主控采集模块 16S（BCU-16S）',
          qtyPerUnit: 1,
          level: 3,
          children: []
        }
      ]
    }
  ]
}

export const resources: Resource[] = [
  {
    id: RESOURCE_ID.smt,
    code: 'RES-SMT-01',
    name: '高速贴片线 SMT-01',
    type: 'PRIMARY',
    groupId: null,
    capacityMode: 'UNIT_PER_HOUR',
    capacityValue: 120,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 1800,
    teardownSeconds: 0,
    maintenancePlan: {
      periodic: [{ cycle: 'WEEKLY', duration_minutes: 120 }],
      next_due_at: '2026-11-10T00:00:00Z',
      note: '每周三保养'
    },
    industryAttrs: {
      maker: 'FUJI',
      model: 'NXT III',
      feeder_slots: 180,
      workshop: 'SMT 贴片车间',
      process_function: '高速贴片（CHIP/QFP/QFN）'
    },
    version: 1
  },
  {
    id: RESOURCE_ID.age,
    code: 'RES-AGING-01',
    name: '老化房 AGING-01（20 工位）',
    type: 'PRIMARY',
    groupId: null,
    capacityMode: 'BATCH_SECONDS',
    capacityValue: 7200,
    batchCapacity: 20,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 3600,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {
      workshop: '测试与老化中心',
      process_function: '整柜充放电老化',
      capacity_text: '20 工位/批，单批 2 小时',
      temperature_range: '25~55℃'
    },
    version: 1
  },
  {
    id: RESOURCE_ID.assy,
    code: 'RES-PACK-01',
    name: 'PACK 总装线 PACK-01',
    type: 'PRIMARY',
    groupId: null,
    capacityMode: 'UNIT_PER_HOUR',
    capacityValue: 4,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 2400,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {
      workshop: 'PACK 总装车间',
      process_function: '模组入柜、铜排装配与气密测试',
      capacity_text: '4 台/小时'
    },
    version: 1
  },
  {
    id: RESOURCE_ID.qc,
    code: 'RES-EOL-01',
    name: 'EOL 终检线 EOL-01',
    type: 'PRIMARY',
    groupId: null,
    capacityMode: 'UNIT_PER_HOUR',
    capacityValue: 20,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 1200,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {
      workshop: '测试与老化中心',
      process_function: 'EOL 绝缘、耐压与功能终检',
      capacity_text: '20 台/小时'
    },
    version: 1
  },
  {
    id: RESOURCE_ID.op,
    code: 'RES-MODULE-01',
    name: '模组装配线 MOD-01',
    type: 'PRIMARY',
    groupId: null,
    capacityMode: 'UNIT_PER_HOUR',
    capacityValue: 6,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 1800,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {
      workshop: '模组装配车间',
      process_function: '电芯堆叠、激光焊接与模组下线',
      capacity_text: '6 组/小时'
    },
    version: 1
  },
  {
    id: RESOURCE_ID.tech,
    code: 'RES-TECH-01',
    name: '装调技师组 TECH-01（6 人）',
    type: 'HUMAN',
    groupId: null,
    capacityMode: 'UNIT_PER_HOUR',
    capacityValue: 4,
    batchCapacity: null,
    efficiency: 1,
    calendarId: ID.calendar,
    setupSeconds: 600,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {
      headcount: 6,
      workshop: '全厂',
      process_function: '人工装配、调试与返修',
      capacity_text: '6 人，按 4 台/小时折算'
    },
    version: 1
  },
  {
    // 已停用资源：用于验证状态筛选与停用交互（status 为前端兼容字段，契约缺口）
    id: RESOURCE_ID.mill,
    code: 'RES-MILL-101',
    name: '普铣 101 号（已停用）',
    type: 'TOOL',
    groupId: null,
    capacityMode: 'SECONDS_PER_UNIT',
    capacityValue: 0,
    batchCapacity: null,
    efficiency: 1,
    calendarId: null,
    setupSeconds: 0,
    teardownSeconds: 0,
    maintenancePlan: {},
    industryAttrs: {},
    status: 'INACTIVE',
    version: 2
  }
]

export const calendars: Calendar[] = [
  {
    id: ID.calendar,
    name: '全厂单班制日历（08:00-20:00，含年度检修）',
    calendarVersion: 1,
    type: 'FACTORY',
    resourceId: null,
    resourceGroupId: null,
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    shifts: [{ startTime: '08:00', endTime: '20:00', daysOfWeek: [1, 2, 3, 4, 5, 6] }],
    holidays: [
      { date: '2026-10-01', name: '国庆节', kind: 'LEGAL' },
      { date: '2026-11-10', name: '设备年度检修', kind: 'COMPANY' }
    ],
    version: 1
  },
  {
    id: '55555555-5555-4555-8555-555555555556',
    name: 'SMT 贴片车间两班制日历',
    calendarVersion: 2,
    type: 'SHIFT',
    resourceId: null,
    resourceGroupId: ID.resourceGroup,
    effectiveFrom: '2026-11-01',
    effectiveTo: null,
    shifts: [
      { startTime: '08:00', endTime: '20:00', daysOfWeek: [1, 2, 3, 4, 5, 6] },
      { startTime: '20:00', endTime: '08:00', daysOfWeek: [1, 2, 3, 4, 5] }
    ],
    holidays: [],
    version: 1
  },
  {
    // 历史日历：effectiveTo 早于今天，Mock 更新返回 403，前端禁用编辑
    id: '55555555-5555-4555-8555-555555555557',
    name: '包装车间两班制日历（2025 历史版本）',
    calendarVersion: 1,
    type: 'SHIFT',
    resourceId: null,
    resourceGroupId: ID.resourceGroup,
    effectiveFrom: '2025-01-01',
    effectiveTo: '2025-12-31',
    shifts: [
      { startTime: '08:00', endTime: '20:00', daysOfWeek: [1, 2, 3, 4, 5, 6] },
      { startTime: '20:00', endTime: '08:00', daysOfWeek: [1, 2, 3, 4, 5] }
    ],
    holidays: [{ date: '2025-10-01', name: '国庆节', kind: 'LEGAL' }],
    version: 1
  },
  {
    // 待生效日历：校验未来版本编辑与生效状态标签
    id: '55555555-5555-4555-8555-555555555558',
    name: '包装车间两班制日历（2026 旺季版本）',
    calendarVersion: 3,
    type: 'SHIFT',
    resourceId: null,
    resourceGroupId: ID.resourceGroup,
    effectiveFrom: '2026-12-01',
    effectiveTo: null,
    shifts: [{ startTime: '08:00', endTime: '20:00', daysOfWeek: [1, 2, 3, 4, 5, 6] }],
    holidays: [],
    version: 1
  }
]

export const availableTime: AvailableTime = {
  items: [
    { startTime: '2026-11-05T00:00:00Z', endTime: '2026-11-05T12:00:00Z', minutes: 720 },
    { startTime: '2026-11-06T00:00:00Z', endTime: '2026-11-06T12:00:00Z', minutes: 720 }
  ]
}

export const operationTemplates: OperationTemplate[] = [
  {
    id: TEMPLATE_ID.smt,
    code: 'OP-SMT-01',
    name: 'SMT 贴片',
    templateVersion: 1,
    primaryResourceId: RESOURCE_ID.smt,
    primaryResourceGroupId: null,
    secondaryResourceIds: [],
    inputMaterialIds: [],
    outputMaterialId: null,
    standardSeconds: 60,
    setupSeconds: 30,
    teardownSeconds: 0,
    version: 1
  },
  {
    id: TEMPLATE_ID.assy,
    code: 'OP-MOD-01',
    name: '模组装配',
    templateVersion: 1,
    primaryResourceId: RESOURCE_ID.op,
    primaryResourceGroupId: null,
    secondaryResourceIds: [],
    inputMaterialIds: [],
    outputMaterialId: null,
    standardSeconds: 30,
    setupSeconds: 10,
    teardownSeconds: 0,
    version: 1
  },
  {
    id: TEMPLATE_ID.age,
    code: 'OP-AGE-01',
    name: '老化测试',
    templateVersion: 1,
    primaryResourceId: RESOURCE_ID.age,
    primaryResourceGroupId: null,
    secondaryResourceIds: [],
    inputMaterialIds: [],
    outputMaterialId: null,
    standardSeconds: 600,
    setupSeconds: 30,
    teardownSeconds: 0,
    version: 1
  },
  {
    id: TEMPLATE_ID.qc,
    code: 'OP-EOL-01',
    name: 'EOL 终检',
    templateVersion: 1,
    primaryResourceId: RESOURCE_ID.qc,
    primaryResourceGroupId: null,
    secondaryResourceIds: [],
    inputMaterialIds: [],
    outputMaterialId: null,
    standardSeconds: 20,
    setupSeconds: 10,
    teardownSeconds: 0,
    version: 1
  }
]

/**
 * 工艺路线 DAG Mock：
 * - FG-ESS-001 同时存在 INACTIVE（V0.9）/ ACTIVE（V1.0）/ DRAFT（V1.1），覆盖版本切换与只读态；
 * - V1.1 草稿内置环、缺模版、seq_no 冲突、重复单向边、孤立节点，用于前端就地校验演示；
 * - SEMI-MOD-001 V2.0 为正常草稿链。
 */
export const routings: Routing[] = [
  {
    id: ID.routing2,
    productCode: 'FG-ESS-001',
    productName: '工商业储能电池柜 ESS-100（液冷）',
    routingVersion: 'V1.1',
    effectiveDate: '2026-11-10',
    expiryDate: null,
    status: 'DRAFT',
    nodes: [
      {
        id: 'n-smt-11',
        seqNo: 10,
        operationTemplateId: TEMPLATE_ID.smt,
        operationCode: 'OP-SMT-01',
        operationName: 'SMT 贴片',
        standardSeconds: 60,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.smt,
        primaryResourceCode: 'RES-SMT-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 80, y: 80 }
      },
      {
        id: 'n-assy-11',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.assy,
        operationCode: 'OP-ASSY-01',
        operationName: '模组装配',
        standardSeconds: 30,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-PACK-01',
        secondaryResourceIds: [RESOURCE_ID.op],
        optional: false,
        position: { x: 380, y: 80 }
      },
      {
        id: 'n-age-11',
        seqNo: 30,
        operationTemplateId: null,
        operationCode: 'OP-AGE-01',
        operationName: '老化测试（待补模版）',
        standardSeconds: 600,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.age,
        primaryResourceCode: 'RES-AGING-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 680, y: 80 }
      },
      {
        id: 'n-qc-11',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.qc,
        operationCode: 'OP-QC-01',
        operationName: 'EOL 终检（seq 冲突）',
        standardSeconds: 20,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-PACK-01',
        secondaryResourceIds: [RESOURCE_ID.qc],
        optional: false,
        position: { x: 380, y: 260 }
      },
      {
        id: 'n-pack-11',
        seqNo: 40,
        operationTemplateId: TEMPLATE_ID.qc,
        operationCode: 'OP-QC-01',
        operationName: 'EOL 终检（孤立节点）',
        standardSeconds: 20,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-PACK-01',
        secondaryResourceIds: [],
        optional: true,
        position: { x: 680, y: 260 }
      }
    ],
    edges: [
      {
        id: 'e-smt-assy-11',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'FS',
        lagMinutes: 0
      },
      {
        id: 'e-assy-age-11',
        fromSeqNo: 20,
        toSeqNo: 30,
        dependType: 'FS',
        lagMinutes: 15
      },
      {
        id: 'e-age-smt-11',
        fromSeqNo: 30,
        toSeqNo: 10,
        dependType: 'FS',
        lagMinutes: 0
      },
      {
        id: 'e-smt-assy-11-dup',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'FS',
        lagMinutes: 0
      }
    ],
    version: 1,
    updatedAt: NOW
  },
  {
    id: ID.routing1,
    productCode: 'FG-ESS-001',
    productName: '工商业储能电池柜 ESS-100（液冷）',
    routingVersion: 'V1.0',
    effectiveDate: '2026-10-01',
    expiryDate: null,
    status: 'ACTIVE',
    nodes: [
      {
        id: 'n-smt-10',
        seqNo: 10,
        operationTemplateId: TEMPLATE_ID.smt,
        operationCode: 'OP-SMT-01',
        operationName: 'SMT 贴片',
        standardSeconds: 60,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.smt,
        primaryResourceCode: 'RES-SMT-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 80, y: 80 }
      },
      {
        id: 'n-assy-10',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.assy,
        operationCode: 'OP-ASSY-01',
        operationName: '模组装配',
        standardSeconds: 30,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-PACK-01',
        secondaryResourceIds: [RESOURCE_ID.op],
        optional: false,
        position: { x: 380, y: 80 }
      },
      {
        id: 'n-age-10',
        seqNo: 30,
        operationTemplateId: TEMPLATE_ID.age,
        operationCode: 'OP-AGE-01',
        operationName: '老化测试',
        standardSeconds: 600,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.age,
        primaryResourceCode: 'RES-AGING-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 680, y: 80 }
      }
    ],
    edges: [
      {
        id: 'e-smt-assy-10',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'FS',
        lagMinutes: 0
      },
      {
        id: 'e-assy-age-10',
        fromSeqNo: 20,
        toSeqNo: 30,
        dependType: 'FS',
        lagMinutes: 30
      }
    ],
    version: 3,
    updatedAt: NOW
  },
  {
    id: ID.routing4,
    productCode: 'FG-ESS-001',
    productName: '工商业储能电池柜 ESS-100（液冷）',
    routingVersion: 'V0.9',
    effectiveDate: '2026-08-01',
    expiryDate: '2026-10-01',
    status: 'INACTIVE',
    nodes: [
      {
        id: 'n-smt-09',
        seqNo: 10,
        operationTemplateId: TEMPLATE_ID.smt,
        operationCode: 'OP-SMT-01',
        operationName: 'SMT 贴片',
        standardSeconds: 60,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.smt,
        primaryResourceCode: 'RES-SMT-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 80, y: 80 }
      },
      {
        id: 'n-assy-09',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.assy,
        operationCode: 'OP-ASSY-01',
        operationName: '模组装配',
        standardSeconds: 30,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-PACK-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 380, y: 80 }
      }
    ],
    edges: [
      {
        id: 'e-smt-assy-09',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'SS',
        lagMinutes: 10
      }
    ],
    version: 2,
    updatedAt: '2026-09-20T08:00:00Z'
  },
  {
    id: ID.routing3,
    productCode: 'SEMI-MOD-001',
    productName: '1P16S 磷酸铁锂电池模组 51.2V/280Ah',
    routingVersion: 'V2.0',
    effectiveDate: '2026-11-10',
    expiryDate: null,
    status: 'DRAFT',
    nodes: [
      {
        id: 'n-smt-21',
        seqNo: 10,
        operationTemplateId: TEMPLATE_ID.smt,
        operationCode: 'OP-SMT-01',
        operationName: 'SMT 贴片',
        standardSeconds: 60,
        setupSeconds: 30,
        primaryResourceId: RESOURCE_ID.smt,
        primaryResourceCode: 'RES-SMT-01',
        secondaryResourceIds: [],
        optional: false,
        position: { x: 80, y: 80 }
      },
      {
        id: 'n-assy-21',
        seqNo: 20,
        operationTemplateId: TEMPLATE_ID.assy,
        operationCode: 'OP-ASSY-01',
        operationName: '模组装配',
        standardSeconds: 30,
        setupSeconds: 10,
        primaryResourceId: RESOURCE_ID.assy,
        primaryResourceCode: 'RES-PACK-01',
        secondaryResourceIds: [RESOURCE_ID.op],
        optional: false,
        position: { x: 380, y: 80 }
      }
    ],
    edges: [
      {
        id: 'e-smt-assy-21',
        fromSeqNo: 10,
        toSeqNo: 20,
        dependType: 'FS',
        lagMinutes: 0
      }
    ],
    version: 1,
    updatedAt: NOW
  }
]

export const orders: Order[] = [
  {
    orderNo: 'DM-SO-1001',
    materialCode: 'FG-ESS-001',
    qty: 12,
    status: 'CONFIRMED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-12',
    priority: 'URGENT',
    source: 'ERP_SYNC',
    ...CUSTOMER.northChina,
    routingId: ID.routing1,
    remark: '华北储能客户首批交付，齐套可排',
    extAttrs: { sales_region: '华北', customer_po: 'PO-NC-26-1001' },
    version: 2,
    createdAt: '2026-11-01T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'DM-SO-1002',
    materialCode: 'FG-ESS-001',
    qty: 24,
    status: 'CONFIRMED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-15',
    priority: 'URGENT',
    source: 'ERP_SYNC',
    ...CUSTOMER.northChina,
    routingId: ID.routing1,
    remark: '华北储能客户追加订单，齐套可排',
    extAttrs: { sales_region: '华北', customer_po: 'PO-NC-26-1002' },
    version: 1,
    createdAt: '2026-11-01T02:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'DM-SO-1003',
    materialCode: 'FG-ESS-001',
    qty: 18,
    status: 'CONFIRMED',
    freezeStatus: 'FROZEN',
    dueDate: '2026-11-08',
    priority: 'HIGH',
    source: 'ERP_SYNC',
    ...CUSTOMER.eastChina,
    routingId: ID.routing1,
    remark: '华东渠道客户已冻结交期，不可移动',
    extAttrs: { sales_region: '华东', customer_po: 'PO-EC-26-1003' },
    version: 3,
    createdAt: '2026-11-02T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'DM-SO-1004',
    materialCode: 'SEMI-MOD-001',
    qty: 32,
    status: 'CONFIRMED',
    freezeStatus: 'LOCKED_RESOURCE',
    dueDate: '2026-11-12',
    priority: 'HIGH',
    source: 'ERP_SYNC',
    ...CUSTOMER.southChina,
    routingId: ID.routing2,
    remark: '华南客户指定模组线与总装线',
    extAttrs: { sales_region: '华南', customer_po: 'PO-SC-26-1004' },
    version: 2,
    createdAt: '2026-11-02T03:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'DM-SO-1005',
    materialCode: 'SEMI-SMT-001',
    qty: 60,
    status: 'SCHEDULED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-20',
    priority: 'NORMAL',
    source: 'MANUAL',
    ...CUSTOMER.southwest,
    routingId: null,
    remark: 'SMT 控制主板备件补单',
    extAttrs: {},
    version: 1,
    createdAt: '2026-11-03T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'DM-SO-1006',
    materialCode: 'FG-ESS-001',
    qty: 20,
    status: 'IN_PROGRESS',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-06',
    priority: 'URGENT',
    source: 'ERP_SYNC',
    ...CUSTOMER.southwest,
    routingId: ID.routing1,
    remark: '西南分布式客户在制订单，SMT 已完成',
    extAttrs: { sales_region: '西南', customer_po: 'PO-WC-26-1006' },
    version: 4,
    createdAt: '2026-10-28T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'DM-SO-1007',
    materialCode: 'FG-ESS-001',
    qty: 30,
    status: 'RELEASED',
    freezeStatus: 'LOCKED_TIME_WINDOW',
    dueDate: '2026-11-10',
    priority: 'LOW',
    source: 'MANUAL',
    ...CUSTOMER.eastChina,
    routingId: ID.routing1,
    remark: '已下发车间，时间窗锁定',
    extAttrs: {},
    version: 2,
    createdAt: '2026-10-29T01:00:00Z',
    updatedAt: NOW
  },
  {
    orderNo: 'DM-SO-1008',
    materialCode: 'FG-ESS-001',
    qty: 90,
    status: 'CONFIRMED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-18',
    priority: 'HIGH',
    source: 'ERP_SYNC',
    ...CUSTOMER.northChina,
    routingId: ID.routing1,
    remark: '华北客户大批量订单，齐套可排',
    extAttrs: { sales_region: '华北', customer_po: 'PO-NC-26-1008' },
    version: 1,
    createdAt: NOW,
    updatedAt: NOW
  },
  {
    orderNo: 'DM-SO-1009',
    materialCode: 'SEMI-MOD-001',
    qty: 200,
    status: 'COMPLETED',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-03',
    priority: 'NORMAL',
    source: 'MANUAL',
    ...CUSTOMER.southChina,
    routingId: ID.routing2,
    remark: '历史订单，已完工入库',
    extAttrs: {},
    version: 4,
    createdAt: '2026-10-28T01:00:00Z',
    updatedAt: NOW
  },
  {
    // 确认失败样例：SEMI-MOD-001 只有 DRAFT 工艺路线，触发 C8 的 42200。
    orderNo: 'DM-SO-1010',
    materialCode: 'SEMI-SMT-001',
    qty: 40,
    status: 'DRAFT',
    freezeStatus: 'UNFROZEN',
    dueDate: '2026-11-25',
    priority: 'NORMAL',
    source: 'MANUAL',
    ...CUSTOMER.eastChina,
    routingId: null,
    remark: '华东渠道样机订单，待评审',
    extAttrs: {},
    version: 2,
    createdAt: NOW,
    updatedAt: NOW
  }
]

export const strategies: Strategy[] = [
  {
    id: ID.strategy1,
    name: '交期优先策略',
    direction: 'BACKWARD',
    strategyVersion: 1,
    effectiveFrom: '2026-10-01',
    effectiveTo: null,
    initialRules: {
      sequence: ['DUE_DATE', 'PRIORITY'],
      dimensions: [
        { dimension: 'DUE_DATE', direction: 'ASC', weight: 40 },
        { dimension: 'PRIORITY', direction: 'DESC', weight: 30 },
        { dimension: 'CUSTOMER_LEVEL', direction: 'DESC', weight: 15 },
        { dimension: 'INVENTORY_STATUS', direction: 'ASC', weight: 10 },
        { dimension: 'RELEASE_TIME', direction: 'ASC', weight: 5 }
      ],
      defaultSetupMinutes: 30
    },
    optimizationRules: {
      weights: { tardiness: 10, setup: 3, utilization: 1 },
      rules: [{ code: 'MIN_SETUP_TIME', type: 'SOFT', weight: 15 }]
    },
    applyCondition: { productLines: ['PACK-A'], orderSources: ['ERP_SYNC'] },
    rules: [
      {
        code: 'MIN_TARDINESS',
        name: '最小超期',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 1
      },
      {
        code: 'EDD',
        name: '交期优先',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 2
      },
      {
        code: 'SPT',
        name: '短任务优先',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 3
      },
      {
        code: 'SAME_PRODUCT_CONTINUOUS',
        name: '同品连续生产',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 4
      },
      {
        code: 'MIN_SETUP_TIME',
        name: '最小换型时长',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 5
      },
      {
        code: 'BALANCE_LOAD',
        name: '均衡负载率',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 6
      },
      {
        code: 'PRIORITY_RESPECT',
        name: '尊重订单优先级',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 7
      },
      {
        code: 'MIN_MAKESPAN',
        name: '最小总完工时间',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 8
      }
    ],
    version: 1
  },
  {
    id: ID.strategy2,
    name: '瓶颈产能优先',
    direction: 'BOTTLENECK',
    strategyVersion: 2,
    effectiveFrom: '2026-11-01',
    effectiveTo: null,
    initialRules: { sequence: ['PRIORITY', 'DUE_DATE'], defaultSetupMinutes: 45 },
    optimizationRules: { weights: { utilization: 10, tardiness: 5 } },
    applyCondition: { productLines: ['SMT-A'] },
    rules: [
      {
        code: 'MIN_TARDINESS',
        name: '最小超期',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 1
      },
      {
        code: 'EDD',
        name: '交期优先',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 2
      },
      {
        code: 'SPT',
        name: '短任务优先',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 3
      },
      {
        code: 'SAME_PRODUCT_CONTINUOUS',
        name: '同品连续生产',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 4
      },
      {
        code: 'MIN_SETUP_TIME',
        name: '最小换型时长',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 5
      },
      {
        code: 'BALANCE_LOAD',
        name: '均衡负载率',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 6
      },
      {
        code: 'PRIORITY_RESPECT',
        name: '尊重订单优先级',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 10,
        enabled: true,
        seqNo: 7
      },
      {
        code: 'MIN_MAKESPAN',
        name: '最小总完工时间',
        phase: 'OPTIMIZATION',
        type: 'SOFT',
        weight: 15,
        enabled: true,
        seqNo: 8
      }
    ],
    version: 2
  }
]

export const sandboxes: Sandbox[] = [
  {
    id: ID.sandboxBaseline,
    name: '主计划（BASELINE）',
    type: 'BASELINE',
    parentSandboxId: null,
    expiresAt: null,
    snapshot: {
      capturedAt: '2026-11-03T02:00:00Z',
      orderIds: ['DM-SO-1001', 'DM-SO-1003'],
      strategyId: ID.strategy1,
      baselineSolveTaskId: 1001,
      note: '默认基线'
    },
    version: 1,
    createdAt: '2026-10-01T00:00:00Z'
  },
  {
    id: ID.sandboxWhatIf,
    name: '加急插单推演',
    type: 'WHAT_IF',
    parentSandboxId: ID.sandboxBaseline,
    expiresAt: '2026-12-31T00:00:00Z',
    snapshot: { capturedAt: NOW, note: 'what-if 推演' },
    version: 1,
    createdAt: NOW
  },
  {
    id: ID.sandboxArchived,
    name: '十月版本归档',
    type: 'ARCHIVED',
    parentSandboxId: ID.sandboxBaseline,
    expiresAt: null,
    snapshot: { capturedAt: '2026-10-25T00:00:00Z', note: '历史版本' },
    version: 1,
    createdAt: '2026-10-25T00:00:00Z'
  }
]

export const solveTaskDetails: SolveTaskDetail[] = [
  {
    id: 1001,
    taskNo: 'SOLVE-20261103-0001',
    sandboxId: ID.sandboxBaseline,
    strategyId: ID.strategy1,
    engine: 'timefold',
    status: 'SOLVED',
    scoreHard: 0,
    scoreSoft: -45,
    startedAt: '2026-11-03T02:00:00Z',
    finishedAt: '2026-11-03T02:00:15Z',
    metrics: { stepCount: 12000, scoreHistory: [0, -120, -80, -45], durationMs: 15000 },
    errorMessage: null,
    createdAt: '2026-11-03T02:00:00Z'
  }
]

export const ganttTasks: GanttTask[] = [
  {
    id: 2101,
    orderNo: 'DM-SO-1001',
    operationIndex: 1,
    operationName: 'SMT 贴片',
    resourceCode: 'RES-SMT-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-05T00:00:00Z',
    endTime: '2026-11-05T02:00:00Z',
    setupStartTime: '2026-11-04T23:30:00Z',
    setupEndTime: '2026-11-05T00:00:00Z',
    qty: 120,
    status: 'PLANNED',
    sequenceInResource: 1,
    version: 1
  },
  {
    id: 2102,
    orderNo: 'DM-SO-1001',
    operationIndex: 2,
    operationName: '模组装配',
    resourceCode: 'RES-PACK-01',
    secondaryResourceCodes: ['RES-MODULE-01'],
    startTime: '2026-11-05T02:30:00Z',
    endTime: '2026-11-05T04:30:00Z',
    setupStartTime: '2026-11-05T02:00:00Z',
    setupEndTime: '2026-11-05T02:30:00Z',
    qty: 120,
    status: 'PLANNED',
    sequenceInResource: 1,
    version: 1
  },
  {
    id: 2103,
    orderNo: 'DM-SO-1001',
    operationIndex: 3,
    operationName: '老化测试',
    resourceCode: 'RES-AGING-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-05T05:00:00Z',
    endTime: '2026-11-06T05:00:00Z',
    setupStartTime: '2026-11-05T05:00:00Z',
    setupEndTime: '2026-11-05T05:00:00Z',
    qty: 120,
    status: 'FROZEN',
    sequenceInResource: 1,
    version: 2
  },
  {
    id: 2104,
    orderNo: 'DM-SO-1003',
    operationIndex: 1,
    operationName: 'SMT 贴片',
    resourceCode: 'RES-SMT-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-05T05:00:00Z',
    endTime: '2026-11-05T06:00:00Z',
    setupStartTime: '2026-11-05T04:30:00Z',
    setupEndTime: '2026-11-05T05:00:00Z',
    qty: 80,
    status: 'IN_PROGRESS',
    sequenceInResource: 2,
    version: 1
  },
  {
    id: 2105,
    orderNo: 'DM-SO-1003',
    operationIndex: 2,
    operationName: '模组装配',
    resourceCode: 'RES-PACK-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-05T06:30:00Z',
    endTime: '2026-11-05T07:30:00Z',
    setupStartTime: '2026-11-05T06:00:00Z',
    setupEndTime: '2026-11-05T06:30:00Z',
    qty: 80,
    status: 'PLANNED',
    sequenceInResource: 2,
    version: 1
  },
  {
    id: 2106,
    orderNo: 'DM-SO-1006',
    operationIndex: 1,
    operationName: '模组装配',
    resourceCode: 'RES-PACK-01',
    secondaryResourceCodes: [],
    startTime: '2026-11-04T08:00:00Z',
    endTime: '2026-11-04T10:00:00Z',
    setupStartTime: '2026-11-04T08:00:00Z',
    setupEndTime: '2026-11-04T08:00:00Z',
    qty: 200,
    status: 'COMPLETED',
    sequenceInResource: 3,
    version: 3
  }
]

export const ganttResourceRows: GanttResourceRow[] = [
  {
    resourceCode: 'RES-SMT-01',
    resourceName: '高速贴片线 SMT-01',
    resourceType: 'PRIMARY',
    tasks: ganttTasks.filter((task) => task.resourceCode === 'RES-SMT-01')
  },
  {
    resourceCode: 'RES-PACK-01',
    resourceName: 'PACK 总装线 PACK-01',
    resourceType: 'PRIMARY',
    tasks: ganttTasks.filter((task) => task.resourceCode === 'RES-PACK-01')
  },
  {
    resourceCode: 'RES-AGING-01',
    resourceName: '老化房 AGING-01（20 工位）',
    resourceType: 'PRIMARY',
    tasks: ganttTasks.filter((task) => task.resourceCode === 'RES-AGING-01')
  }
]

export const ganttOrderRows: GanttOrderRow[] = [
  {
    orderNo: 'DM-SO-1001',
    materialCode: 'FG-ESS-001',
    dueDate: '2026-11-12',
    tasks: ganttTasks.filter((task) => task.orderNo === 'DM-SO-1001')
  },
  {
    orderNo: 'DM-SO-1003',
    materialCode: 'FG-ESS-001',
    dueDate: '2026-11-08',
    tasks: ganttTasks.filter((task) => task.orderNo === 'DM-SO-1003')
  }
]

/** MRP 齐套分析 fixture（C25 新契约）。sandboxId/blocking/blocked 由 handler 按请求回填。 */
export const mrpAnalysisFixture: MrpAnalysisResult = {
  sandboxId: '00000000-0000-0000-0000-000000000401',
  analyzedAt: '2026-11-04T02:00:00Z',
  status: 'PARTIAL',
  blocking: false,
  blocked: false,
  inventoryEnabled: true,
  orderCount: 3,
  warnings: ['RAW-CELL-001 可用量低于安全库存'],
  orders: [
    {
      orderNo: 'DM-SO-1001',
      productCode: 'FG-ESS-001',
      qty: 1000,
      dueDate: '2026-11-08',
      kitted: false,
      shortages: [
        {
          materialCode: 'RAW-CELL-001',
          materialName: '磷酸铁锂方形电芯 3.2V/280Ah',
          requiredQty: 1920,
          allocatedQty: 1500,
          shortageQty: 420
        }
      ]
    },
    {
      orderNo: 'DM-SO-1006',
      productCode: 'SEMI-MOD-001',
      qty: 800,
      dueDate: '2026-11-10',
      kitted: true,
      shortages: []
    },
    {
      orderNo: 'DM-SO-1008',
      productCode: 'FG-ESS-001',
      qty: 500,
      dueDate: '2026-11-12',
      kitted: false,
      shortages: [
        {
          materialCode: 'RAW-THM-001',
          materialName: 'EC 离心散热风机模组 48V',
          requiredQty: 60,
          allocatedQty: 40,
          shortageQty: 20
        }
      ]
    }
  ],
  shortages: [
    {
      materialCode: 'RAW-CELL-001',
      materialName: '磷酸铁锂方形电芯 3.2V/280Ah',
      requiredQty: 1920,
      availableQty: 1500,
      shortageQty: 420,
      earliestRequiredAt: '2026-11-06T00:00:00Z',
      affectedOrderNos: ['DM-SO-1001'],
      suggestedPurchaseDate: '2026-11-05T00:00:00Z',
      suggestedQty: 420,
      supplierCode: 'SUP-CELL-001'
    },
    {
      materialCode: 'RAW-BMS-001',
      materialName: 'BMS 主控采集模块 16S（BCU-16S）',
      requiredQty: 384,
      availableQty: 1200,
      shortageQty: 0,
      earliestRequiredAt: '2026-11-12T00:00:00Z',
      affectedOrderNos: ['DM-SO-1006'],
      suggestedPurchaseDate: null,
      suggestedQty: null,
      supplierCode: null
    },
    {
      materialCode: 'RAW-THM-001',
      materialName: 'EC 离心散热风机模组 48V',
      requiredQty: 60,
      availableQty: 40,
      shortageQty: 20,
      earliestRequiredAt: '2026-11-05T00:00:00Z',
      affectedOrderNos: ['DM-SO-1008'],
      suggestedPurchaseDate: '2026-11-05T00:00:00Z',
      suggestedQty: 20,
      supplierCode: 'SUP-THM-001'
    }
  ]
}

export const users: User[] = [
  {
    id: ID.user1,
    username: 'admin',
    displayName: '系统管理员',
    email: 'admin@example.com',
    phone: '13800000000',
    status: 'ACTIVE',
    lastLoginAt: NOW,
    version: 1,
    roleCodes: ['ADMIN']
  },
  {
    id: 'ffffffff-ffff-4fff-8fff-fffffffffff1',
    username: 'planner01',
    displayName: '计划员 01',
    email: 'planner01@example.com',
    phone: null,
    status: 'ACTIVE',
    lastLoginAt: '2026-11-03T09:00:00Z',
    version: 1,
    roleCodes: ['PLANNER']
  },
  {
    id: 'ffffffff-ffff-4fff-8fff-fffffffffff2',
    username: 'viewer01',
    displayName: '只读用户',
    email: null,
    phone: null,
    status: 'DISABLED',
    lastLoginAt: null,
    version: 1,
    roleCodes: ['VIEWER']
  }
]

/** C30 权限点：`GET /permissions` 返回的就是这个裸数组。 */
export const permissions: PermissionView[] = [
  { id: 'p01', code: 'MD_READ', name: '主数据查看', module: 'MD' },
  { id: 'p02', code: 'MD_WRITE', name: '主数据维护', module: 'MD' },
  { id: 'p03', code: 'PR_READ', name: '工艺查看', module: 'PR' },
  { id: 'p04', code: 'PR_WRITE', name: '工艺维护', module: 'PR' },
  { id: 'p05', code: 'OM_READ', name: '订单查看', module: 'OM' },
  { id: 'p06', code: 'OM_WRITE', name: '订单维护', module: 'OM' },
  { id: 'p07', code: 'OM_ATP', name: 'ATP 试算', module: 'OM' },
  { id: 'p08', code: 'ORDER_FREEZE', name: '订单冻结', module: 'OM' },
  { id: 'p09', code: 'SS_READ', name: '策略查看', module: 'SS' },
  { id: 'p10', code: 'SS_WRITE', name: '策略维护', module: 'SS' },
  { id: 'p11', code: 'SE_SOLVE', name: '发起求解', module: 'SE' },
  { id: 'p12', code: 'SCHEDULE_ADJUST', name: '排程调整', module: 'SCHEDULE' },
  { id: 'p13', code: 'SCHEDULE_PUBLISH', name: '计划发布', module: 'SCHEDULE' },
  { id: 'p14', code: 'SYS_USER', name: '用户管理', module: 'SYS' },
  { id: 'p15', code: 'SYS_ROLE', name: '角色管理', module: 'SYS' },
  { id: 'p16', code: 'SYS_AUDIT', name: '审计查看', module: 'SYS' },
  { id: 'p17', code: 'AGENT_EVENT', name: 'Agent 事件订阅', module: 'AGENT' },
  { id: 'p18', code: 'AGENT_CONFIRM', name: 'Agent 确认', module: 'AGENT' }
]

/** C30 角色：`GET /roles` 返回 `{items}`，不是裸数组。 */
export const roles: RoleView[] = [
  {
    id: 'r01',
    code: 'ADMIN',
    name: '管理员',
    description: '全部权限',
    permissionCodes: permissions.map((item) => item.code),
    version: 1
  },
  {
    id: 'r02',
    code: 'PLANNER',
    name: '计划员',
    description: '订单、策略、求解与排程',
    permissionCodes: [
      'MD_READ',
      'PR_READ',
      'OM_READ',
      'OM_WRITE',
      'OM_ATP',
      'SS_READ',
      'SS_WRITE',
      'SE_SOLVE',
      'SCHEDULE_ADJUST',
      'SCHEDULE_PUBLISH',
      'AGENT_CONFIRM'
    ],
    version: 1
  },
  {
    id: 'r03',
    code: 'PROCESS_ENGINEER',
    name: '工艺工程师',
    description: '主数据与工艺维护',
    permissionCodes: ['MD_READ', 'MD_WRITE', 'PR_READ', 'PR_WRITE'],
    version: 1
  },
  {
    id: 'r04',
    code: 'SALES',
    name: '销售',
    description: '订单录入与交期试算',
    permissionCodes: ['OM_READ', 'OM_WRITE', 'OM_ATP'],
    version: 1
  },
  {
    id: 'r05',
    code: 'VIEWER',
    name: '只读用户',
    description: '只读',
    permissionCodes: ['MD_READ', 'PR_READ', 'OM_READ', 'SS_READ'],
    version: 1
  }
]

export const auditLogs: AuditLog[] = [
  {
    id: '1024',
    traceId: '56a8a6223cc647898dc11403037d8477',
    userId: ID.user1,
    action: 'AUTH_LOGIN',
    resourceType: 'USER',
    resourceId: ID.user1,
    beforeValue: null,
    afterValue: { username: 'admin', result: 'SUCCESS' },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-04T07:55:00Z'
  },
  {
    id: '1025',
    traceId: '8f2c1d4b6a7e4c0d9b3e5f7a1c2d3e4f',
    userId: ID.user1,
    action: 'PLAN_PUBLISHED',
    resourceType: 'PLAN',
    resourceId: ID.sandboxBaseline,
    beforeValue: { versionNo: 1 },
    afterValue: { versionNo: 2, planNo: 'PLAN-20261104-0002' },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-04T06:30:00Z'
  },
  {
    id: '1026',
    traceId: '1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d',
    userId: ID.user1,
    action: 'ORDER_FROZEN',
    resourceType: 'ORDER',
    resourceId: 'DM-SO-1008',
    beforeValue: { frozen: false },
    afterValue: { frozen: true, reason: '客户确认插单' },
    clientIp: '10.0.0.20',
    createdAt: '2026-11-04T05:10:00Z'
  },
  {
    id: '1027',
    traceId: '2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e',
    userId: ID.user1,
    action: 'ORDER_RELEASED',
    resourceType: 'ORDER',
    resourceId: 'DM-SO-1004',
    beforeValue: { status: 'HOLD' },
    afterValue: { status: 'RELEASED' },
    clientIp: '10.0.0.20',
    createdAt: '2026-11-03T09:40:00Z'
  },
  {
    id: '1028',
    traceId: '3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f',
    userId: ID.user1,
    action: 'SANDBOX_APPLIED',
    resourceType: 'SANDBOX',
    resourceId: ID.sandboxWhatIf,
    beforeValue: { type: 'WHAT_IF' },
    afterValue: { type: 'BASELINE' },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-03T03:15:00Z'
  },
  {
    id: '1029',
    traceId: '4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a',
    userId: ID.user1,
    action: 'USER_UPDATED',
    resourceType: 'USER',
    resourceId: 'ffffffff-ffff-4fff-8fff-fffffffffff1',
    beforeValue: { roleCodes: ['VIEWER'] },
    afterValue: { roleCodes: ['PLANNER'] },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-02T08:20:00Z'
  },
  {
    id: '1030',
    traceId: '5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b',
    userId: null,
    action: 'AUTH_LOGIN_FAILED',
    resourceType: 'USER',
    resourceId: 'unknown',
    beforeValue: null,
    afterValue: { username: 'admin', reason: 'BAD_CREDENTIALS' },
    clientIp: '10.0.0.34',
    createdAt: '2026-11-01T23:59:00Z'
  },
  {
    id: '1031',
    traceId: '6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c',
    userId: ID.user1,
    action: 'TASK_ADJUSTED',
    resourceType: 'TASK',
    resourceId: '2101',
    beforeValue: { startTime: '2026-11-05T00:00:00Z' },
    afterValue: { startTime: '2026-11-05T01:00:00Z' },
    clientIp: '10.0.0.12',
    createdAt: '2026-11-01T10:05:00Z'
  }
]

export const configs: SystemConfig[] = [
  {
    key: 'solver.defaultEngine',
    value: 'timefold',
    description: '默认求解引擎',
    updatedAt: NOW
  },
  {
    key: 'solver.timeoutSeconds',
    value: '60',
    description: '求解超时（秒）',
    updatedAt: NOW
  },
  {
    key: 'planning.horizonDays',
    value: '30',
    description: '排产滚动窗口（天）',
    updatedAt: NOW
  },
  {
    key: 'features.agent',
    value: 'true',
    description: 'Agent 功能开关',
    updatedAt: NOW
  }
]

export const planId = ID.plan
export const currentTimestamp = NOW
