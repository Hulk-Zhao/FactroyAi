# 行业模板指南：SMT 与储能 PACK

本文说明如何在当前代码基线上为 **SMT 电子组装** 与 **储能 PACK** 两类离散制造场景建模。所有字段、行为都以仓库现有代码与 `db/migration/V1__init.sql` 为准；仓库没有为 SMT 提供独立种子脚本，因此 SMT 部分是「按现有模型可落地的建模方法」，不是可直接导入的成品数据集。

> 阅读前请先看：[用户手册](user-manual.md)（接口与页面）、[架构文档](../dev/architecture.md)（求解调用链）、[约束 DSL](../dev/constraint-dsl.md)（软约束权重写法）。

## 0. 通用建模顺序

排产装配只依赖 **订单 → 工艺路线 → 工序模板 → 资源 → 日历** 这条链，BOM 不参与求解（见 0.1）。

| 顺序 | 动作 | 主要接口 | 落库表 | 说明 |
|---|---|---|---|---|
| 1 | 建物料 | `POST /api/v1/materials` | `material` | 成品 / 半成品 / 原材料 / 辅料 / 包材 |
| 2 | 建日历 | `POST /api/v1/calendars` | `calendar` | 资源必须能绑定到日历才有时段约束 |
| 3 | 建资源 | `POST /api/v1/resources` | `resource` | 指定 `capacityMode`、`capacityValue`、`calendarId` |
| 4 | 建工序模板 | `POST /api/v1/operation-templates` | `operation_template` | 标准工时、换型工时、主/辅资源 |
| 5 | 建工艺路线 | `POST /api/v1/routings` | `routing_node` / `routing_edge` | 节点事实来源是子表，`routing.nodes/edges` 只是画布快照 |
| 6 | 建 BOM | `POST /api/v1/boms` | `bom` / `bom_item` | 主数据维护与导入导出用，不进入排产 |
| 7 | 建订单 | `POST /api/v1/orders` | `orders` | 默认 `DRAFT` / `UNFROZEN` |
| 8 | 确认订单 | `PUT /api/v1/orders/{orderNo}/confirm` | `orders` | 只有 `CONFIRMED` 才默认参与求解 |
| 9 | （可选）建策略 | `POST /api/v1/strategies` | `strategy` / `strategy_rule` | 8 条 `SOFT` 规则且权重合计 100 才会注入软约束 |
| 10 | 提交求解 | `POST /api/v1/solve-tasks` | `solve_task` / `task` / `constraint_justification` | 异步开关与轮询见 API 文档 |

### 0.1 求解时实际读取的数据

`ScheduleProblemAssembler#assemble` 的处理路径（`aps-service/.../schedule/ScheduleProblemAssembler.java`）：

1. 取请求点名的订单（未点名时按场景取默认订单集合），拒绝 `CANCELLED`。
2. `resolveRouting(order)`：优先用订单 `routingId`，否则按 **产品物料编码 + 状态 `ACTIVE`** 取最新生效路线；取不到直接 `42200`。
3. 读 `routing_node`（按 `seq_no`）与 `routing_edge`，每个节点转成一个 `SchedulingOperation`。
4. 节点的主资源：`overrides.primaryResourceId` → `operation_template.primary_resource_id` → `operation_template.primary_resource_group_id` 下第一个资源；`overrides.primaryResourceId` 显式为 `null` 表示该工序资源浮动，由求解器在候选资源中选择。
5. 节点的辅资源：`overrides.secondaryResourceIds` → `operation_template.secondary_resource_ids`。
6. 标准工时：`routing_node.standard_seconds` → `operation_template.standard_seconds`，两处都空则 `42200`。
7. 读资源与日历，生成可用时段；任务数量 = **订单数 × 路线节点数**，单任务覆盖整张订单数量，不做拆分。

**结论：BOM 层级不会被展开成任务，也不会影响任务数量。** 这是当前实现的明确限制，不是录入错误。

### 0.2 产能与时长口径

`OperationRunDuration.seconds(resource, operation, qty)` 是所有时长计算的唯一口径（见 `aps-solver/solver-api/.../duration/OperationRunDuration.java`）：

