# 开源 APS / 排产调度项目竞品调研报告

> 报告目的：为「轻量开源智能 APS（Agent + 算法双引擎）」的 PRD 设计与技术选型提供参考
> 报告日期：2026-11-03
> 调研范围：Timefold Solver（原 OptaPlanner）、frePPLe、JVS-APS，以及补充的国产开源项目 Carbon、pro-mes
> 参考来源：GitHub / Gitee 官方仓库、frepple.com、timefold.ai、掘金 / CSDN / SegmentFault / 知乎等一手与二手资料

---

## 一、执行摘要（TL;DR）

1. 三大主流开源项目定位差异明显，不存在「一个包打天下」的方案：
   - **Timefold Solver**：Apache-2.0 的通用**约束求解器库**（原 OptaPlanner 团队 2023 年分叉），算法能力最强、协议最友好，但**不是 APS 应用**，需要业务层自建。
   - **frePPLe**：C++ 引擎 + Django Web 的**完整 APS 应用系统**，含需求预测、有限产能排程、甘特图、ERP 集成，最贴近生产场景，但**算法先进性有限**（以启发式规则为主，无元启发式与精确求解），且历史上以 AGPLv3 发布（新版 Community Edition 官方声明改为 MIT，需按仓库当前 LICENSE 文件为准）。
   - **JVS-APS**：国内软开企服开源，Java Spring Cloud + Vue3 技术栈，**基于 OptaPlanner** 的排产求解器，中文原生、开箱即用、可私有化部署，是国内 APS 二次开发**最现实的起点**。

2. 补充调研的两个国产项目：**Carbon**（AGPLv3，ERP/MES/QMS 一体化）与 **pro-mes**（开源 MES + AI 异常预警）适合作为周边生态参考，而不是排产算法内核。

3. 针对「Agent + 算法双引擎」的轻量 APS 定位，推荐组合方案：
   - **业务底座借鉴 JVS-APS** 的模块划分（基础数据 / 生产工艺 / 生产计划）与中文交互范式；
   - **求解引擎集成 Timefold Solver**（Apache-2.0）作为默认算法引擎，替换 JVS-APS 中的旧版 OptaPlanner；
   - **精确求解预留 Google OR-Tools CP-SAT 接口**（Apache-2.0），用于中小规模 Job Shop 的最优性证明；
   - **Agent 层自研**，承担数据清洗、约束语义化、异常重排解释、what-if 对话式排产等 LLM 优势场景。

4. **协议风险要点**：
   - AGPLv3（frePPLe 旧版 / Carbon）对 SaaS 与闭源商用极不友好，任何网络服务化调用都会触发「传染性开源」条款；
   - MIT / Apache-2.0（Timefold、frePPLe 新版 Community、OR-Tools）可闭源商用，仅需保留版权声明；
   - **JVS-APS 在 Gitee 主页声明开源可下载源码，但商业授权 / 二次开发范围需在 LICENSE 与商业支持条款中逐一确认**，避免直接 fork 后闭源分发引发纠纷。

---

## 二、单项目深度分析

### 2.1 Timefold Solver（原 OptaPlanner）

| 维度 | 说明 |
|---|---|
| 定位 | 通用**约束求解器 / AI Solver 库**，非完整 APS 应用 |
| 起源 | 2023 年 4 月由原 Red Hat OptaPlanner 团队分叉创建 |
| 开源协议 | **Apache-2.0**（Community Edition）；另有闭源 Enterprise Edition |
| 主语言 | Java（核心）、Kotlin、Python（solver-python 已归档，主推 JVM） |
| 运行要求 | JDK 21+，Maven 3.9.11+ |
| GitHub Stars | 主仓 ~1.84k，Quickstarts ~600，年发布 19 次，最新 v1.35.0（2026-10） |
| 适用场景 | 车辆路径（VRP）、员工排班、维护排程、任务分配、学校课表、云资源优化、会议排程、**Job Shop Scheduling**、装箱 |

**技术架构**

- 模块化设计：核心 Solver + ScoreDirector（Bavet 增量评分引擎）+ 持久化模块（Jackson/JAXB/JPA/JSONB）+ Quarkus/Spring Boot 集成。
- 求解流水线：**构造启发式（Construction Heuristics）→ 局部搜索（Local Search）→ 可选的模拟退火 / 禁忌 / 大洪水**。
- 支持三种多线程策略：多线程求解、分区搜索（Partitioned Search）、多求解器并行。

**核心算法能力**

