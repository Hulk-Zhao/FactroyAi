# 演示数据指南（储能 PACK + SMT）

统一种子脚本：[scripts/seed-demo.ps1](../../scripts/seed-demo.ps1)。

- **覆盖场景**：19 / 19 个前端页面均有可见数据。
- **幂等**：`-Scene all` 连跑两次，业务表行数完全一致（见文末验证记录）。
- **可重置**：`-Reset` 只清本脚本演示数据，不 DROP 库、不碰 `aps_local*`。
- **待其它车道**：ATP 表、`mfg-agent-platform` 外部对接表尚未建，本脚本已跳过并在输出中提示；V14 换型矩阵已建表，已补齐 6 条演示数据。

---

## 1. 现状盘点（已有 vs 新增）

| 已有能力 | 已有脚本 | 本脚本的处理 |
| --- | --- | --- |
| 基础 M1 闭环：2 层 BOM、全厂 7×24 日历、6 资源、4 工序模版、1 条 6 节点路线、8 个 DRAFT 订单、1 个策略主表 | `seed-m1-demo.ps1` | 不复用其业务编码，改用独立 `DM-` 前缀 + 独立 UUID 号段；避免与 `MAT-*`/`SO-*` 冲突 |
| 压测数据：500 订单 / 50 资源 / 6 工序 | `seed-m2-perf.ps1` | 不重复；演示用小批量、多状态、可讲故事的订单 |
| 8 家客户 `CUS-001..CUS-008` | `seed-m3-customers.ps1` | **直接复用同一编码/名称/固定 UUID**，按 `(tenant_id, code)` upsert，不产生第二套客户 |
| 换型矩阵表（V14） | 迁移已由其它车道建好 | 补 6 条演示矩阵（租户/资源组/资源三级 + 同/跨系列特例） |
| ATP、mfg-agent-platform 外部对接 | 不存在 | **未建表、未造数**，输出明确提示「待该车道建表后补」 |

本脚本新增覆盖的缺口：

- 独立 SMT 资源与工序：`RES-SMT-01/02`、`RES-AOI-01`、`OP-SMT-01/02`。
- `strategy_rule` 明细：2 个策略 × 8 条软规则，各权重合计 **100**（`resolveSoftWeights` 可读到）。
- 订单状态铺满：`DRAFT / CONFIRMED / RELEASED / IN_PROGRESS / SCHEDULED`，冻结维度另含 `FROZEN / LOCKED_RESOURCE / LOCKED_TIME_WINDOW`。
- MRP 齐套 + 缺料；在途含近期到期与远期被裁剪。
- 求解成功/失败/取消；成功任务含 `constraint_justification`。
- 4 个 PUBLISHED 计划版本（基线 2 + WhatIf 1 + 归档 1），跨版本可对比。
- 沙箱 BASELINE + WHAT_IF + ARCHIVED（已 apply 的归档结果）。
- 审计日志 14 条、导入任务 2 条、演示用户 2 个。

---

## 2. 脚本参数

```powershell
# 全量灌注
.\scripts\seed-demo.ps1 -Database <目标库> -Scene all

# 分段灌注（前缀包含关系）
.\scripts\seed-demo.ps1 -Database <目标库> -Scene master   # 主数据 + 库存 + 换型矩阵
.\scripts\seed-demo.ps1 -Database <目标库> -Scene orders   # + 客户/供应商/订单/策略
.\scripts\seed-demo.ps1 -Database <目标库> -Scene sandbox  # + BASELINE/WHAT_IF/ARCHIVED 任务
.\scripts\seed-demo.ps1 -Database <目标库> -Scene solve    # + 求解任务/归因/人工调整
.\scripts\seed-demo.ps1 -Database <目标库> -Scene plans    # + 计划版本/下发 outbox
.\scripts\seed-demo.ps1 -Database <目标库> -Scene audit    # 仅审计/导入/演示用户

# 清理（不 DROP 库）
.\scripts\seed-demo.ps1 -Database <目标库> -Reset
```

安全约束：

- `-Database` 必填；`aps_local*`、`postgres`、`template0/1` 直接拒绝。
- 先按固定 UUID / 业务键清理，再在单事务内插入；失败整体回滚。
- 客户 `CUS-001..008` 为 `seed-m3` 共用主数据，`-Reset` **不删除**（避免碰其它车道）。

### 2.1 日期全部相对 `-AnchorDate` 生成