| `capacityMode` | 公式 | 适用 |
|---|---|---|
| `UNIT_PER_HOUR` | `ceil(qty * 3600 / (capacity_value * efficiency))` | 贴片机、按件/小时的设备 |
| `SECONDS_PER_UNIT` | `ceil(qty * standard_seconds / efficiency)` | 人工装配、单件节拍设备 |
| `BATCH_SECONDS` | `ceil(ceil(qty / batch_capacity) * capacity_value / efficiency)` | 老化柜、回流焊、烘箱等按批设备 |

`efficiency` 取值 0.5–1.5（`Resource.efficiency` 注释与校验）。`BATCH_SECONDS` 的 `batch_capacity` 为空时按 1 处理，因此必须显式填写批量，否则每件都会占一整批工时。

## 1. 储能 PACK 场景（已有可运行种子）

### 1.1 数据来源

- 种子脚本：[scripts/seed-m1-demo.ps1](../../scripts/seed-m1-demo.ps1)，幂等：先按固定 UUID 清理，再插入。
- 演示编排：[scripts/demo-m1.ps1](../../scripts/demo-m1.ps1)。
- 性能压测（通用，不含工艺）：[scripts/seed-m2-perf.ps1](../../scripts/seed-m2-perf.ps1)。
- 客户主数据：[scripts/seed-m3-customers.ps1](../../scripts/seed-m3-customers.ps1)。

### 1.2 物料

| 编码 | 类型 | 名称 | 说明 |
|---|---|---|---|
| `MAT-FG-001` | `FINISHED` | 储能电芯模组 48V/15Ah | 主成品，有 ACTIVE 路线 |
| `MAT-SF-001` | `SEMI` | 电池包半成品 16S1P | 中间层 |
| `MAT-RM-003` | `RAW` | 铝合金型材 6061-T6 | 结构件 |
| `MAT-RM-004` | `RAW` | ABS 阻燃粒料 | 结构件 |
| `MAT-FG-002` | `FINISHED` | 储能电芯模组 48V/20Ah | 负例：没有 ACTIVE 路线 |

`material.industry_attrs` 可放储能行业属性，`docs/db/schema.md` 5.2 给出的约定是 `cell_model`、`nominal_capacity_ah`、`nominal_voltage_v`、`grading_rule`。**当前求解器不读取 `industry_attrs`**，这些字段只用于主数据管理与展示。

### 1.3 BOM 两层结构

| 父件 | 子件 | `qtyPerUnit` | 版本 | 状态 |
|---|---|---|---|---|
| `MAT-FG-001` | `MAT-SF-001` | 1 | V2.0 | ACTIVE |
| `MAT-FG-001` | `MAT-RM-003` | 2 | V2.0 | ACTIVE |
| `MAT-SF-001` | `MAT-RM-004` | 1 | V3.0 | ACTIVE |
| `MAT-SF-001` | `MAT-RM-003` | 0.5 | V3.0 | ACTIVE |

字段要点：`bom.is_panel` / `bom.panel_size` 表示拼板；`bom_item.position` 为位号，`bom_item.substitutes` 为替代料数组，`bom_item.is_phantom` 为虚拟件。储能场景通常 `is_panel=false`。再次强调：BOM 不参与排产，排产只看路线。

### 1.4 资源

| 编码 | 类型 | 产能模式 | `capacityValue` | `batchCapacity` | 用途 |
|---|---|---|---|---|---|
| `RES-SMT-01` | `PRIMARY` | `UNIT_PER_HOUR` | 6 | - | SMT 贴片机 1 号 |
| `RES-SMT-02` | `PRIMARY` | `UNIT_PER_HOUR` | 4 | - | SMT 贴片机 2 号（节点级覆盖） |
| `RES-ASSY-01` | `PRIMARY` | `SECONDS_PER_UNIT` | 0（不参与计算） | - | 总装线，按 `standard_seconds` 计 |
| `RES-AGE-01` | `PRIMARY` | `BATCH_SECONDS` | 600 | 20 | 老化柜，每批 600 秒 / 20 台 |
| `RES-QC-01` | `SECONDARY` | `SECONDS_PER_UNIT` | 0 | - | 质检工位（辅资源） |
| `RES-OP-01` | `HUMAN` | `SECONDS_PER_UNIT` | 0 | - | 装配操作工（辅资源） |