- **7 种 Acceptor**：Hill Climbing、Tabu Search（Entity / Value / Move 三类）、Simulated Annealing、Late Acceptance（默认推荐）、Diversified Late Acceptance、Step Counting Hill Climbing、Great Deluge。
- **丰富 Move 操作**：ChangeMove、SwapMove、Pillar Move、List Variable Move 等，天然适配排产（一个 Resource 持有多个 Task 的 List Variable 模式）。
- **Constraint Stream API**：类型安全的 DSL，支持增量计算与「约束解释（Constraint Justification）」——对 Agent 层输出「为什么这样排」极为友好。
- **Score 类型**：SimpleScore / HardSoftScore / HardMediumSoftScore / BendableScore（最多 5 级），均支持 BigDecimal 高精度版本。

**优势**

- Apache-2.0 协议，**可闭源商用**，无传染风险。
- 算法库完整、建模能力顶级，社区活跃（90 天合并 PR 60 个，17 位活跃贡献者）。
- Spring Boot / Quarkus 深度集成，容易接入现有 Java 微服务栈。
- Constraint Stream 支持「解释性输出」，与 Agent 双引擎架构天然契合。

**短板**

- **只是求解器库，无 UI、无报表、无 ERP/MES 集成、无需求预测**，所有业务模型都要自建。
- 纯 JVM 实现，超大规模问题上比 C++ 引擎略慢。
- 无 CP-SAT / MIP 精确求解能力，无法证明最优性。
- 中文社区薄弱，文档几乎全英文。

**二次开发门槛**

- **中高**：需要熟悉 Java + 约束建模思想；官方 Quickstarts 覆盖 Job Shop / Maintenance / VRP 模板，1~2 周可跑通首个 PoC。
- 建模工作量最大：`@PlanningEntity` / `@PlanningSolution` / Constraint Provider 需要按业务定制。

---

### 2.2 frePPLe

| 维度 | 说明 |
|---|---|
| 定位 | **完整的开源 APS / 供应链计划系统**（需求预测 + 有限产能排程 + 库存计划） |
| 起源 | 2007 年，比利时 frePPLe bv 公司 |
| 开源协议 | 官方 GitHub 当前声明：**Community Edition = MIT License**；早期版本（JiangLongLiu 分叉等）标注 **AGPLv3**；企业版 / 云版闭源收费 |
| 主语言 | C++ 核心引擎（~25 万行）+ Python Django Web 层（~10 万行） |
| 数据库 | PostgreSQL |
| GitHub Stars | 主仓 ~1.8k |
| 适用场景 | 离散制造 / 混合制造、多地点库存规划、按订单生产（MTO）、按库存生产（MTS） |

**技术架构（4 层）**

1. 浏览器 UI 层（现代浏览器均可）；
2. Web 应用层：Apache + Python Django，插件式扩展，REST API；
3. 计划引擎层：C++，支持批处理与连续两种运行模式；
4. 数据层：PostgreSQL，支持多场景（sandbox）数据隔离。

**核心排产算法**（`src/solver/`）

- **约束传播 + 启发式规则**，非现代元启发式：
  - `OperatorForward` 前向计划：按 BOM 展开，正推工序时间；
  - `OperatorBackward` 后向计划：从交期倒推最晚开始时间；
  - `OperatorPropagate` 约束传播：库存、产能、前置约束剪枝；
  - `OperatorRotate` 资源协调：多需求竞争同资源时按优先级重排；
  - `OperatorDelay` 延迟重排：无法满足时自动延后；
  - `OperatorAlternate` 替代路径选择：多路径下按成本/产能/交期择优。
- 求解理念：TOC（约束理论，围绕瓶颈排产）+ 拉动式计划（尽可能晚开始）+ 精益（减少中间库存）。

**需求预测**（`src/forecast/`）

内置 7 种时间序列算法：移动平均、加权移动平均、单指数平滑、Holt 双指数平滑、Holt-Winters 三指数平滑（含季节）、Croston 间歇性需求、自适应平滑（自动选最优）。

**功能特性**

- 生产计划与详细排程（有限产能 + 有限物料）；
- 交互式甘特图（Community 版受限，Cloud/Enterprise 版功能完整）；
- 交期承诺（ATP/CTP，企业版）；
- 换型矩阵优化（Setup Matrix，企业版）；
- 保质期计划（Shelf Life，企业版）；
- What-if 场景管理；
- 与 Odoo / ERPNext / SAP 双向集成（REST API + CSV/Excel 导入导出）。

**优势**

- **开箱即用**：完整 Web UI + REST API + Odoo 连接器，2~4 周可上线基础场景。
- C++ 引擎性能好，秒级响应万级订单。
- 深度理解制造业场景（多级 BOM、替代料、换型、保质期、多工厂）。
- 内置需求预测（多数国产开源 APS 不具备）。

**短板**