脚本内**不写任何绝对业务日期**；所有日期由基准日按固定偏移派生。参数 `-AnchorDate` 可选（`yyyy-MM-dd`，缺省 = 运行当天，时区 `Asia/Shanghai`）。

```powershell
# 现场默认：按运行当天生成（演示前必须用当天日期重灌）
.\scripts\seed-demo.ps1 -Database aps_demo -Scene all -Reset

# 彩排复现：固定基准日，同日两次生成逐字一致（-AnchorDate 2026-11-05 与历史种子等价）
.\scripts\seed-demo.ps1 -Database aps_demo_verify -Scene all -AnchorDate 2026-11-05
```

求解可用窗口是**滑动窗口**：`horizonStart = 运行时刻 - 2h`、`horizonEnd = horizonStart + 172h`（约 `+0 ~ +7.2 天`）。因此：

- **冻结任务、锁窗必须落在 `AnchorDate +1 ~ +5 天`**（跨夜长任务可延到 +5.5 天，仍在 +7.2 天窗口内），重排时 pin 得住；落到窗口外会变成硬违反（`-8hard`），给多少预算都解不出。
- **订单交期放在 `+20 天` 以外**（远离窗口边沿），只影响软约束与 MRP 裁剪，不参与可行性。

| 项目 | 相对 `AnchorDate` 偏移 | 锚定 `2026-11-05` 时的落点 |
| --- | --- | --- |
| 订单交期 `DM-SO-1001..1010` | +20 / +24 / +27 / +30 / +35 / +40 / +45 / +49 / +53 / +43 天 | 11-25 ~ 12-28 |
| `DM-SO-1003` 8 条 `FROZEN` 任务 | 锚点 **+4 天 08:00**，沿工艺链连续排至 **+5.5 天 10:46** | 11-09 08:00 → 11-10 10:46 |
| `DM-SO-1005` `freezeLock` 锁窗 | **+1 天 08:00 ~ 11:00**（任务锚点同为 +1 天 08:00） | 11-06 08:00 ~ 11:00 |
| `DM-SO-1002` BASELINE/WHAT_IF 锚点 | **+6 天 08:00** | 11-11 08:00 |
| 任务锚点（1004 / 1006 / 1007） | +7 / +0 / +18 天 08:00 | 11-12 / 11-05 / 11-23 08:00 |
| 日历 `holidays` | +11 天（设备年度检修）、+49 天（年终盘点） | 11-16 / 12-24 |
| `inventory.snapshot_at`、`inventory_transit.created_at` | -6 天 18:00 | 10-30 18:00 |
| `inventory_transit.expected_date` | +7 / +20 / +60 / +76 / +115 天 | 11-12 / 11-25 / 2027-01-04 / 2027-01-20 / 2027-02-28 |
| 主数据 `created_at`、BOM/路线/日历/策略 `effective_from` | -35 天 09:00 / -308 天 | — |
| 物料单价生效日 `ext_attrs.priceDate` | -30 天 | 10-06 |
| 订单 `created_at` | -8 天 10:00 | — |
| 订单下单日 `ext_attrs.orderDate` | -5 ~ -16 天 | 10-20 ~ 10-31 |
| 任务、`solve_task`、`plan_version` `created_at` | -3 天 12:00 | — |
| `solve_task` 开始/结束 | 001 `-2`、002 `-1`、003 `0`、004 `-3`、005 `-3` | — |
| `constraint_justification` `created_at` | -2 天 09:43 | — |
| `plan_version` 发布时间 | -2 / -1 / 0 / -3 天 | — |
| `notification_outbox` | 0 天 10:30 / 10:31 / 11:00 | — |
| 审计日志、导入任务 | 0 ~ -3 天 | — |

> 交期偏移刻意保留原种子的 `+20 ~ +53` 分布以满足“`-AnchorDate 2026-11-05` 与原种子完全等价”；最远一单 +53 天，距窗口终点仍有 36 天以上，不影响排产可行性。

---

## 3. 19 个页面场景对照