全部资源绑定 `CAL-FAC-7X24`。`resource.type` 允许 `PRIMARY / SECONDARY / HUMAN / TOOL`；只有 `PRIMARY` 可作为任务主资源，`SECONDARY/HUMAN/TOOL` 通过 `secondaryResourceIds` 占用。

### 1.5 工序模板与工艺路线

| 模板编码 | 名称 | 主资源 | 标准工时(s) | 换型(s) |
|---|---|---|---|---|
| `OP-SMT-01` | SMT 贴片 | `RES-SMT-01` | 60 | 30 |
| `OP-AGE-01` | 老化烘烤 | `RES-AGE-01` | 600 | 30 |
| `OP-ASSY-01` | 电池包总装 | `RES-ASSY-01` | 30 | 10 |
| `OP-QC-01` | 出厂检验 | `RES-ASSY-01` | 20 | 10 |

路线 `V2.0`（ACTIVE，绑定 `MAT-FG-001`）共 6 个节点、5 条依赖边：

| 序号 | 节点 | 模板 | 覆盖 |
|---|---|---|---|
| 10 | SMT 贴片 | `OP-SMT-01` | 无 |
| 20 | SMT 贴片 | `OP-SMT-01` | `primaryResourceId = RES-SMT-02` |
| 30 | 老化烘烤 | `OP-AGE-01` | 无 |
| 40 | 电池包总装 | `OP-ASSY-01` | `secondaryResourceIds = [RES-OP-01]` |
| 50 | 出厂检验 | `OP-QC-01` | `secondaryResourceIds = [RES-QC-01]` |
| 60 | 包装入库 | `OP-ASSY-01` | 无 |

依赖边：`10→20→30→40→50→60` 全为 `FS`，其中 `20→30` 的 `lagMinutes=30`（贴片后静置 30 分钟再老化）。`routing_edge` 目前只接受 `FS / SS / FF`，`lagMinutes` 不能为负。

### 1.6 日历

`CAL-FAC-7X24`（`type=FACTORY`）的 `shifts` 为：

```json
[{"startTime":"00:00","endTime":"24:00","daysOfWeek":[1,2,3,4,5,6,7]}]
```

- `holidays` 支持 `{"date","name","kind"}`；`kind` 为 `LEGAL` 或 `COMPANY` 的日期会被整天跳过，`kind` 缺省按 `LEGAL` 处理。
- 资源未绑定日历时，回退到 **最新版本的 FACTORY 日历**；找不到 FACTORY 日历时视为无时段约束（7×24）。
- 班次支持跨日：`endTime <= startTime` 视为次日结束（例如 `20:00 → 08:00`）。
- **待确认**：可用时段计算按 UTC 解释班次时间（`ScheduleProblemAssembler#expand` 使用 `ZoneOffset.UTC`），因此 `08:00` 实际对应东八区 16:00。若业务按本地时区排班，需要先确认这是设计还是缺陷。

### 1.7 订单

`SO-20261104-0001` 至 `SO-20261104-0008` 使用 `MAT-FG-001`，优先级覆盖 `HIGH / NORMAL / URGENT / LOW`，交期为 `CURRENT_DATE + 3..+10`，全部 `DRAFT`。

`SO-20261104-0009` 使用 `MAT-FG-002`。脚本注释写作「负例：无 ACTIVE BOM」，但求解装配根本不读 BOM，实际失败点是 **没有 ACTIVE 工艺路线**（`resolveRouting` 抛 `42200`）。

### 1.8 常见约束与建议权重

硬约束（`ApsConstraintProvider`，共 9 条）：未排位任务、资源不重叠、日历可用、工序依赖、冻结资源不可变、冻结开始时间不可变、主资源限定、C21 时间窗锁、C21 资源锁。

软约束 8 条（必须 8 条且合计 100 才生效）：`MIN_TARDINESS`、`EDD`、`SPT`、`SAME_PRODUCT_CONTINUOUS`、`MIN_SETUP_TIME`、`BALANCE_LOAD`、`PRIORITY_RESPECT`、`MIN_MAKESPAN`。

储能场景的**建议**权重（`POST /constraints-dsls/validate` 可接受，业务可按需调整）：