- **算法先进性有限**：以启发式规则为主，缺 Late Acceptance / 模拟退火 / CP-SAT 等现代方法；无最优性保证；无并行求解（单线程）。
- **数据模型固定**，扩展到非制造业场景较难。
- **协议演变复杂**：早期镜像与文档标注 AGPLv3，新版 GitHub 主页声明 Community Edition 为 MIT；如果做 SaaS，务必以你 fork 的具体 commit 的 `LICENSE` 文件为准。
- 企业版功能（甘特图交互、Setup Matrix、Shelf Life、ATP）都是闭源收费；社区版功能明显缩水。
- 中文语言与本地化服务薄弱，国内实施主要靠第三方。

**二次开发门槛**

- **中**：Django + Python 熟悉者可扩展；C++ 核心引擎修改门槛较高。
- 建议以「插件 / 扩展 / API 消费者」的方式接入，而非改核心引擎。

---

### 2.3 JVS-APS

| 维度 | 说明 |
|---|---|
| 定位 | **面向国内工业企业的开源 APS 排产系统**，主打「算法驱动 + 低代码融合」 |
| 起源 | 软开企服（重庆软开企业服务有限公司） |
| 开源地址 | Gitee：`software-minister/jvs-aps`；GitHub 镜像：`wangchao2418/jvs-aps` |
| Stars | Gitee ~2k+ |
| 开源协议 | Gitee 声明「开源可下载源码、可私有化部署、可二次开发」，**具体 LICENSE 需以仓库根目录 LICENSE 文件为准**；同时官方提供商业技术支持与定制服务 |
| 技术栈 | JDK 17 + Spring Cloud + Vue 3 + Nacos + MySQL 8.0 + Redis 6.0 + Maven 3.8 |
| 求解器 | **基于 OptaPlanner**（Timefold 的前身）实现约束求解；宣称使用遗传算法、模拟退火、禁忌搜索 |
| 在线 Demo | https://aps.bctools.cn |

**模块架构**（三大主模块）

1. **基础数据**：物料管理、扩展属性、制造 BOM、主资源 / 辅资源（模具、夹具，含装卸时长）、来料计划、生产订单；
2. **生产工艺**：工序模版、工艺路线（拖拽式设计器，支持前后间隔时长）、生产日历（白班 / 夜班 / 三班倒）；
3. **生产计划**：排产策略、排产计划、任务报工、交期预估、产能预排。

**排产策略（亮点）**

- **初排规则**：优先级 / 需求交付时间 / 客户等级 / 库存状态，支持升降序；
- **任务优化规则（12+ 种）**：最小超期、最少任务超期、短任务优先、交期优先、同品连续生产、最小换型时长、均衡任务数量、均衡任务总时长……每项可独立配置权重；
- **排产方向**：正向 / 反向 / 瓶颈；
- **物料约束 MRP**：开启「约束物料」开关后自动检测缺料，缺料来源为「制造」时自动创建补充生产订单，来源为「采购」时按提前期推迟生产（真正的齐套逻辑）。

**排产结果与可视化**

- 资源甘特图 / 订单甘特图 / 物料需求甘特图，支持拖拽调整；
- 支持人工干预：任务拆分、合并、移动、锁定、冻结；
- 冲突检测与瓶颈预警；
- 排产结果导出报表 DTO 独立包（`dto/report`）。

**代码结构（Java 侧）**

```
jvs-aps-common/…/aps/
  ├─ solve/     排产求解核心（calculate / model / score / impl）
  ├─ service/   facade / adjustment（freeze, merge, move, split）
  ├─ entity/    plan / planning DTO + PO
  ├─ graph/     图结构
  ├─ tree/      树结构
  └─ mapper/    MyBatis 持久化
jvs-aps-mgr/    controller 层
```

**优势**

- **中文原生、开箱即用**：UI、术语、报表全部中文，业务人员可直接上手。
- **技术栈现代且国内主流**：Spring Cloud + Vue3 + MySQL，与国内企业 IT 环境契合度极高。
- **模块划分清晰**，与「基础数据 / 生产工艺 / 生产计划」的经典 APS 三层建模一致，可直接借鉴到 PRD。
- 支持私有化部署 + 全量源码 + 商业支持三条路径。
- 与 JVS 低代码平台同根同源，可快速搭建 ERP/MES/WMS 配套。

**短板**

- 求解器基于**旧版 OptaPlanner**（Timefold 分叉前的版本），算法生态、性能优化、后续维护都不如 Timefold。
- 甘特图交互能力（拖拽调整、锁定 / 冻结）与商业 APS（安达发、悠桦林）相比仍有差距。
- 缺少需求预测模块（frePPLe 的强项）。
- 缺少精确求解能力（无 CP-SAT / MIP）。
- 社区活跃度中等，Issues / PR 数量远小于 Timefold。
- **协议 / 授权条款需要在实际商用前与官方书面确认**，尤其是二次开发后闭源分发的边界。