| # | 页面 / 路由 | 演示数据 | 能讲的故事 | 对应业务规则 |
| --- | --- | --- | --- | --- |
| 1 | 工作台 `/` | `/api/v1/health` 返回 `UP` | 系统健康、版本信息 | 健康检查 |
| 2 | 物料 `/master-data/materials` | **26 个物料**：16 原材料、4 半成品、4 成品、2 辅料，编码 `RAW-/SEMI-/FG-/AUX-`；每条带规格型号、计量单位、供应商、采购提前期、MOQ、安全库存；`ext_attrs` 另含单价/币种/价格生效日/计价单位 | 从电芯/PCB/风扇/BMS 到 PACK 成品的完整物料族；`RAW-THM-001↔RAW-THM-002`、`RAW-BMS-002↔RAW-BMS-003` 两组可替代料 | 物料主数据 + 分类 |
| 3 | BOM `/master-data/boms` | **8 个 BOM / 37 行子件**：FG-ESS-001 V1.0（6 行）、SEMI-MOD-001（2 行）、SEMI-SMT-001（3 行）、FG-ESS-002（6 行）、FG-ESS-001 V1.1 DRAFT（6 行）、FG-ESS-003（1 行）、**FG-ESS-005（9 行）**、**SEMI-PDU-001（4 行）**；ACTIVE 7 / DRAFT 1 | 成品→半成品→原料三层展开（FG-ESS-001/002/005 均 3 层）；每行带用量、损耗率与关联工序（`items[].operationCode`）；两行带替代料（优先级 1、比例 1:1） | BOM ACTIVE 唯一约束、损耗率、替代料 |
| 4 | 资源 `/master-data/resources` | 12 个资源，5 个资源组；全部绑定日历；能力模式含 `UNIT_PER_HOUR` / `BATCH_SECONDS` | SMT、模组、PACK、老化、EOL 全链路产能 | 资源能力 + 日历绑定 |
| 5 | 日历 `/master-data/calendars` | 全厂单班 08:00–20:00 + 2 个节假日；SMT/PACK 双班 08:00–20:00 & 20:00–08:00 | 两班制与年度检修对排产的影响 | 班次 + 节假日 |
| 6 | 工艺路线 `/process/routings` | 3 条 ACTIVE 路线：FG-ESS-001/002 各 8 节点 7 边，SEMI-MOD-001 2 节点 1 边 | 产品工艺路线管理 | 路线版本与 ACTIVE 唯一 |
| 7 | 路线编辑器 `/process/routings/:id` | DAG：SMT→AOI→(CELL→MODULE)→PACK→老化→EOL→包装，含汇合节点 | 图形化查看工序依赖 | `routing_node` / `routing_edge`（FS） |
| 8 | 订单 `/orders` | 10 个 `DM-SO-1001..1010`，状态覆盖 5 种；交期 = 基准日 +20 ~ +53 天；数量 18~90；**10/10 绑定客户**；每单 `ext_attrs` 带下单日/客户 PO/联系人/收货地址/币种/单价/金额/明细行；1001、1009 为多行订单；`DM-SO-1010` 为缺料演示单（DRAFT、无工艺路线、不排产） | 状态流转、冻结/锁定、跨客户排产、多行订单与金额口径、缺料单与可排单分离 | 订单状态机 + 冻结语义 |
| 9 | 策略 `/strategies` | 2 个策略，各 8 条 `strategy_rule`，权重合计 100 | 交期优先 vs 均衡产线的目标差异 | 8 条 SOFT 规则 → Timefold 软约束 |
| 10 | MRP `/mrp/analysis` | **三档齐套**：① `1008` 单张 `FULL`（90 台齐套）；② `1008+1009` 批次 `PARTIAL`（1008 齐套、1009 缺 2 项，其余齐套）；③ `1010` 单张 `SHORTAGE`，缺 `RAW-BMS-001` 40 套（需求 40 / 可用 0）。1009 的两项缺口：`RAW-THM-001` 需 60.6（0 在手，**可由 `RAW-THM-002` 替代**）；`RAW-BMS-002` 需 30（0 在手，**在途 30 预计 +60 天到货，晚于交期 +53 天**）。七单（1002–1008）合计 `PARTIAL`，`RAW-ELEC-002` 缺 154 只 | 齐套检查、逐层 BOM 展开、部分齐套、缺料替代与在途 ETA、缺料阻断排产 | 在途仅计 `expected_date <= 最早需求交期`；可用量 = 在手 + 在途 - 安全库存 |
| 11 | 求解 `/solve-tasks` | SOLVED×3（hard 0 / soft -210、-165、-132）、FAILED×1、CANCELLED×1；成功任务 4 条归因（`implicated_facts` 为 `[{"type":"TASK","id":<真实 task.id>}]`，可被 `GET /agent/explain/{taskId}` 反查到）；六单全量显式 120/120 预算实测 `SOLVED / 0hard / 0soft / 48 任务 / 120.7s / overlap=0` | 求解结果、未排任务、软分解释、AI 解读归因、缺料单 422 | 引擎状态机 + 约束归因 |
| 12 | 资源甘特 `/gantt` | BASELINE 6 订单（1002–1007）× 8 工序 = 48 任务，8 个资源有排程 | 产线负荷、换型、空闲窗口 | 任务时间窗 + 资源日历 |
| 13 | 订单甘特 `/gantt-orders` | 返回 10 个订单（6 个已排 + 4 个未排：1001/1008/1009/1010），已排订单自基准日 +0 天起连续排程；1005 op1 位于基准日 +1 天 08:00–11:00 锁窗内 | 订单级交付节奏、锁窗落点、未排单区分 | 订单-任务关联 |
| 14 | 导入中心 `/import` | 2 个 import_job：materials SUCCEEDED（10 新增/2 更新）、orders FAILED（2 条错误行） | 模板导入、失败重试 | 异步导入状态机 |
| 15 | 计划发布 `/plans/publish` | BASELINE 下 2 个 PUBLISHED；全局 4 个 | 发布版本、任务数、回滚链 | 发布快照 + `rollback_of` |
| 16 | 计划对比 `/plans/compare` | 3 个版本对比：task 32/48/48，soft -210/-165/-132，推荐 003 | 不同策略/插单后的差异 | 同 sandbox 取发布时间前最新 SOLVED |
| 17 | 沙箱 `/sandbox` | BASELINE + WHAT_IF + ARCHIVED；WHAT_IF 对比 BASELINE `moved=48`、`setupDelta=-1` | 插单仿真、apply 归档 | 按 `orderNo + operationIndex` 比对 |
| 18 | 系统用户 `/system/users` | 3 个用户（admin + planner.demo + engineer.demo） | 角色与账号管理 | RBAC |
| 19 | 审计日志 `/system/audit` | 14 条：登录失败、订单状态变更、冻结、沙箱 apply、计划发布/下发、任务调整、导入、用户变更 | 合规追溯 | 审计动作覆盖 |