```yaml
version: "1.0"
constraints:
  - ruleCode: MIN_TARDINESS
    weight: 25
    enabled: true
  - ruleCode: PRIORITY_RESPECT
    weight: 20
    enabled: true
  - ruleCode: EDD
    weight: 15
    enabled: true
  - ruleCode: SAME_PRODUCT_CONTINUOUS
    weight: 10
    enabled: true
  - ruleCode: MIN_SETUP_TIME
    weight: 10
    enabled: true
  - ruleCode: BALANCE_LOAD
    weight: 10
    enabled: true
  - ruleCode: SPT
    weight: 5
    enabled: true
  - ruleCode: MIN_MAKESPAN
    weight: 5
    enabled: true
```

注意：`scripts/seed-m1-demo.ps1` 插入的「交期优先·正向排产策略」只有 `strategy` 主表，没有 `strategy_rule` 明细；`ScheduleService#resolveSoftWeights` 读不到 8 条合规规则时不会注入权重，**该种子策略实际是纯硬约束求解**。

### 1.9 验证路径

```bash
# 1) 灌入种子（要求本地 PostgreSQL 已启动）
powershell -ExecutionPolicy Bypass -File scripts/seed-m1-demo.ps1
# 2) 登录、确认订单、提交求解、轮询结果（完整 curl 见 docs/dev/api-usage.md）
```

负例验证：确认 `SO-20261104-0009` 后提交求解，应返回 `422/42200`，消息包含「没有 ACTIVE 工艺路线」。

## 2. SMT 电子组装场景

仓库目前**没有独立的 SMT 种子脚本**：`seed-m1-demo.ps1` 只把 `RES-SMT-01/02` 与 `OP-SMT-01` 作为储能路线中的两个节点。SMT 场景需要按下面的方法自建主数据，或用 `/{entity}/import` 批量导入。

### 2.1 物料与行业属性

- 物料类型：PCB/元件用 `RAW`，贴装后的 PCBA 用 `SEMI`，整机用 `FINISHED`，钢网/载具等可建为 `TOOL` 资源而非物料。
- `material.industry_attrs` 约定字段（`docs/db/schema.md` 5.2）：`msd_level`、`exposure_hours`、`bake_hours`、`panel_size`、`package`。**求解器不消费这些字段**，MSD 暴露时间/烘烤时长不能作为约束生效。

示例：

```json
{"msd_level": 3, "exposure_hours": 168, "bake_hours": 24, "panel_size": 4, "package": "0603"}
```

### 2.2 BOM 与拼板

SMT 的 BOM 建模重点是三组字段：

| 字段 | 用途 |
|---|---|
| `bom.is_panel` / `bom.panel_size` | 拼板数量；当前只做存储与展示，**不折算排产时长** |
| `bom_item.position` | 位号（如 `R101`、`U3`），用于工艺与 SMT 程序对照 |
| `bom_item.substitutes` | 替代料数组；替代关系不参与求解 |
| `bom_item.qtyPerUnit` / `scrap_rate` | 单件用量与损耗率；不参与求解 |
| `bom_item.is_phantom` | 虚拟件（如免装 PCBA 层） |

物料层级用 `bom_item.level` 与父件 `bom.parent_material_id` 表达，仓库示例是两层（FG → SF → RAW）。

### 2.3 工艺路线建议

典型节点：`锡膏印刷 → SPI → 贴片 → 回流焊 → AOI → 分板/测试 → 包装`。每条节点用独立 `operation_template`，节点级差异用 `routing_node.overrides`：

```json
{"primaryResourceId": "<SMT-2 的资源 UUID>"}
```

- 每个 `routing_node` 会生成一个任务，**一个节点不能拆成多台设备并行**；需要多机并行时，应把该工序建成多个可选资源（同一资源组）或拆成多条路线（待确认哪种更贴合业务）。
- 回流焊后冷却、点胶固化等等待时间，用 `routing_edge.lagMinutes` + `FS` 表达，不要写成独立工序。
- 测试工位/治具用 `secondaryResourceIds` 占用；`RESOURCE` 锁（C21）可把某节点固定到指定机台。
- 只支持 `FS/SS/FF`，没有 `SF`；`lagMinutes` 不允许负数。