**二次开发门槛**

- **低-中**：Java 团队 1~2 周可跑通本地部署；工艺路线 / BOM / 资源等元数据都通过 UI 配置，无需改代码；深度定制算法需要熟悉 OptaPlanner 约束建模。

---

### 2.4 补充：Carbon（crbnos/carbon）

| 维度 | 说明 |
|---|---|
| 定位 | 开源 **ERP + MES + QMS 一体化**制造管理系统 |
| 协议 | **AGPLv3** |
| Stars | GitHub ~1.2k |
| 核心能力 | MRP 物料需求计划、多级 BOM 分解与版本控制、MES 通过 WebSocket 实时采集设备产量与良品率、QMS 集成 OpenCV 表面缺陷识别与 SPC 统计过程控制、内置 AI Agent 实现采购订单自动创建与询价 |
| 借鉴价值 | 一体化数据模型（ERP/MES/QMS 共用主数据）、WebSocket 实时设备数据回流、AI Agent 与业务系统的集成模式 |
| 协议风险 | **AGPLv3 传染性强**，SaaS 或网络调用会触发全部源码开放义务；不建议直接作为商业 APS 的底座，只作为架构参考 |

### 2.5 补充：pro-mes

| 维度 | 说明 |
|---|---|
| 定位 | 开源 **MES 系统**，含生产调度排程、物料库存、SPC 质量、设备工艺、AI 异常预警 |
| Stars | GitHub ~800 |
| 借鉴价值 | AI 异常预警算法与 SPC 集成、质量追溯链路设计 |
| 局限 | 排产只是 MES 的一个附属能力，不具备独立 APS 求解器 |

---

## 三、横向对比表

### 3.1 七维度对比（Timefold / frePPLe / JVS-APS / Carbon / OR-Tools）

| 维度 | Timefold Solver | frePPLe | JVS-APS | Carbon | Google OR-Tools（参考） |
|---|---|---|---|---|---|
| **算法能力** | ★★★★☆ 元启发式完整（7 Acceptor + 10+ Move），Constraint Stream 增量评分 | ★★★☆☆ 启发式规则 + 约束传播，无现代元启发式，无并行 | ★★★☆☆ 基于 OptaPlanner（Timefold 前身），遗传/退火/禁忌 | ★★☆☆☆ 规则驱动，无独立求解器 | ★★★★★ CP-SAT + LP/MIP + 图算法，可证明最优 |
| **开箱即用** | ✗ 只是库，无 UI/报表/集成 | ✓ 完整 Web UI + REST API + Odoo 连接器 | ✓ 完整中文 UI + 甘特图 + 排产策略配置 | ✓ ERP/MES/QMS 全套 | ✗ 纯算法库 |
| **中文支持** | ✗ 全英文文档 | △ 界面有翻译，本地化服务薄弱 | ✓ 中文原生，术语、报表全中文 | △ 部分中文 | ✗ 全英文 |
| **甘特图能力** | ✗ 无 | ✓ 交互式甘特图（企业版功能更完整） | ✓ 资源 / 订单 / 物料三视图甘特图，支持拖拽、拆分、合并、锁定、冻结 | △ 基础视图 | ✗ 无 |
| **异常重排 / 插单** | ✓ 通过 List Variable + 增量求解支持，需业务封装 | △ 需重新跑计划 | ✓ 内置动态重排，紧急插单 30 秒内出方案 | △ 手工调整 | ✓ 增量求解可支持 |
| **集成难度** | 中：需自建 Spring Boot 应用，API 清晰 | 中：REST API + CSV/Excel + Odoo 连接器现成 | 低：国内主流 Java 栈，可直接嵌入企业微服务 | 中：AGPLv3 传染风险大 | 高：需自行开发所有业务层 |
| **协议风险** | ✓ Apache-2.0，可闭源商用 | △ 新版 Community 声明 MIT，旧版 AGPLv3；**必须按 fork 的具体 LICENSE 判断** | △ 声明开源可二次开发，但商业条款需与厂商书面确认 | ✗ **AGPLv3 传染性强，SaaS 高风险** | ✓ Apache-2.0 |

### 3.2 项目基础信息速查