---

## 4. 重置与清理

```powershell
# 只清演示数据（保留客户 CUS-001..008 与其它车道数据）
.\scripts\seed-demo.ps1 -Database aps_demo_verify -Reset

# 完整验证：临时库 + 独立实例
# 1) 建库并执行 db/migration/V1..V14
# 2) 灌注
.\scripts\seed-demo.ps1 -Database aps_demo_verify -Scene all
# 3) 验证结束
psql -h 127.0.0.1 -p 55432 -U aps -d postgres -c "DROP DATABASE aps_demo_verify WITH (FORCE);"
```

重置后业务表（物料/订单/策略规则/任务/求解/计划/审计）计数为 0，重新灌注后回到基线计数。

---

## 5. 数据可信度自检

| 指标 | 取值范围 | 说明 |
| --- | --- | --- |
| 工序标准工时 | 900 ~ 10800 秒（15 ~ 180 分钟） | 无 0.01 秒级异常值 |
| 资源默认换型 | 600 ~ 3600 秒（10 ~ 60 分钟） | — |
| 换型矩阵 | 900 ~ 7200 秒（15 ~ 120 分钟） | 覆盖同系列/跨系列 |
| 实际任务时长 | 30 ~ 240 分钟 | 含老化 3 小时 |
| 班次 | 08:00–20:00；20:00–08:00 | 双班制 |
| 订单数量 | 18 ~ 90 台 | 大批量 1008 = 90 台；缺料演示单 1010 = 40 台；多层订单 1001 = 36（30+6）、1009 = 30（24+6） |
| 订单金额 | 3,360,000 ~ 15,120,000 CNY；1007 为 1,800,000 USD | 单价 × 数量逐行计算，多行订单取行金额合计 |
| 订单交期 | 基准日 +20 ~ +53 天（锚定 2026-11-05 时为 11-25 ~ 12-28） | 远离求解窗口终点（+7.2 天） |
| 在途日期 | 基准日 +7 / +20 / +60 / +76 / +115 天 | 前三条近期（+60 若晚于需求日则不计）；后两条被 MRP 裁剪 |
| 库存口径 | 在手 0 ~ 20000 | `RAW-BMS-001` 为 0 且无在途（缺料演示）；六单紧俏件 `RAW-ELEC-002` 为 400 + 在途 50、安全库存 20 |
| 任务起点口径 | seed 写「换型开始」；求解器落库写「加工开始」 | **冻结任务首次重排会整体后移一个换型时长，属已知约定差异** |
| 审计 | 14 条 | 无空表 |