### 2.4 资源与产能

| SMT 设备 | 建议 `capacityMode` | 说明 |
|---|---|---|
| 贴片机 | `UNIT_PER_HOUR` | `capacityValue` 为理论贴片产能（板/小时），`efficiency` 吸收稼动率 |
| 印刷机 / SPI / AOI | `SECONDS_PER_UNIT` | 用 `standard_seconds` 表达节拍 |
| 回流焊 | `BATCH_SECONDS` | `capacityValue` 为每炉秒数，`batchCapacity` 为每炉板数 |
| 钢网 / 治具 | `SECONDARY` 或 `TOOL` | 只能作为辅资源占用，不能单独排主任务 |

`efficiency` 只允许 0.5–1.5；超出范围的稼动率应体现在 `capacityValue` 或 `standard_seconds`，不要写进 `efficiency`。

### 2.5 日历与班次

两班示例（每班 12 小时，周一至周六）：

```json
[
  {"startTime":"08:00","endTime":"20:00","daysOfWeek":[1,2,3,4,5,6]},
  {"startTime":"20:00","endTime":"08:00","daysOfWeek":[1,2,3,4,5,6]}
]
```

- 跨日班次由 `shiftEnd` 支持；`daysOfWeek` 使用 ISO 编号 1=周一 … 7=周日。
- `holidays` 中 `kind=LEGAL/COMPANY` 会整天停机；换型/保养计划目前只在 `resource.maintenance_plan` JSONB 中存储，**求解器未读取**（`ScheduleProblemAssembler` 只展开 `calendar.shifts/holidays`）。
- 再次提醒 UTC 解释问题（见 1.6 待确认项），上线前必须对齐时区。

### 2.6 常见约束与当前限制

可直接依赖的约束：

- 资源不重叠（主资源含换型，辅资源只占加工窗口）；
- 日历可用、工序依赖、冻结任务不可移动、主资源限定、C21 时间/资源锁；
- 软约束中的 `SAME_PRODUCT_CONTINUOUS` 使用 **物料分类编码** 作为产品族（`ScheduleProblemAssembler` 传入 `productFamily`），因此 SMT 换线优化前要给物料配置分类；
- `MIN_SETUP_TIME` 使用 `setup_seconds`；换型矩阵 `SetupMatrixResolver` 已实现，但装配层传入空列表，**换型矩阵当前不会生效**。

当前不支持 / 待确认：

- 无 BOM 展开、无物料齐套、无库存约束；
- 无订单/批量拆分、无合批、无联产品/副产物；
- 无模具/钢网/载具数量约束（`TOOL` 资源没有实例数量概念）；
- 无多工厂/车间/产线层级，资源是平的；
- 无技能矩阵、无人员排班（`HUMAN` 仅作为辅资源占用）；
- 无 MSD 暴露时间、烘烤规则、首件检验等工艺规则。

## 3. 求解器相关注意事项

- 引擎默认 `timefold`；`ortools` 只有 `aps.solve.engine.ortools-enabled=true` 时才保留，否则回退或报 `50010`。`OrtoolsSolverAdapter` 目前是占位实现。
- 无 `strategyId`、策略规则不合规、或未传 `parameters.softWeights` 时，Timefold 关闭全部软约束，只保留硬约束。
- 约束 DSL 的 `compiled_spec` 会存库并可激活，但 `ScheduleProblemAssembler` 传给求解器的 `constraints` 是空列表；**DSL 当前不驱动求解**。请用策略或请求 `parameters.softWeights` 控制软约束。
- 请求 `parameters` 还支持 `timeoutSeconds`、`moveThreadCount`、`acceptor`、`randomSeed`、`maxSteps`、`unimprovedSecondsSpentLimit`、`direction`、`useFrozenAssignments`，含义见 API 文档。

## 4. 待确认

1. 日历班次按 UTC 解释是否符合预期（1.6 / 2.5）。
2. SMT 多机并行应通过「多候选资源」还是「拆多条路线」建模。
3. 换型矩阵、维护计划、`industry_attrs` 是否会接入求解（当前均未接线）。
4. `SO-20261104-0009` 的负例描述（脚本注释写 BOM，实际失败在路线）需要统一。