| 项目 | 起源年 | 主语言 | Stars | 代码规模 | 部署难度 | 定位 |
|---|---|---|---|---|---|---|
| Timefold Solver | 2023（从 OptaPlanner 分叉） | Java + Kotlin | ~1.84k | ~35 万行 Java | 中（作为库集成） | 约束求解器库 |
| frePPLe | 2007 | C++ + Python | ~1.8k | ~25 万 C++ + ~10 万 Python | 低（Docker） | 完整 APS 应用 |
| JVS-APS | 近年 | Java + Vue3 | Gitee ~2k+ | 中等 | 低（Docker Compose） | 完整 APS 应用 |
| Carbon | 近年 | 多语言 | ~1.2k | 中等 | 中 | ERP/MES/QMS 一体化 |
| Google OR-Tools | 2010 | C++ + 多语言绑定 | ~11k | ~80 万行 C++ | 高（需自建应用） | 运筹算法库 |

---

## 四、选型建议：面向「轻量开源智能 APS + Agent × 算法双引擎」

### 4.1 三种候选路线对比

| 路线 | 描述 | 优点 | 缺点 | 建议 |
|---|---|---|---|---|
| **A. 基于 frePPLe 二次开发** | Fork frePPLe，替换求解器 / 增强甘特图 / 加 Agent 层 | 完整 APS 骨架、内置预测、多 ERP 集成 | 协议演变复杂（MIT/AGPLv3 需核实）、C++ 引擎修改门槛高、算法天花板低、中文本地化需自建 | ✗ 不推荐作为核心底座，可**参考其数据模型与预测算法** |
| **B. 基于 JVS-APS 二次开发** | Fork JVS-APS，替换 OptaPlanner → Timefold，加 Agent 层 | 中文原生、模块划分成熟、Java 栈契合国内企业、甘特图与排产策略配置齐全 | 授权条款需与厂商书面确认、算法基础偏弱、缺少需求预测 | ✓ **推荐作为业务底座参考**（不一定 Fork，可重写） |
| **C. 自研业务层 + Timefold + OR-Tools 双引擎** | 从零搭建基础数据 / 工艺 / 计划模块，算法层集成 Timefold（元启发式）+ OR-Tools CP-SAT（精确求解），Agent 层自研 | 协议最干净（Apache-2.0）、算法天花板最高、Agent 与求解器可深度协同（Constraint Stream 解释性） | 开发周期最长、需要 Java + 约束建模能力的团队 | ✓✓ **最推荐**（与「轻量开源 + Agent 双引擎」定位最契合） |

### 4.2 推荐方案：**路线 C（自研 + 双引擎集成）**，兼顾路线 B 的模块借鉴

**核心架构建议**

```
┌───────────────────────────────────────────────────────────────┐
│              Agent 层（自研，LLM 驱动）                       │
│  · 数据清洗 / 主数据治理 Agent                                │
│  · 约束语义化 Agent（自然语言 → Constraint Stream）           │
│  · 异常重排解释 Agent（Timefold Justification → 中文归因）    │
│  · 对话式 what-if 排产 Agent                                  │
│  · 交期承诺 / 询单响应 Agent                                  │
└───────────────────────────────────────────────────────────────┘
                              │
┌───────────────────────────────────────────────────────────────┐
│              业务应用层（自研，参考 JVS-APS 模块划分）        │
│  · 基础数据：物料 / BOM / 资源 / 工艺路线 / 生产日历          │
│  · 生产计划：订单接入 / 排产策略 / 排产任务 / 甘特图 / 报工   │
│  · 需求预测：借鉴 frePPLe 的 7 种时间序列算法（可选集成）    │
│  · 集成：ERP / MES / WMS 适配器（REST + 中间表 + MQ）         │
└───────────────────────────────────────────────────────────────┘
                              │
┌───────────────────────────────────────────────────────────────┐
│              算法引擎层（双引擎 + 调度）                      │
│  ┌───────────────────────────┐  ┌─────────────────────────┐  │
│  │ Timefold Solver           │  │ Google OR-Tools CP-SAT  │  │
│  │ (Apache-2.0, JVM)         │  │ (Apache-2.0, C++/Py)    │  │
│  │ · 元启发式：LA/TS/SA      │  │ · 精确求解：Interval +  │  │
│  │ · Constraint Stream API   │  │   NoOverlap + Cumulative│  │
│  │ · List Variable 排产建模  │  │ · 中小规模最优性证明    │  │
│  │ · 大规模、增量、可解释    │  │ · 大规模时可作为下界    │  │
│  └───────────────────────────┘  └─────────────────────────┘  │
│                    ↑                                          │
│           引擎路由 / 结果融合器（自研）                       │
└───────────────────────────────────────────────────────────────┘
                              │
┌───────────────────────────────────────────────────────────────┐
│              数据层：MySQL / PostgreSQL + Redis + 时序库      │
└───────────────────────────────────────────────────────────────┘
```

**选型理由**