### 5.1 订单清单（10 张）

| 订单 | 产品 | 数量 | 交期（基准日偏移） | 状态 | 冻结/锁定 | 进入六单全量 |
|---|---|---|---|---|---|---|
| `DM-SO-1001` | FG-ESS-001 | 36 | +20 天 | DRAFT | UNFROZEN | 否（试用批，待确认） |
| `DM-SO-1002` | FG-ESS-001 | 24 | +24 天 | CONFIRMED | UNFROZEN | **是**（BASELINE 锚点 +6 天 08:00） |
| `DM-SO-1003` | FG-ESS-002 | 18 | +27 天 | CONFIRMED | **FROZEN** | **是**（8 条任务级 FROZEN，锚点 +4 天） |
| `DM-SO-1004` | FG-ESS-002 | 32 | +30 天 | CONFIRMED | **LOCKED_RESOURCE** | **是**（op4 锁 `RES-MODULE-01`） |
| `DM-SO-1005` | FG-ESS-001 | 48 | +35 天 | RELEASED | **LOCKED_TIME_WINDOW** | **是**（op1 锁 +1 天 08:00–11:00） |
| `DM-SO-1006` | FG-ESS-001 | 20 | +40 天 | IN_PROGRESS | UNFROZEN | **是**（有在制任务） |
| `DM-SO-1007` | FG-ESS-002 | 60 | +45 天 | SCHEDULED | UNFROZEN | **是** |
| `DM-SO-1008` | FG-ESS-001 | 90 | +49 天 | CONFIRMED | UNFROZEN | 否（七单负例；单张 MRP `FULL`） |
| `DM-SO-1009` | FG-ESS-005 | 30 | +53 天 | DRAFT | UNFROZEN | 否（150kWh 新型号首单；明细 24 台 FG-ESS-005 + 6 台 FG-ESS-002；**部分齐套**） |
| `DM-SO-1010` | FG-ESS-003 | 40 | +43 天 | DRAFT | UNFROZEN | 否（**缺料演示单**，无工艺路线） |

### 5.2 冻结清单（进入求解的硬约束）

| 订单 | 层级 | 锁类型 | 落点 | 重排行为 |
|---|---|---|---|---|
| `DM-SO-1003` | 任务级 8 条 | `FROZEN` | FG-ESS-002 全工序 | 机时/资源/时间全部不动 |
| `DM-SO-1004` | op4（模组） | `RESOURCE` | `RES-MODULE-01` | 只能排在该资源上 |
| `DM-SO-1005` | op1（SMT） | `TIME_WINDOW` | 基准日 +1 天 08:00–11:00 | 加工窗口必须落在窗内 |

> 订单级 `freeze_status` / `ext_attrs.freezeLock` 与任务级锁是两层校验：订单级约束不进入求解模型，只在人工调整 `TaskAdjustService.validate` 时拦截（越界 `42201`）；任务级锁进入求解。

### 5.3 库存与产能口径

- 库存 22 行：`RAW-CELL-001` 8000（安全 200）、`RAW-SMT-001` 2000（50）、`RAW-SMT-002` 20000（500）、`RAW-STR-001` 1000（20）、`RAW-ELEC-001` 5000（100）、`RAW-ELEC-002` 400（20）、`RAW-ELEC-003` 3000（100）、`SEMI-SMT-001` 340（30）、`SEMI-MOD-001` 450（30）、`AUX-CHEM-001` 500（20）、`RAW-BMS-001` **0（0）**；150kWh 族：`RAW-THM-001` **0（10）**、`RAW-THM-002` 120（20）、`RAW-BMS-002` **0（0）**、`RAW-BMS-003` 40（10）、`SEMI-PDU-001` 80（10）、`RAW-ELEC-004` 500（50）、`RAW-ELEC-005` 600（100）、`RAW-STR-002` 5000（500）、`RAW-PKG-001` 5000（200）、`AUX-CHEM-002` 300（50）、`SEMI-HARNESS-001` 80（20）。
- 在途 5 行：`RAW-ELEC-002` 50（+20 天）、`RAW-ELEC-002` 200（+76 天）、`RAW-CELL-001` 2000（+7 天）、`RAW-CELL-001` 4000（+115 天）、`RAW-BMS-002` 30（**+60 天**，晚于 1009 交期 +53 天，本次不计入可用量）。
- 替代料 2 组（写在 `bom_item.substitutes`，含 `materialId / materialCode / priority / ratio`）：`RAW-THM-001 → RAW-THM-002`、`RAW-BMS-002 → RAW-BMS-003`，优先级 1、比例 1:1。
- 产能：SMT 120/h、AOI 200/h、CELL 150/h、MODULE 6/h、PACK 4/h、AGING batch=20/7200s、EOL 20/h、PACKING 30/h。
- 日历：`CAL-SMT-2SHIFT`（SMT 贴片车间两班制，Mon-Sat 08-20 + Mon-Fri 20-08）、`CAL-PACK-2SHIFT`（包装车间两班制，周日 36h 停机）、`CAL-PLANT-1SHIFT`（全厂单班制 08-20 Mon-Sat）、`CAL-7X24`（默认）。

### 5.4 订单商务信息（`orders.ext_attrs`）

订单头 `qty` 为 MRP 口径；商务明细在 `ext_attrs.lines`（`productCode / qty / unitPrice / currency / amount / dueDate`）。客户主数据 `customer.ext_attrs` 另带联系人、电话、默认收货地、信用额度与币种。

| 订单 | 客户 / 联系人 | 下单日（锚定 11-05） | 币种 | 金额 | 明细行 |
|---|---|---|---|---|---|
| `DM-SO-1001` | CUS-003 / 陈志强 | -14 天（10-22） | CNY | 6,768,000 | 2 行：FG-ESS-001×30 + FG-ESS-005×6 |
| `DM-SO-1002` | CUS-001 / 张伟 | -9 天（10-27） | CNY | 4,032,000 | 1 行 |
| `DM-SO-1003` | CUS-002 / 李娜 | -10 天（10-26） | CNY | 3,924,000 | 1 行 |
| `DM-SO-1004` | CUS-003 / 陈志强 | -11 天（10-25） | CNY | 6,976,000 | 1 行 |
| `DM-SO-1005` | CUS-001 / 张伟 | -12 天（10-24） | CNY | 8,064,000 | 1 行 |
| `DM-SO-1006` | CUS-004 / 赵敏 | -13 天（10-23） | CNY | 3,360,000 | 1 行 |
| `DM-SO-1007` | CUS-005 / Anna Schmidt | -15 天（10-21） | USD | 1,800,000 | 1 行（单价 30,000 USD） |
| `DM-SO-1008` | CUS-001 / 张伟 | -16 天（10-20） | CNY | 15,120,000 | 1 行 |
| `DM-SO-1009` | CUS-002 / 李娜 | -5 天（10-31） | CNY | 8,220,000 | 2 行：FG-ESS-005×24 + FG-ESS-002×6 |
| `DM-SO-1010` | CUS-002 / 李娜 | -6 天（10-30） | CNY | 3,920,000 | 1 行 |

> 口径说明：订单头 `product_id/qty` 仍是 MRP 展开依据（1009 头为 FG-ESS-005×30），`ext_attrs.lines` 仅作商务明细，不参与 MRP。`priceByCode` 为 CNY 目录价，1007 出口合同按整单 USD 单价覆盖。

---

## 6. 客户维度

- `orders.customer_id` 为 UUID、可空；本脚本 **10/10 订单均已绑定**（5 家客户）。
- 梯度：
  - 同一客户多订单：`CUS-001` 绑定 `DM-SO-1002 / 1005 / 1008`（共 162 台，FG-ESS-001），形成「一个大客户占满 PACK 产能」的演示场景。
  - `CUS-002` 绑定 `DM-SO-1003 / 1009 / 1010`；其中 `DM-SO-1009`（30 台 FG-ESS-005 新型号首单）为部分齐套演示单，`DM-SO-1010`（40 台 FG-ESS-003）为 DRAFT 缺料演示单，均不进入六单可排集合。
  - 客户独占产能：`CUS-005` 的 `DM-SO-1007`（60 台 FG-ESS-002）+ `CUS-002` 的 `DM-SO-1003`（18 台同产品）连续占用模组/PACK 线。