1. **协议干净**：Timefold 与 OR-Tools 都是 Apache-2.0，可闭源商用、可 SaaS、可分发，无任何传染风险。
2. **算法天花板高**：Timefold 覆盖 90% 的排产场景（大规模、增量、解释性），OR-Tools CP-SAT 用于中小规模 Job Shop 提供最优性证明或紧下界，两者互补。
3. **Agent 双引擎天然契合**：
   - Timefold 的 Constraint Stream 提供 `ConstraintJustification`，Agent 可以直接把「为什么这样排」翻译成中文归因；
   - Agent 可把自然语言约束（"A 客户订单必须优先"、"夜班不能安排换模"）自动转成 Constraint Provider 代码片段；
   - 双引擎路由由 Agent 决策：小规模走 CP-SAT 求精确解，大规模走 Timefold 求高质量近似解。
4. **借鉴 JVS-APS 的模块划分**：基础数据 / 生产工艺 / 生产计划三层结构已经被国内客户验证，中文术语与业务人员心智模型一致，PRD 可直接参考。
5. **可渐进交付**：MVP 阶段只上 Timefold + 基础数据 + 甘特图；后续再补 OR-Tools、需求预测、Agent 层。

### 4.3 什么时候选其他方案？

- 团队 **完全没有 Java 能力**，只有 Python → 走 frePPLe 路线（但要严格核对协议），或 Timefold Python 绑定（已归档，不推荐生产用）；
- **交付周期 < 3 个月**、客户对算法先进性不敏感 → Fork JVS-APS 快速上线，后期再替换求解器；
- 客户是**大型集团 + 半导体 / 汽车主机厂**，需要最优性证明 → 以 OR-Tools CP-SAT 为主，Timefold 为辅；
- 需要**完整供应链计划（含预测、库存、多地点调拨）** → frePPLe 是首选，或在其之上再集成 Timefold。

---

## 五、可直接借鉴的功能清单与架构设计点

### 5.1 业务功能清单（来自 frePPLe + JVS-APS）

**基础数据模块（借鉴 JVS-APS）**

- [ ] 物料管理：类型 / 来源 / 库存 / 扩展属性（颜色、尺寸）
- [ ] 制造 BOM：多级 BOM、替代料、优先级、可视化配置
- [ ] 主资源 / 辅资源：设备、人力、模具、夹具；产能三种定义（单位时间产量 / 单品耗时 / 批次耗时）；装卸时长
- [ ] 生产日历：白班 / 夜班 / 三班倒、法定节假日、维护窗口
- [ ] 来料计划：到货时间、数量、在途状态

**生产工艺模块（借鉴 JVS-APS）**

- [ ] 工序模版：可复用的工序组件，定义资源 + 物料 + 前后关系
- [ ] 工艺路线：拖拽式设计器、串并行工序、前后间隔时长、可引用工序模版

**生产计划模块（借鉴 JVS-APS + frePPLe）**

- [ ] 排产策略：初排规则（优先级 / 交期 / 客户等级 / 库存状态）+ 任务优化规则（12+ 种）+ 权重配置
- [ ] 排产方向：正向 / 反向 / 瓶颈
- [ ] 智能排产：一键生成、预览、提交、人工调整（拆分 / 合并 / 移动 / 锁定 / 冻结）
- [ ] 甘特图：资源 / 订单 / 物料需求三视图，拖拽调整，冲突高亮
- [ ] 物料约束 MRP：自动检测缺料、自动生成补充生产订单、按采购提前期推迟
- [ ] 交期预估 / 产能预排：询单秒级反馈最早可交付日期
- [ ] 动态重排：紧急插单、设备故障、物料延期场景，30 秒内出新方案
- [ ] 任务报工：计划 vs 实际对比、异常触发重排

**需求预测模块（借鉴 frePPLe）**

- [ ] 7 种时间序列算法：移动平均、加权移动平均、单/双/三指数平滑（Holt-Winters）、Croston 间歇性需求、自适应选择
- [ ] 预测误差自动评估、多方法择优
- [ ] 支持外部预测模型（Prophet / LSTM）集成

**供应链协同（借鉴 frePPLe）**

- [ ] 多地点库存规划、安全库存 / 覆盖天数 / 补货批量
- [ ] 自动围栏（AutoFence）：基于「确认供应等待时间是否超过阈值」自动补货
- [ ] What-if 场景管理：多方案对比

### 5.2 架构设计点

1. **算法引擎与业务解耦**：所有求解器（Timefold / OR-Tools / 未来的自研引擎）通过统一的 `SolverAdapter` 接口暴露，业务层不感知具体算法。
2. **约束的 DSL 化 + 语义化**：借鉴 Timefold Constraint Stream，同时用 YAML / JSON DSL 描述业务约束，Agent 层可以把自然语言约束翻译成 DSL，再由代码生成器翻译成 Constraint Provider。
3. **求解结果的可解释性**：Timefold 的 `ConstraintJustification` 必须持久化到 DB，Agent 层消费这些解释并生成中文归因（"该订单延后 2 天，因为模具 M-01 在 3 号被 A 客户订单占用"）。
4. **增量求解 + 冻结机制**：借鉴 JVS-APS 的 `freeze / merge / move / split`，已下达或已开始的任务应「冻结」，重排时只调整未冻结部分。
5. **双引擎路由策略**：Agent 根据问题规模（订单数、工序数、资源数）+ 时效要求 + 最优性要求，自动选择引擎：
   - 询单预估（<1 秒）→ Timefold 构造启发式；
   - 日常排产（分钟级）→ Timefold Late Acceptance；
   - 高价值订单精确排产（小时级）→ OR-Tools CP-SAT；
   - 大规模车间（万级工单）→ Timefold 分区搜索。
6. **数据接入分层**：借鉴 Carbon 的 WebSocket 实时设备数据回流 + JVS-APS 的中间表定时同步；主数据 / 交易数据 / 时序数据分库存储。
7. **Agent 编排框架**：建议 LangGraph（图结构 + 检查点持久化）或 AgentScope（多 Agent 协作），承担「数据治理 → 约束建模 → 求解 → 解释 → 反馈学习」闭环。
8. **多租户 + 多场景（sandbox）**：借鉴 frePPLe 的 scenario 机制，同一份主数据可以创建多个 what-if 沙盘，独立求解、独立对比。

---

## 六、必须规避的坑与协议风险提示

### 6.1 协议风险（**优先级最高**）

| 风险 | 说明 | 规避建议 |
|---|---|---|
| **AGPLv3 传染性** | frePPLe 早期版本、Carbon 均为 AGPLv3。任何通过网络提供服务（SaaS）都会触发**必须开放全部衍生源码**的义务 | 严禁把 AGPLv3 代码嵌入闭源 SaaS；如果一定要用 frePPLe，请以 fork 时点的 LICENSE 为准，或购买商业版 |
| **frePPLe 协议演变** | 官方 GitHub 当前声明 Community Edition 为 MIT，但很多镜像与旧文档仍写 AGPLv3 | 以**你实际 clone 的 commit 的 LICENSE 文件**为准，并保留证据链；升级 / 打补丁前重新核对 |
| **JVS-APS 商业条款** | Gitee 声明「开源可二次开发」，但官方同时提供商业支持；具体是否允许闭源分发、是否要求署名、是否有商用授权费，需书面确认 | 商用前**必须与软开企服签署授权确认函**，或仅作为**架构参考**、代码全部自研重写 |
| **OptaPlanner → Timefold 分叉** | Timefold 是 OptaPlanner 的 Apache-2.0 分叉，两者协议都是 Apache-2.0，但**商标名不同**，宣传时不能混用 | 使用 Timefold 时不要在营销物料中声称「基于 OptaPlanner」，避免商标问题 |
| **依赖库协议** | Timefold / OR-Tools / Spring Boot / Vue 等的传递依赖可能存在 LGPL / GPL / EPL | 上线前跑一次 `license-maven-plugin` 或 `pip-licenses`，输出 SBOM，法务审阅 |

### 6.2 技术坑

1. **不要迷信「一键全自动排产」**
   - 中小工厂基础数据（标准工时、换模时间、良率）误差常在 30%+，任何精密算法输出的都是「数字化垃圾」。
   - **产品定位建议**：人机协同（APS 出初稿，计划员在甘特图上拖拽微调），而不是黑箱自动化。这也是 JVS-APS 与黑湖小工单的市场验证结论。

2. **算法先进性 ≠ 生产可用性**
   - frePPLe 用启发式规则，反而是三者中最贴近真实生产的；OR-Tools CP-SAT 算法最强，但落地需要大量二次开发。
   - PRD 中不要把「用了 XX 先进算法」作为卖点，而要把「订单准时率提升 X%、排产耗时从 X 小时降至 X 分钟」作为卖点。

3. **求解器性能陷阱**
   - Timefold 的 Constraint Stream 若写法不当（如嵌套 `forEach` + `filter`）会导致增量评分退化为全量重算，性能骤降 10 倍。
   - OR-Tools CP-SAT 在超过 5000 个 Interval 变量时求解时间可能爆炸，需要设置合理的 `max_time_in_seconds` 与 hint 解。

4. **甘特图不是「排产结果可视化」那么简单**
   - 拖拽调整要触发**局部重排 + 冲突校验 + 依赖级联更新**，前端交互复杂度高。
   - 建议前端使用 Bryntum Scheduler / DHTMLX Gantt / 阿里 G2 / 蚂蚁 AVA 等成熟组件，不要从零写。