- 订单列表/详情返回 `customerId`，前端客户列不再显示「未指定」。
- **已知缺口**：导入/导出模板（`ImportEntity.ORDERS`）目前不含客户列，因此导出 xlsx 里没有客户字段；未自行改契约。订单详情/列表接口可正常回显 `customerId`。
- 说明：`OrderView` 仅返回 `customerId`（UUID），没有 `customerName`；前端若需要名称需另接客户接口，本车道不改契约。

---

## 7. 待其它车道

| 实体 | 状态 | 处理 |
| --- | --- | --- |
| 换型矩阵 `setup_matrix_entry` | V14 已建表 | 已补 6 条（`DEMO` 前缀 UUID `e2c00000`） |
| ATP（`atp_query` / `atp_result`） | 表不存在 | **待该车道建表后补**；脚本运行时输出 SKIP 提示 |
| `mfg-agent-platform` 外部对接 | 表不存在 | **待该车道建表后补** |
| 导入/导出客户列 | 契约未包含 | **待导入导出车道扩展列** |

---

## 8. 验证记录（2026-11-05 重灌 aps_demo）

实例：`18203`，库：`aps_demo_verify`（由种子态 `aps_demo` 模板复制），`APS_SECURITY_MODE=off`，MRP 拦截默认开启。

- 幂等：`aps_demo` 连跑两次 `-Scene all`，计数逐项一致：
  `audit=14|bom_items=24|boms=6|customers=8|imports=2|inventory=11|materials=14|orders=10|plans=4|routings=3|rules=16|setup=6|solve=5|tasks=136`
- 28 个请求全部 `HTTP 200`；求解详情/归因已改为从 `solve_task` 动态取最近一条 `SOLVED` id（本次 id=64），不再硬编码 `/solve-tasks/1`。
- 缺料演示单：`DM-SO-1010` 单张 MRP `status=SHORTAGE`、`kitted=false`、`RAW-BMS-001` 需求 40 / 可用 0 / 缺 40，trace `d00c5d41719f409ea2a09dbec5021c05`。
- 齐套对照：`DM-SO-1008` 单张 `status=FULL`（90 台齐套），不再承担缺料镜头。
- 六单全量（1002–1007）：`SOLVED / hard=0 / soft=0 / unassigned=0 / 48 任务 / 17.0s`，提交 trace `84c410760cdb4ccabd140e58b2cfe3ce`，轮询 trace `dd40f4c1011c424e8ed6d77c6138bf62`。
- 七单（1002–1008）：`HTTP 422 / code=42200`，`MRP 齐套未通过（PARTIAL），缺料物料: RAW-ELEC-002`，trace `ccad207fa17b42d3ba7b0c60b1d3014d`。
- 计划对比：3 个版本 soft `-132/-165/-210`，推荐 `PLAN-20261105-003`。
- 沙箱对比：`movedTaskCount=48`、`setupCountDelta=-1`。
- 订单：`total=10, withCustomer=10, distinctCustomers=5`。
- 清理：停实例后 `DROP DATABASE aps_demo_verify WITH (FORCE)`，端口 18203 释放；`aps_demo` 保持种子态；`aps_local` 与 18085 未触碰。

### 8.1 日期相对化验证（2026-11-05，`aps_demo_rel_verify`）

- `-AnchorDate 2026-11-05`（显式）与不传（默认当天）两次灌注，业务日期逐项等价；`due_date`、`task` 起止、`holidays`、`snapshot_at`、`expected_date`、`solve_task`/`plan_version` 时间戳同一锚点下逐字一致。
- 求解窗口实测 `window_start = 当天 09:00`、`window_end = 当天 +7 天 13:00`；订单交期 +20~+53 天在窗口外远侧；1003 `FROZEN` 落 +4 天、1005 锁窗落 +1 天，均在窗内。
- 同锚点连跑两次计数：`audit=14|bom_items=37|boms=8|customers=8|imports=2|inventory=22|materials=26|orders=10|plans=4|routings=3|rules=16|setup=6|solve=5|tasks=136`，`due/task/holiday/snapshot/transit/solve/plan` 指纹一致。
- 六单显式预算（`timeoutSeconds=120`、`unimprovedSecondsSpentLimit=120`）：`SOLVED / 0hard / 0soft / 48 任务 / overlap_pairs=0`。
- 归因自证：`GET /gantt/order` 取真实 `taskId` → `GET /agent/explain/{taskId}` 返回 `available=true`、`source=EVIDENCE`、`sourceSolveTaskId` 有值、`constraintImpacts` 非空。
- 纪律：`aps_demo` 未写入；验证库用后 `DROP ... WITH (FORCE)`。

### 8.2 真实订单化验证（2026-11-05，`aps_demo_rel_verify`）

实例：`18204`，库：`aps_demo_rel_verify`（`template0` + 手工执行 V1–V14），启动参数 `--spring.flyway.enabled=false`，只读 18085 未触碰。

- 幂等：同锚点（`-AnchorDate 2026-11-05`）连跑三次，计数逐项一致：
  `audit=14|bom_items=37|boms=8|customers=8|imports=2|inventory=22|materials=26|orders=10|plans=4|routings=3|rules=16|setup=6|solve=5|tasks=136`。
  指纹（`customer/material/bom/bom_item/orders/inventory/transit/task/solve_task/plan_version/strategy_rule/routing/routing_node/routing_edge/calendar/resource/operation_template/audit_log/import_job/setup_matrix/sandbox`）逐行一致；`plan_version.snapshot` 内嵌的是运行期 `taskId`（BIGSERIAL），指纹比对时按 `"taskId":"?"` 归一化，除此之外全部一致。
- 三档齐套（`POST /api/v1/mrp/analysis`，均 `HTTP 200 / code=0`）：
  - 全齐：`DM-SO-1008` → `FULL`，`orders[0].shortages=[]`，trace `bd5c1d799f524ab1851acd7d12174857`。
  - 部分齐套：`DM-SO-1008 + DM-SO-1009` → `PARTIAL`，1008 `kitted=true`，1009 `kitted=false` 且只缺 2 项，trace `473f3f9302114161b4ecb142a1ec9700`；
    缺料行：`RAW-BMS-002` 需求 30 / 可用 0 / 缺 30（`transitQty=0`，在途 30 天未计），`RAW-THM-001` 需求 60.6 / 可用 -10 / 缺 60.6；`suggestedPurchaseDate` 分别为 11-28、12-13。
  - 完全缺料：`DM-SO-1010` → `SHORTAGE`，`RAW-BMS-001` 需求 40 / 可用 0 / 缺 40，trace `f21b68aab3be48588c6152342c166813`。
- 单张 `DM-SO-1009` 单查返回 `SHORTAGE`（trace `bef4d99f20d24bb19004fa68798afb99`），但 `shortages` 只有 2 行（BOM 逐层展开共 16 项物料）；`MrpOrderResult.kitted` 是布尔值，单张订单无法表达「部分齐套」，故 `PARTIAL` 必须按批次（1008+1009）查询。
- 替代料：`GET /api/v1/boms/e1140000-...007`（FG-ESS-005）返回 `RAW-THM-001 substitutes=["RAW-THM-002"]`、`RAW-BMS-002 substitutes=["RAW-BMS-003"]`；`bom_item.substitutes` 内含 `priority=1`、`ratio="1:1"`。**MRP 的 `MrpShortage` DTO 无替代料与 ETA 字段，替代建议只能在 BOM 详情看，在途 ETA 只能查 `inventory_transit`。**
- 在途 ETA：`RAW-BMS-002` 30 套 `expected_date=2027-01-04`（锚点 +60 天，晚于 1009 交期 2026-12-28），故不计入可用量。
- 六单（1002–1007，显式 `120/120`）：solve id `16`，`SOLVED / 0hard / 0soft / assigned=48 / persisted=48 / unassigned=0 / durationMs=120682 / terminationReason=TIME_LIMIT`，`task` 共 48 条、资源重叠 `0`。
- 28 项端点冒烟全部 `HTTP 200`（含 `health / materials / boms / orders / resources / calendars / operation-templates / routings / strategies / sandboxes / plans / audit-logs / constraint-dsls / setup-matrix / mrp-suggestions / gantt-order / gantt-resource / boms/{id} / boms/{id}/tree / materials/{code} / orders/{no} / solve-tasks/{id} / plans/{id} / solve-tasks/{id}/justifications / routings/{id}/graph`）。
- 七单（1002–1008）：`PARTIAL`，`RAW-ELEC-002` 需求 584 / 可用 430 / 缺 154 只，trace `b962cfba640241dbb7ac989350678f39`，证明新增物料族未改动六单紧俏件口径。
- 纪律：`aps_demo` 零写入；验证库与实例用后清理。