5. **需求预测与排产的耦合**
   - frePPLe 把预测与排产耦合在同一引擎，好处是数据一致，坏处是预测模型难以替换。
   - 建议架构上**解耦**：预测服务独立（可以用 Prophet / LSTM / 外部 ML 平台），排产服务通过 REST 消费预测结果。

6. **多工厂 / 多车间协同**
   - JVS-APS、frePPLe Community 版都不原生支持复杂的多工厂协同，需要业务层自建。
   - 如果目标客户是集团型企业，PRD 要显式说明「多工厂协同 = 二期能力」。

7. **数据主数据治理**
   - ERP 中的物料编码、BOM、工艺路线常有多套（设计 BOM / 制造 BOM / 计划 BOM），APS 落地必须先做主数据映射。
   - 建议在产品中加入「主数据健康度看板」，把数据质量作为一等公民。

### 6.3 商业 / 生态坑

1. **不要与 JVS-APS 直接竞争同一批客户**：他们的销售与服务体系已经覆盖国内中小离散制造，正面刚没有胜算。**差异化定位**建议：
   - Agent × 算法双引擎（自然语言排产、可解释归因）；
   - 更轻量的 SaaS 部署（JVS-APS 是 Spring Cloud + Nacos + Redis + MySQL，运维较重）；
   - 面向**中小型工厂**而非中大型（JVS-APS 的定位是中大型）。

2. **不要用「开源」做主要卖点**：国内制造业客户对「开源」的付费意愿远低于「有效果」。开源只是降低试用门槛的手段，商业化要靠**实施服务 + 高级功能 + 云托管**。

3. **谨慎处理「Timefold Enterprise Edition」的边界**：Timefold 企业版包含多线程求解、邻近选择算法等高级能力，社区版功能足够但性能上限较低。产品宣传时要严格区分「基于 Timefold Community Edition」还是「Timefold Enterprise」。

---

## 七、下一步行动建议（面向 PRD）

1. **本周内**：
   - 拉取 Timefold Solver Quickstarts 中的 `job-shop-scheduling` 与 `maintenance-scheduling` 示例，跑通 PoC，评估建模工作量；
   - Clone JVS-APS Gitee 仓库，本地部署，逐一体验排产策略、甘特图、物料约束 MRP，把 UI 截图与术语整理成 PRD 附录；
   - 与软开企服联系，就 JVS-APS 的商用授权条款获取书面答复。

2. **两周内**：
   - 输出 PRD v0.1：模块划分直接采用「基础数据 / 生产工艺 / 生产计划 / Agent 层 / 算法引擎层」五段式；
   - 完成 Timefold Constraint Stream 与 Agent 自然语言约束的双向翻译 PoC（选 5 个典型约束场景）；
   - 完成 OR-Tools CP-SAT 的 Job Shop 建模 PoC，与 Timefold 结果对比（求解质量、时间、可扩展性）。

3. **一个月内**：
   - 完成 MVP 范围界定：建议 MVP = 基础数据 + 工艺路线 + Timefold 排产 + 甘特图 + 简单 Agent 解释，不含需求预测与多工厂；
   - 输出 SBOM 与协议合规报告，法务审阅；
   - 与 1~2 家目标客户做需求验证，重点确认「Agent 双引擎」的实际付费意愿。

---

## 附录：核心参考链接

- Timefold Solver GitHub：https://github.com/TimefoldAI/timefold-solver
- Timefold Quickstarts：https://github.com/TimefoldAI/timefold-quickstarts
- frePPLe GitHub：https://github.com/frePPLe/frepple
- frePPLe Editions：https://frepple.com/editions/
- JVS-APS Gitee：https://gitee.com/software-minister/jvs-aps
- JVS-APS GitHub 镜像：https://github.com/wangchao2418/jvs-aps
- JVS-APS 在线 Demo：https://aps.bctools.cn
- Carbon GitHub：https://github.com/crbnos/carbon
- Google OR-Tools：https://github.com/google/or-tools
- 开源智能排产系统深度研究报告（CSDN）：https://blog.csdn.net/2604_96270735/article/details/163196635
- 7 款免费开源与轻量级 APS 排程工具全景对比：https://www.xiaogongdan.cn/article/free-aps-production-scheduling-software-recommendation.html
- 2026 年五大主流 APS 排产系统对比：https://segmentfault.com/a/1190000047539040

> **免责声明**：本报告基于 2026-11 前的公开资料整理，各项目的开源协议、功能边界、社区活跃度可能随版本更新变化。商用决策前请以官方仓库当前 LICENSE 与厂商书面确认为准。
