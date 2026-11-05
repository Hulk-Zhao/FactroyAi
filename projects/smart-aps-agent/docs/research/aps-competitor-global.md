# 国际主流智能排产（APS）产品能力调研与 AI 改造建议

> 调研时间：2026-11-05
> 范围：SAP、Kinaxis、Blue Yonder、o9、Siemens Opcenter APS、Dassault DELMIA Ortems/Quintiq、Asprova、Oracle SCM、Anaplan、Plataine、Critical Manufacturing
> 用途：产品决策参考。文中严格区分「厂商宣称」「产品已发布」「有客户/可核实证据」「无法确认」。

---

## 0. 先给结论：最值得抄的 5 件事

### ① 把 Agent 建在「平台工具 + 证据」上，而不是建在提示词上（Kinaxis）

Kinaxis 的 Maestro Agents 不是通用聊天机器人：Agent 原生嵌在 Maestro 的实时计划环境里，调用平台的数据、工作流和工具，能看到约束与 trade-off，输出「为什么这样建议」，并保留 human-in-the-loop。2025-10 发布时已有 Jabil 等客户试点；2026-02 又推出无代码的 Maestro Agent Studio，2026 年 Q2 上线 Orchestrator Agent 和 Advanced Solver Studio。[S8][S9][S10]

**对本项目**：我们已经有 `/agent/explain`、`/agent/whatif`、`/agent/events` 三个工具型端点，下一步不应做「万能聊天」，而应做「只调用这三个只读工具的计划员 Agent」，把证据、沙箱、审计作为 Agent 的边界。

### ② 把「放行前检查」做成 Agent，而不是把排产交给 LLM（SAP）

SAP 的 Production Planning and Operations Agent 做的是：读取生产订单，检查物料、产能、排程可用性，发现缺料时建议替代料或调整排程，最后由人批准放行。它没有让 LLM 生成排产结果，而是让 Agent 做「放行前的可执行性检查 + 工作流编排」。[S2][S3]

**对本项目**：MRP 三态（FULL/PARTIAL/SHORTAGE）、ATP ESTIMATE/VERIFIED、冻结任务与资源锁都已经存在，做「订单可放行性 Agent」的路径最短、价值最直接。

### ③ 把「不可行原因」产品化（Siemens）

Siemens Opcenter APS 2510 把可解释性做成了产品功能：Validation Dashboard 一键查看/搜索/分组校验问题，Constraint Usage chart 看约束使用情况，Secondary constraint event 区分主/次约束事件。[S20] IDC 也把「AI-based scheduling（GA、MCTS、autonomous scheduling）」列为 Siemens 的差异化。[S21]

**对本项目**：Timefold 的 `ConstraintMatchTotal` / 约束匹配本来就能拿到，缺的是「聚合、归因、展示」。这比让 LLM 写一段解释更可靠，也更能解决 -1hard。

### ④ 自然语言只改「策略/DSL」，不直接改求解器约束（Oracle）

Oracle 的 Planning Order Release Assistant 允许计划员用自然语言写放行规则，例如「如果放行会让任何客户需求延迟超过 7 天，就挂起审核」。它改的是 order release policy，不是直接改 solver 的硬约束。[S42][S43]

**对本项目**：C45 明确 `nl-to-dsl` 保持 501，必须先有「校验 → 只能落 DRAFT → 人工审核 → 沙箱验证 → 激活」的闭环，这个判断是对的。C47 已把 DSL 接进求解装配，地基已经具备，下一步应做闭环而不是直接放开写约束。

### ⑤ 把「变化后自动重排」做成「建议 + 对比 + 批准」（Plataine / Kinaxis）

Plataine 的 Scheduler Agent 在车间发生变化时自动重算并重排，计划员可以用自然语言问「为什么这样排」「为什么延迟」，Agent 结合工厂上下文回答；其 P-O 算法宣称可一次排 30,000–50,000 个任务。[S35][S37] Kinaxis 的 disruption management 则把插单、缺料、供应变化变成备选方案与影响对比。[S11]

**对本项目**：增量重排和沙箱 compare 已有，缺的是「事件驱动 + 变更影响摘要 + 多方案建议 + 人工批准」，不是缺一个更聪明的 LLM。

---

## 1. 调研方法与判定口径

### 1.1 证据等级

| 等级 | 含义 | 本报告用法 |
|---|---|---|
| A（强） | 官方产品文档/发布说明明确，且有发布日期；最好有客户案例或可验证的产品行为 | 可支撑「已落地」判断 |
| B（中） | 官方产品页/新闻稿明确，但缺客户案例，或只在相邻模块提供 | 只能支撑「已发布/部分」 |
| C（弱） | 营销页、第三方转述、路线图、分析师转述 | 标「待核实」或「路线图」 |
| D（无法确认） | 公开资料没有可核实证据 | 明确写「无法确认」，不替厂商补故事 |

### 1.2 能力标记

- **有**：官方资料明确，且属于该产品主线能力。
- **部分**：只在计划层/相邻模块提供；或能力存在但明显受限；或只有厂商宣称、缺产品级证据。
- **无**：公开资料未发现；不等于产品绝对没有。
- **无法确认**：信息不足，或来源不可靠。

### 1.3 一个必须先说清的概念区分

本调研发现，厂商口中的「AI」至少有三层，混在一起会得出错误结论：

1. **优化算法型 AI**：遗传算法、禁忌搜索、MCTS、局部搜索、机器学习调参。它仍然是确定性/可复现的求解内核，不是 LLM。
2. **预测型 AI**：需求预测、异常检测、根因分析。它通常不直接改排产结果。
3. **生成式/Agent 型 AI**：自然语言交互、工具调用、方案摘要、工作流编排。它主要活在排产外围，不直接保证硬约束。

**关键事实：没有一家厂商让 LLM 直接生成排产结果并替代优化引擎。** 排产结果仍由优化/启发式引擎产生；LLM 的位置是意图解析、工具调用、结果改写、异常归因、方案摘要。

---

## 2. 能力矩阵（横向对比）

> 单元格格式：**判定** + 简短依据 + 来源编号。表格较宽，建议横向滚动阅读。

| 厂商（产品） | 1. 求解引擎与规模 | 2. 约束建模 | 3. 交互甘特与人工干预 | 4. ATP/CTP | 5. What-if 与方案对比 | 6. 异常与扰动响应 | 7. ERP/MES/PLM 集成 | 8. 可解释性 | 9. AI/LLM 能力（总评） |
|---|---|---|---|---|---|---|---|---|---|
| **SAP**（IBP / PP-DS / DMC） | **有**：PP-DS 有 optimizer + 启发式，HANA 内存计算；多工厂计划在 IBP 层[S1][S4] | **有**：换型矩阵、替代资源、多资源、日历；exception-based setup matrix 可压缩矩阵规模[S5] | **有**：PP-DS planning board 拖拽；DMC Resource Orchestration 工作台为 Gantt 拖拽派工[S7] | **有**：S/4HANA aATP 支持 ATP/CTP、多级确认与替代策略[S6] | **有**：IBP 场景模拟与对比；PP-DS 可试排不保存[S1][S2] | **有**：PP-DS 重排 + DMC 告警/资源编排；但自动重排仍以规则/人工为主[S7] | **有**：S/4、DMC、PLM 同源主数据，制造执行闭环强[S3][S7] | **部分**：有 pegging/justification 与计划日志，但约束冲突归因未做成独立产品能力[S4] | **有**：Joule + Production Planning & Operations Agent；但 PP-DS 求解器本身无 LLM[S2][S3] |
| **Kinaxis**（Maestro / RapidResponse / Enterprise Scheduling） | **有**：并发内存引擎，优化 + 启发式 + AI 组合；强调实时同步与大规模网络[S11][S12][S13] | **有**：material/resource/dependency/time/sequence 约束；工具/模具级细节弱于车间级 APS[S11] | **有**：Enterprise Scheduling 有交互 Gantt，可评估/解决扰动；但整体偏计划层[S11] | **有**：Order Fulfillment 支持 ATP/CTB；Celestica 案例为全球 ATP/CTB 标准化[S41] | **有**：并发场景、方案对比、KPI scorecard；强调 limitless what-if[S12] | **有**：disruption management、备选供应计划、插单影响[S11] | **有**：ERP/PLM/MES 连接器与事件驱动；但车间级设备控制不深[S11] | **有**：explainable outputs、scorecard、Agent 明确 reasoning[S8][S12] | **有**：Maestro Agents 已商用；Agent Studio、Orchestrator、Advanced Solver Studio 2026 上线[S8][S9][S10] |
| **Blue Yonder**（Luminate Planning / APS，原 flexis） | **有**：constraint-based optimization + 数字孪生校验；APS 来自 flexis 收购[S14] | **有**：工厂、人员、物料、工具、序列规则、pearl chains；可配置约束与规则库[S14] | **有**：Gantt、手工重排、销售/生产双视图[S14] | **有**：Order Promiser，14 种承诺策略（部分/准时/单次交付/多源等），ML 预测可用性[S17] | **有**：what-if、方案对比、数字孪生验证[S14] | **有**：实时异常、规则违反可视化、瓶颈早发现[S14] | **有**：ERP/MES/WMS、网络协同[S14][S15] | **部分**：宣称 explainable AI、规则违反可视化；APS 层缺自然语言归因[S14] | **部分**：Agentic AI 集中在 WMS/库存/网络/需求；未见 APS 专用 Agent 证据[S15][S16] |
| **o9 Solutions**（Digital Brain） | **部分**：EKG + 优化/启发式，偏端到端供应链计划；车间级有限产能求解证据弱[S18][S19] | **部分**：供应/网络约束强；工装、模具、换型矩阵等车间级约束缺公开证据[S19] | **部分**：有计划工作台与场景；车间级交互甘特未找到可核实证据[S19] | **部分**：供应计划/订单承诺有覆盖，但 ATP/CTP 产品级证据不足，标「待核实」[S19] | **有**：场景模拟、KPI 对比、IBP/S&OP 一体化[S18][S19] | **有**：Control Tower、实时需求/供应 shaping[S19] | **有**：EKG、ERP/供应商协同[S19] | **部分**：EKG 可追溯、Agent 可解释；缺求解冲突归因证据[S19] | **有**：AI Agents、APEX（2026-03）、neuro-symbolic 设计；但客户级 AI 实证少[S18][S19] |
| **Siemens**（Opcenter APS / Preactor） | **有**：有限产能引擎；算法组合含 GA/MCTS/autonomous scheduling 投资；2510 支持 5 万链接[S20][S21] | **有**：工具/夹具、人员资质、日历、换型、次级约束事件[S20][S22] | **有**：交互式多约束调度，支持手工调整与多方案[S22] | **部分**：有交期控制/排程，但多级 ATP/CTP 未见产品级证据[S22] | **有**：多方案生成与评估、仿真[S22] | **有**：告警窗口、MES 集成、实时车间问题[S20] | **有**：PLM/MES/ERP 数字线程，Xcelerator 体系[S21] | **有**：Validation Dashboard、Constraint Usage chart、Secondary constraint event[S20] | **部分**：优化算法型 AI 明确；LLM 仅 GenAI help/Insights Hub Copilot，未进排产内核[S21] |
| **Dassault**（DELMIA Ortems / Quintiq） | **有**：可配置优化引擎；Quintiq 的 Quill 支持构造/局部搜索启发式[S23][S25] | **有**：机器、工具、人员、多工厂、虚拟孪生；约束建模强[S23][S25] | **有**：Gantt、手工调整、what-if[S23][S25] | **部分**：有交付日期 KPI 与订单承诺场景，但多级 ATP/CTP 证据不足[S25] | **有**：unlimited what-if、KPI 对比、方案选择[S25] | **有**：动态重排、延迟/故障/缺料响应[S23] | **有**：ERP/MES、3DEXPERIENCE 数字孪生[S23] | **部分**：有 KPI/根因与 Scheduling Intelligence；求解冲突归因未产品化[S23] | **有**：Virtual Companions（Aura/Leo/Marie）2026 全球可用；AI agents 页面提 autonomous rescheduling[S24][S26] |
| **Asprova**（APS / Solver Option） | **有**：自有 Solver，GA/迭代改进，宣称 100 万+ 候选；案例 50 台设备/2 个月 4,000 作业[S27] | **有**：模具、治具、人员、子资源、Next Resource Constraint、批量/合并分支[S28] | **有**：Gantt 拖拽、手工调整、多场景[S28] | **部分**：有交期与优先级，但多级 ATP/CTP 无证据[S28] | **有**：多场景生成；方案对比/回滚证据较弱，标「部分」[S28] | **有**：订单/设备变化后快速重排[S27] | **有**：ERP/MES 连接器，日系制造业集成经验[S28] | **部分**：Gantt/可视化强；缺约束冲突归因产品证据[S28] | **无**：Solver 是可解释性有限的元启发式，未发现 LLM/Agent 证据[S27] |
| **Oracle**（SCM Planning / Production Scheduling / GOP） | **有**：Production Scheduling 引擎，支持大量物料作业与多资源；规模/算法细节未公开[S31] | **有**：资源、替代资源、日历、物料、换型可视化；约束覆盖车间级[S31] | **有**：可视化 Gantt、拖拽、dispatch list、多计划[S31] | **有**：Global Order Promising 支持 ATP/CTP/PTP、多级 sourcing/scheduling[S32] | **有**：多计划并行、inline simulation、what-if[S31] | **有**：设备停机/缺工/缺料/优先级变化的动态修复[S31] | **有**：Fusion Manufacturing/ERP/MES 一体化[S30] | **部分**：AI Advisor/异常解释有；APS 约束冲突归因证据弱[S30] | **有**：SCM AI Agents（2025-01 起）、26A planning agents、NL 放行规则；APS 本身无 Agent 证据[S29][S42][S43] |
| **Anaplan** | **部分**：Optimizer 是线性规划（LP），不是车间排产引擎[S34] | **无**：不是车间级约束建模；偏财务/需求/供应计划 | **无**：未发现车间 Gantt/拖拽排产证据 | **无**：未发现 ATP/CTP 排产承诺证据 | **有**：场景规划、方案对比、跨职能 IBP[S33] | **部分**：计划层重算，不是车间事件驱动 | **部分**：ERP/数据集成强；无 MES 深度 | **部分**：CoModeler 记录建模步骤、可审计[S33] | **有**：Role-based Agents、CoModeler（2025-12 发布，GA 计划 2026Q1）[S33] |
| **Plataine**（Production Scheduler / Autoclave Scheduler） | **有**：Practimum-Optimum 算法，宣称一次排 30,000–50,000 任务；秒级/分钟级重算[S35][S37] | **有**：工具、固化周期、温度、真空口、daisy chain、零件尺寸、多工厂[S35][S38] | **有**：在线 Gantt；Agent 辅助而非纯手工[S35] | **部分**：以交期/吞吐优化为主，未发现多级 ATP/CTP[S35] | **有**：仿真、方案探索、计划 vs 实际[S35] | **有**：车间变化自动重排、缺工具/急单/延迟响应[S35] | **有**：ERP/IIoT 集成，航空复材/汽车零部件场景[S35][S38] | **有**：NL 问「为什么这样排/为什么延迟」，Agent 解释调度决策[S35] | **有**：AI Scheduler Agents（2025-03 发布）、NL 问答、自动重排[S35][S36] |
| **Critical Manufacturing**（MES + 原生 APS） | **部分**：MES 原生 APS 引擎；规模/算法细节未公开[S39] | **有**：日历、资源可用性、人员资质、物料依赖、多步/并行工序[S39] | **部分**：支持手工调整；Gantt 能力未见独立产品页[S39] | **部分**：有交期约束；未发现 ATP/CTP[S39] | **部分**：有 KPI 评估/分析；未发现多方案对比[S39] | **有**：adaptive scheduling 自动更新、WIP/设备状态实时[S39] | **有**：MES 原生，主数据/WIP/设备状态一体化[S39] | **部分**：有 KPI 质量评估；缺冲突归因证据[S39] | **无/无法确认**：未发现 AI 排产证据；Gartner 2026 仅泛称 trusted AI[S40] |

### 2.1 矩阵读数（不要只看「有/无」）

1. **「有 AI」不等于「有 LLM 排产」。** 上表 11 家中有 10 家在产品页/新闻稿出现 AI 字样，但真正有已发布 LLM/Agent 产品的只有 6 家左右；Siemens、Asprova 主要是优化算法型 AI。
2. **车间级 APS 与供应链计划正在分层。** SAP（IBP 计划层 + PP-DS/DMC 执行层）、Kinaxis（计划层强 + Enterprise Scheduling）、Blue Yonder（APS + 网络）、o9（计划层强、车间级弱）差异明显。不能拿 IBP 的能力给 PP-DS 打分，反之亦然。
3. **可解释性最强的是 Siemens，而不是 AI 声量最大的厂商。** Validation Dashboard / Constraint Usage / Secondary constraint event 是产品功能，不依赖 LLM。
4. **ATP/CTP 最成熟的是 Oracle、SAP、Blue Yonder、Kinaxis。** 这也是 AI 最容易产生可验证 ROI 的地方：承诺日期可被求解/规则验证，LLM 只负责理解和解释。
5. **Plataine 是「AI-native 排产」里少数有产品、有客户、有算法的。** 但它的场景偏航空复材/先进制造，不能直接平移到通用离散制造。

---

## 3. AI/LLM 能力拆解矩阵（重点）

> 这一张表把「AI/LLM」拆成 9 个可落地能力。判定含义同上；「有」表示产品已发布/明确可用，不表示已大规模落地。

| 厂商 | NL 对话/问答 | NL 改约束/提需求 | 排产结果解释/归因 | 异常根因分析 | 交期承诺助手 | 参数/权重自动调优 | 需求预测联动 | 知识沉淀/文档生成 | Agent 编排 | 实证评级 |
|---|---|---|---|---|---|---|---|---|---|---|
| **SAP** | **有**：Joule 对话、导航、执行 job[S2] | **部分**：Agent 建议替代料/调整排程，不是改 solver 约束[S2] | **部分**：Supply optimization analysis 可解释 time-series 优化运行[S3] | **部分**：Agent 检查物料/产能/排程，定位阻塞[S2] | **部分**：aATP 强，但无 NL 交期助手证据[S6] | **无**：未发现自动调权/调参证据 | **有**：IBP 内嵌 ML 预测[S1] | **部分**：Joule 生成分析/文档，但排产知识沉淀未产品化 | **有**：Joule Agents 体系[S2][S3] | **中**：官方发布明确，客户级 Agent 案例少 |
| **Kinaxis** | **有**：Maestro Agents 嵌入实时计划环境[S8] | **部分**：Agent 在约束内推理/建议，不是直接改约束[S8] | **有**：clear reasoning、scorecard、explainable outputs[S8][S12] | **有**：分析需求延迟对生产/分销的影响[S8] | **部分**：Order Fulfillment/ATP 强，NL 助手证据弱[S41] | **部分**：Advanced Solver Studio 可组合用户模型，不是自动调参[S10] | **有**：需求/供应并发计划[S11] | **有**：Agent 捕获与规模化专家知识[S8][S9] | **有**：Orchestrator Agent（2026Q2）[S10] | **强**：已商用 + 客户试点 + 量化口径（厂商自述） |
| **Blue Yonder** | **有**：Agent 接入 Teams、对话式简报[S15] | **无**：未发现 NL 改约束证据 | **部分**：规则违反可视化、explainable AI 宣称[S14] | **部分**：异常/瓶颈检测，根因证据弱[S14] | **有**：Order Promiser 的 ML 可用性/自动交付日期[S17] | **无**：未发现自动调参证据 | **有**：ML 需求预测[S14] | **部分**：Agent 做简报，知识沉淀证据弱 | **有**：BY Orchestrator + Agentic Agents[S15] | **中**：已发布，APS 专用 Agent 无证据；Electrolux 仍在探索[S16] |
| **o9** | **有**：AI Agents 对话/洞察[S18][S19] | **部分**：Agent 推荐动作，不是改 solver 约束[S19] | **有**：EKG + Agent 解释/建议[S19] | **有**：Control Tower 风险/异常洞察[S19] | **部分**：有计划承诺场景，产品级 ATP 助手证据弱 | **部分**：neuro-symbolic 优化，未见自动调参 | **有**：需求/供应计划一体化[S19] | **有**：EKG 沉淀 tribal knowledge[S19] | **有**：APEX Agentic 体系[S18] | **中**：产品声量大，客户级 AI 实证少 |
| **Siemens** | **部分**：GenAI help / Insights Hub Copilot[S21] | **无**：未发现 NL 改约束 | **有**：Validation Dashboard、Constraint Usage、Secondary constraint event[S20] | **部分**：告警与约束使用分析[S20] | **无**：未发现 ATP 助手 | **部分**：算法组合/autonomous scheduling 投资，不是自动调参[S21] | **部分**：Insights Hub 预测/异常检测[S21] | **部分**：Copilot/GenAI help[S21] | **部分**：Insights Hub agent manager/production compiler[S21] | **中**：算法型 AI 明确；LLM 未进排产内核 |
| **Dassault** | **有**：Virtual Companions（Aura/Leo/Marie）[S26] | **部分**：Agent 可辅助排程/异常，直接改约束未证实[S24] | **有**：Agent 解释、预测延迟、评估备选[S24] | **有**：预测未来延迟、找根因[S24] | **部分**：有交付日期/方案建议，ATP 助手证据弱[S25] | **部分**：Scheduling Intelligence ML，未见自动调参[S23] | **有**：预测/虚拟孪生联动[S23] | **有**：LEO 生成 MBOM/工艺/文档[S24] | **有**：Virtual Companions 体系[S24][S26] | **中**：2026 全球可用；调度场景客户实证少 |
| **Asprova** | **无**：未发现 NL/聊天 | **无**：未发现 NL 改约束 | **部分**：Gantt/结果可视化，缺归因[S28] | **部分**：可看延迟/瓶颈，根因证据弱[S28] | **部分**：交期优化，非 ATP 助手[S27] | **部分**：Solver 按 KPI 迭代改进，非自动调参[S27] | **无**：未发现预测联动 | **无**：未发现文档生成 | **无**：未发现 Agent | **中**：算法型 AI（GA/迭代），非 LLM |
| **Oracle** | **有**：SCM AI Agents/Advisor[S29][S30] | **有**：NL 写 order release 规则（策略层，非 solver 约束）[S42][S43] | **有**：AI Advisor/异常解释[S30] | **有**：异常检测与建议[S30] | **有**：GOP ATP/CTP/PTP + AI/ML 可用性[S32] | **部分**：Planning Measure Expression Assistant，非自动调参[S42] | **有**：Demand Management ML/自调优[S30] | **有**：Policy Advisor/Planning Advisor[S30] | **有**：Agent Studio/Agent Marketplace[S29][S42] | **强**：产品文档与发布明确；APS 求解器 AI 未证实 |
| **Anaplan** | **有**：Role-based Agents/CoPlanner[S33] | **部分**：CoModeler 用 NL 生成模型逻辑/计算[S33] | **有**：Agent 解释/建议/生成 narrative[S33] | **部分**：财务/供应链偏差分析[S33] | **无**：未发现 ATP 助手 | **部分**：Optimizer LP/场景优化，非自动调参[S34] | **有**：PlanIQ 统计预测[S33] | **有**：CoModeler 记录建模步骤/文档[S33] | **有**：Role-based Agents[S33] | **中**：已发布，非 APS 场景 |
| **Plataine** | **有**：NL 问生产绩效/延迟/调度决策[S35] | **部分**：Agent 推荐改进，不是改约束[S35] | **有**：解释调度决策/延迟原因[S35] | **有**：识别瓶颈、调查延迟[S35] | **部分**：交期/吞吐优化，非多级 ATP | **部分**：P-O 算法自动优化 KPI，不是参数调优 | **有**：中长期 AI 仿真/预测[S35] | **有**：Agent 沉淀工厂知识[S35] | **有**：Scheduler Agents[S35][S36] | **强**：已商用 + 客户案例；效果数字为厂商口径 |
| **Critical Manufacturing** | **无法确认**：未发现 NL/Agent 证据 | **无法确认** | **部分**：KPI 质量评估[S39] | **部分**：告警/分析，根因证据弱[S39] | **部分**：交期约束，非 ATP 助手 | **无法确认** | **部分**：MES 数据可分析[S39] | **无法确认** | **无法确认** | **无法确认**：无 AI 排产证据 |

### 3.1 九个 AI 能力的「真实成熟度」排序

按本调研的证据强度，从高到低大致是：

1. **结果解释/归因**：最容易落地，也最不依赖模型。Siemens、Kinaxis、Plataine、Oracle 都有产品化证据。
2. **异常根因分析**：计划层成熟（Kinaxis、o9、Blue Yonder），车间层较弱（依赖 MES 数据）。
3. **交期承诺助手**：ATP/CTP 引擎成熟，NL 外壳仍在早期；Blue Yonder/Oracle/SAP 的底子最好。
4. **NL 对话/问答**：2025–2026 已普遍发布，但多数是「查数 + 摘要」，不是「改计划」。
5. **知识沉淀/文档生成**：Dassault LEO、Kinaxis Agents、Anaplan CoModeler 有明确用例；排产领域仍早期。
6. **Agent 编排**：2026 年主题，但多数是「推荐 + 人工批准」，真正自治极少。
7. **需求预测联动**：技术成熟，但与排产的「闭环」普遍弱；多数只是把预测当输入。
8. **NL 改约束/提需求**：最不成熟。Oracle 的 order release policy 是最接近的落地，但仍不是 solver 约束。
9. **参数/权重自动调优**：几乎空白。厂商更多是给算法组合/参数面板，不是自动搜索最优权重。

> 这条排序对本项目很重要：**先做 1、2、3，不要先做 8、9。** 8 需要 DSL 审核闭环，9 需要基准题集和离线搜索，两者都不是「接个 LLM」就能完成。

---

## 4. AI 落地点实证：哪些是真在用，哪些是路线图/营销

> 判定标准：有发布日期 + 产品文档/发布说明 + 可验证功能 = 已发布；有客户/案例 = 已落地；只有产品页/新闻稿 = 早期/宣称；只有 roadmap = 路线图。

| # | 厂商 | 具体声称 | 可核实依据（时间/来源） | 判定 |
|---|---|---|---|---|
| 1 | SAP | Production Planning and Operations Agent 检查物料/产能/排程，建议替代料/调整排程，人工批准后放行 | SAP H2 2025 Innovation Guide 明确功能；官方称 GA 计划 2026 Q1；第三方 2026-05 文章称 Q4 2025 已发布（口径冲突）[S2][S3] | **已发布/早期商用**；GA 时间待核实；未披露命名客户 |
| 2 | SAP | Joule 支持 IBP 场景模拟与对比；未来「对话式跑全仿真」 | H2 2025 指南称 harmonized scenario simulations 由 Joule 支持；「对话跑全仿真」是第三方 roadmap 解读[S2] | **部分落地**；全仿真为路线图，标「待核实」 |
| 3 | Kinaxis | Maestro Agents 嵌入实时计划，分析约束/trade-off，给建议并解释；客户量化收益 | 2025-10-17 官方发布；Jabil 高级计划总监引语；top-10 药企「10× 生产力、40 击→4 击」、电子厂商「每月省 30 小时」[S8] | **已商用 + 客户试点**；数字为厂商/客户自述 |
| 4 | Kinaxis | Agent Studio 无代码编排；Orchestrator Agent；Advanced Solver Studio | 2026-02-05 Agent Studio 发布；2026-08-05 Q2 财报称 Orchestrator/Advanced Solver Studio 已上线[S9][S10] | **已发布**；2026 年扩展 |
| 5 | Blue Yonder | ICON 2025 发布 Agentic Agents（Shelf/Warehouse/Inventory/Network Ops） | 2025-05-05 官方博客；BY Orchestrator；Cognitive Solutions[S15] | **已发布**；集中在仓储/库存/网络，不是 APS |
| 6 | Blue Yonder | Electrolux 采用 AI agents | 官方客户故事：Electrolux「已开始探索」AI agents，用于供应商采用、减少手工、加快决策[S16] | **早期试点/探索**，不是规模化落地 |
| 7 | Blue Yonder | Order Promiser 用 ML 预测可用性、自动交付日期、14 种承诺策略 | 2026 官方 blog；订单管理产品页[S17] | **已发布**；AI/ML 在承诺层，APS 排产层未见 LLM |
| 8 | o9 | AI Agents + APEX（Agile/Adaptive/Autonomous），Human+AI 决策系统 | 2026-03-26 BusinessWire 新闻稿（站点维护中，仅检索摘要）；IDC 2025 报告称 2024–2025 推出 AI agents/PGA[S18][S19] | **已发布**；客户级 AI 实证少，营销声量大于证据 |
| 9 | Siemens | AI-based scheduling：GA、MCTS、autonomous scheduling；GenAI help | 2025-11-24 IDC MarketScape 博文（IDC 转述）；2025-11-14 APS 2510 release notes 无 LLM 功能[S20][S21] | **算法型 AI 已用**；LLM 仅辅助；autonomous 程度待核实 |
| 10 | Siemens | Insights Hub/Opcenter Copilot、production compiler/agent manager | 2025 Opcenter AI 博文：LLM copilot、知识图谱、agent manager[S21] | **已发布/早期**；与 APS 排产内核解耦 |
| 11 | Dassault | Virtual Companions（Aura/Leo/Marie）全球可用；AI agents 支持 autonomous rescheduling | 2026-10-06 3DEXPERIENCE wiki 称全球 SaaS 可用；DELMIA AI agents 页面把「autonomous rescheduling」列为用例[S24][S26] | **已发布**；调度场景客户实证少 |
| 12 | Dassault | Ortems「AI-powered finite-capacity optimization」、Scheduling Intelligence | Ortems 产品页；Quintiq Scheduler brochure（2024-09）[S23][S25] | **部分**：AI 主要指优化/ML 分析，LLM 排产未证实 |
| 13 | Oracle | SCM AI Agents（角色型）；Agent Studio；26A planning agents；NL 放行规则 | 2025-01-30 官方发布「available today」；2025-03 Agent Studio；26A Planning Advisor/Order Release Assistant；第三方示例 NL 规则[S29][S30][S42][S43] | **已商用**；产品文档明确；APS 求解器 AI 未证实 |
| 14 | Anaplan | Role-based Agents + CoModeler；NL 生成模型逻辑 | 2025-12-09 官方新闻；CoModeler 限量 2025-11、GA 计划 2026Q1[S33] | **已发布**；非 APS |
| 15 | Plataine | AI Scheduler Agents、NL 问「为什么」、自动重排；P-O 算法 30k–50k 任务 | 2025-03-04 JEC 发布 AI Agents；2024-08 arXiv P-O 论文；Takase/Holy/Multi-Ply 客户案例[S35][S36][S37][S38] | **已商用 + 客户案例**；效果数字为厂商口径 |
| 16 | Asprova | Solver 是「优化 AI」，100 万+ 候选、迭代改进；案例治具交换 3210→2138 | 2022 Solver 产品 PDF；MS/APS 产品页[S27][S28] | **算法型 AI 已用**；无 LLM/Agent 证据 |
| 17 | Critical Manufacturing | MES 原生 APS；Gartner 2026 称「trusted AI」 | APS 产品页/2022 数据表；2026-03-25 Gartner Market Guide 新闻[S39][S40] | **APS 有**；AI 排产**无法确认** |

### 4.1 三个「看起来是 AI，其实不是」的典型

1. **「AI 排产」= 元启发式/遗传算法。** Asprova Solver、Siemens GA/MCTS、Kinaxis optimization 都属此类。它们是求解算法，不是 LLM；可解释性和自然语言能力不能按 LLM 期待。
2. **「AI 计划」= 需求预测 + 异常检测。** SAP IBP、Blue Yonder、o9、Oracle 的很多 AI 功能在预测/异常层，不直接改排产。
3. **「AI Agent」= 查询助手 + 工作流。** 多数厂商的 Agent 是「查数据、出摘要、给建议、等人批」，不是「自主排产」。Kinaxis 自己也强调 human-in-the-loop 和 guardrails。[S8]

---

## 5. 对本项目的改造建议

### 5.1 基线复盘：我们其实已经站在「正确的一侧」

按任务给定的基线（不联网核实）：

**已有能力**
- 主排产：工序依赖、换型、日历、资源锁、时间窗锁、冻结任务、增量重排；
- MRP 齐套：FULL/PARTIAL/SHORTAGE 三态、替代料、预计到货；
- ATP：ESTIMATE/VERIFIED 两档、只读；
- 沙箱 What-if、方案对比；
- AI 解读 `/agent/explain`（LLM 失败降级到证据）；
- AI What-if 问答、23 场景帮助助手；
- C1–C49 契约冻结体系。

**已知缺口**
- Timefold 2.8.0 社区版无多线程求解；
- 跨资源复合移动缺邻域算子，部分题面卡 -1hard；
- 全场景在紧题面上不稳定。

**一个重要的仓库事实（截至本次调研）**：契约冻结表显示 C45 已明确 `nl-to-dsl` 保持 501，原因是「NL→DSL 尚无可验收的强制审核闭环」；C47 已把 ACTIVE `constraint_dsl` 接入 `ScheduleProblemAssembler`，DSL 已经能影响求解。[C45/C47，见 `docs/api/contract-freeze.md` §25.19/§25.22]

这意味着：**我们不缺「LLM 入口」，缺的是「让 LLM 输出可控、可审、可回滚」的闭环。** 这恰好是国际厂商 2025–2026 的共同做法。

**我们的相对优势**
- 硬约束 + 确定性求解 + 证据链 + 契约冻结，比「让 LLM 直接排产」更可信；
- 已有 explain 的「数字逐项校验、失败降级证据」机制，这是很多厂商没有的；
- 已有沙箱 compare，具备「先推演、后生效」的产品结构。

**我们的相对劣势**
- 求解稳定性/邻域算子（核心产品力）；
- 不可行归因（现在只有求解后的 justification，没有「为什么不可行」）；
- 事件驱动（没有 MES/设备/缺料事件源）；
- DSL 只有 8 条软约束，NL 可表达的语义空间有限；
- 没有基准题集，无法证明「AI/调参」真的变好。

### 5.2 优先级改造清单

> 成本为粗略人日区间（含后端、前端、测试、文档；不含长期运营）。优先级按「产品价值 / 依赖复杂度 / 风险」排序。
> 契约提醒：任何新增路径/字段/枚举都要走契约变更单；不要直接改 `docs/api/openapi/*.yaml` 或 `controller/contract/**`。

#### P0（先做，0–6 周；不做则 AI 层没有可信地基）

| # | 改造项 | 改什么（具体） | 为什么值得（对标） | AI 具体怎么用 | 前置条件 | 成本（人日） | 风险 / 验收 |
|---|---|---|---|---|---|---|---|
| P0-1 | **求解稳定性与复合邻域** | ① 新增跨资源复合 Move：同订单相邻工序成组移动、跨资源块交换、资源链替换、锁定任务「弹出-插入」；② 用 `UnionMoveSelector` 组合现有 Change/Swap 与新 Move；③ 应用层并行跑 3–5 个独立 solver（不同 seed/构造启发式/阶段配置），按 hard→soft 选最优；④ 建 20–50 题基准集（真实种子数据） | 直接解决 -1hard 与紧题面不稳定；对标 Siemens 算法组合、Asprova Solver、Kinaxis optimization+heuristics[S12][S21][S27] | **不用 LLM**。AI 只在结果层做「为什么这个方案更好」的摘要；调参见 P1-6 | Timefold 2.8 API 稳定；CPU/内存容量；基准数据脱敏；不改硬约束语义 | 20–40 | 风险：复合 Move 步长变大、单步耗时上升；并行 solver 吃 CPU；过拟合基准。验收：基准集 hard score=-1 的题面清零或显著下降；time-to-best 不退化；方差可解释 |
| P0-2 | **不可行原因归因（Why Infeasible）** | 从 `ScoreDirector`/`ConstraintMatchTotal` 取硬约束冲突，按资源、订单、工序、换型、日历、锁分组；输出「阻塞集 + 冲突对 + 最小修复建议」；新增只读端点（如 `GET /agent/infeasible/{taskId}` 或 `explain?mode=INFEASIBLE`） | 现在只有分数，计划员不知道为什么；Siemens 已把 Validation Dashboard/Constraint Usage 做成产品[S20]；这是比 LLM 更可靠的差异化 | LLM 只做「把结构化冲突改写成计划员语言」，逐项数字校验；无证据时输出「无归因记录」，绝不猜 | 求解失败时也要持久化 constraint matches（现在可能只存 solved justification）；C45 的 no-data 语义；契约变更 C50+ | 6–10 | 风险：归因错误/过度简化；输出过大。验收：每个 -1hard 题面都能列出至少 1 个可复现冲突；数字 100% 可回溯；用户可据此手工解开 |
| P0-3 | **交期承诺助手（ATP Ask）** | NL 问「这个订单 500 件、11 月 20 日、A 客户，能不能交？如果不能，最早什么时候？」→ 解析成结构化参数 → 调现有 ATP ESTIMATE/VERIFIED → 返回档位、假设、瓶颈资源、可选方案（拆单/替代资源/加班若已建模）；只读，不写订单 | ATP 是 AI 最容易验证 ROI 的入口；Blue Yonder Order Promiser、Oracle GOP、SAP aATP 都在这条线上[S6][S17][S32] | LLM 只做意图解析 + 参数回显 + 结果改写；**日期必须来自 ATP 求解**，禁止模型编造；解析失败时让用户确认参数 | C46 ATP 只读；订单/资源/日历数据完整；需要新只读端点或复用 `/agent/whatif` 的 question 字段（但要改契约）；多级 CTP 暂不做 | 6–10 | 风险：意图误解析；用户把 ESTIMATE 当承诺。验收：参数回显 100%；0 例编造日期；ESTIMATE/VERIFIED 档位清晰；P95 延迟可接受 |

#### P1（1–3 个月；有 P0 地基后再做）

| # | 改造项 | 改什么（具体） | 为什么值得（对标） | AI 具体怎么用 | 前置条件 | 成本（人日） | 风险 / 验收 |
|---|---|---|---|---|---|---|---|
| P1-4 | **异常事件 → 影响面 → 2–3 个修复方案** | 扩展事件类型（设备故障、缺料、急单、人员缺勤）；事件进来后算受影响订单/任务/资源/下游；自动生成 2–3 个沙箱方案（冻结锁定、只移可移、优先保交期/保换型）；给 KPI 对比 + 变更影响摘要；人工批准后才发布 | 对标 Kinaxis disruption management、Dassault Ortems、Siemens 告警[S11][S23][S20]；我们已有增量重排 + 沙箱 compare，差事件驱动和方案生成 | LLM 做「事件摘要 + 方案 trade-off 说明 + 建议」；方案必须由 solver 生成；人批准后走既有发布流程 | C45 `/agent/events` 事件类型目前是封闭 4 类，需契约扩展；事件源（MES/手工）；沙箱 compare；冻结/锁语义；审计 | 15–25 | 风险：事件风暴、方案爆炸、计划抖动。验收：影响面 precision/recall 对人工；方案生成 P95；churn 可解释；0 自动发布 |
| P1-5 | **NL → 受控 DSL 补丁（DRAFT→审核→沙箱→激活）** | 只支持 8 条软约束；NL 输出严格 JSON/YAML DSL 补丁；`ConstraintDslService` 校验；权重合计 100；不支持参数必须 422 点名；前端 diff 预览；只能落 DRAFT；人工审核 + 沙箱 A/B 后才 ACTIVE；一键回滚 | Oracle 的 NL 放行规则证明「NL 只改策略、不直接改 solver」可行[S42][S43]；我们 C45 已经判定无审核闭环不能开放，C47 已接线 DSL，只差闭环 | LLM 做「中文意图 → DSL 草案」；用 schema/约束解码，禁止自由文本；LLM 不决定是否生效；失败回退到模板/表单 | C45 §5 审核闭环；C47 DSL→求解；C19 的 8 条规则；契约变更 C50+；权限与审计 | 12–18 | 风险：语义误译、权重被静默改、用户绕过审核。验收：schema 通过率；语义评测集；0 自动 ACTIVE；回滚可用；权重变化可见 |
| P1-6 | **参数/权重自动调优（离线）** | 建基准题集与评测脚本；对 solver 配置（构造启发式、阶段配置、时间限制、8 条软权重）做贝叶斯优化/racing；目标 = hard 可行优先，其次 soft score + churn 惩罚；输出推荐配置，人工审批后生效 | 厂商几乎都只给参数面板，没有真正自动调优；Siemens 算法组合、Kinaxis Advanced Solver Studio 是最近的产品化尝试[S10][S21] | **不要用 LLM 调参**。LLM 只解释「这组参数为什么更好/更差」，以及把结果写成报告 | P0-1 基准集；离线计算资源；权重业务约束；审批流程 | 12–20 | 风险：过拟合、权重偏离业务、评测噪声。验收：在留出题集上 hard/soft 双改善；方差下降；推荐可解释；可回退 |
| P1-7 | **增量重排稳定性 KPI 与变更解释** | 定义 churn：移动任务数、换资源数、冻结违反数、交期变化、换型次数变化；对每次重排输出 diff 与原因；前端高亮「为什么这个任务被移动」 | 对标 Kinaxis/Blue Yonder 的 sequence stability、变更可见性[S11][S14]；我们已有增量重排，缺度量与解释 | LLM 做变更摘要（「因 R1 故障，3 个订单后移，2 个换型合并」）；数字由 diff 计算；不生成新事实 | 增量重排结果持久化；diff 结构；冻结/锁语义 | 8–12 | 风险：KPI 定义争议；解释误导。验收：churn 可复现；每次移动有约束/事件依据；计划员可接受 |
| P1-8 | **方案对比「决策摘要」** | 在现有 compare 上增加：差异摘要、谁受益/谁受损、不可比警告（已有 BASELINE_EMPTY/SPARSE 等）、推荐方案与理由；只读 | 对标 Blue Yonder/Dassault 的 KPI 对比与 what-if[S14][S25]；我们的 compare 已有，差「可读的决策摘要」 | LLM 把结构化 diff 改写成 5 条以内的决策摘要；逐项数字校验；不可比时明确说「不可比」，不硬推荐 | C45 whatif 的 comparison 结构；警告字段；前端展示 | 5–8 | 风险：过度推荐。验收：摘要数字 100% 一致；不可比场景不输出伪结论 |

#### P2（3–6 个月；按业务需要启动）

| # | 改造项 | 改什么（具体） | 为什么值得 | AI 怎么用 | 前置条件 | 成本 | 风险 |
|---|---|---|---|---|---|---|---|
| P2-9 | **知识沉淀/交接文档生成** | 从 solve_task、agent_run、审计、计划版本生成「班次交接/排产说明」，带引用；不新增事实 | Kinaxis 的 expert knowledge capture、Dassault LEO 的文档生成[S8][S24] | LLM 摘要 + 引用；数字校验；模板化 | 审计/版本数据完整；脱敏 | 5–8 | 编造原因；引用缺失 |
| P2-10 | **Agent 编排（Orchestrator）** | 白名单工具、租户策略、速率限制、审批、审计、评测集；只读优先，写操作必须人批 | Kinaxis Orchestrator、Oracle Agent Studio、o9 APEX 都是 2026 主题[S10][S29][S18] | 多 Agent 编排；工具调用；不直连数据库 | P0/P1 工具化完成；安全/权限/评测 | 20–35 | 权限越界、prompt injection、成本失控 |
| P2-11 | **需求预测联动** | 若未来有需求/订单预测数据，把预测作为求解输入或场景参数；先做离线对比 | SAP/Blue Yonder/o9 的预测联动[S1][S14][S19] | 预测模型 + 排产 KPI 对比；LLM 只解释 | 需求数据、准确率评估、业务口径 | 15–25 | 数据质量差导致负收益 |
| P2-12 | **CTP 多级承诺** | 从 ATP 扩展到产能/替代资源/多级 BOM 的 capable-to-promise；只读预估 + 人工确认 | Oracle/SAP 的 CTP 是 ATP 之上的自然扩展[S6][S32] | 求解 + 规则；LLM 只做解释与参数解析 | 产能/替代料/多级 BOM 建模；性能 | 20–35 | 承诺过度、性能下降 |

### 5.3 如果只能做三件事

1. **P0-1 求解稳定性 + 复合邻域**：这是产品地基。没有它，任何「AI 排产」都是包装。
2. **P0-2 不可行归因**：把 -1hard 从「分数」变成「原因 + 修复建议」，直接提升计划员信任。
3. **P0-3 交期承诺助手**：用现有 ATP 两档能力，做一个「不编日期」的 NL 助手，最快证明 AI 的价值。

### 5.4 建议时间线

| 阶段 | 重点 | 交付 |
|---|---|---|
| 0–1 个月 | P0-1 基准集 + 第一批复合 Move；P0-2 归因数据结构 | 可复现的 20–50 题基准；-1hard 题面的冲突清单 |
| 1–2 个月 | P0-1 并行 solver portfolio；P0-2 只读端点 + 前端面板；P0-3 ATP Ask | 稳定性报告；Why Infeasible 面板；ATP 助手 |
| 2–3 个月 | P1-4 事件→方案；P1-7 churn；P1-8 决策摘要 | 异常响应闭环（建议+批准）；变更解释 |
| 3–6 个月 | P1-5 NL→DSL 审核闭环；P1-6 离线调优；P2-12 CTP | DSL 补丁工作流；调优报告；CTP 预估 |
| 6–12 个月 | P2-10 Orchestrator；P2-9 知识沉淀；P2-11 预测联动（按需） | 工具化 Agent 平台；可审计的知识沉淀 |

---

## 6. 「看起来炫，但实际不该做」清单

| 不该做 | 为什么 | 例外 |
|---|---|---|
| **让 LLM 直接生成排产结果/替代 Timefold** | 无法保证硬约束，结果不可复现、不可审计；所有主流厂商仍用优化/启发式引擎，LLM 只在外围 | 可用于「生成候选排序思路/伪代码」，但不能作为生产结果 |
| **自然语言直接改约束并自动 ACTIVE** | 会绕过权重合计、参数校验、沙箱验证；C45 已明确该端点必须保持 501 直到审核闭环；Oracle 也只做到 policy 层 | 若先做 DRAFT→校验→沙箱→人工激活，可做（P1-5） |
| **全自动重排/自动发布** | 事件风暴下版本爆炸、责任不清；Kinaxis/SAP/Blue Yonder 都保留 human-in-the-loop；计划员的信任来自「可批可不批」 | 仅限低风险、可回滚的「建议模式」 |
| **通用聊天机器人接全部写 API** | prompt injection、跨租户、越权写、审计缺失；Agent 必须白名单工具 + 租户策略 + 只读优先 | 只读工具 + 人批准写操作，可做 |
| **3D 数字孪生/大屏作为 AI 排产卖点** | 成本高、决策价值低；Dassault/Quintiq 的 virtual twin 主要用于约束建模/仿真，不是可视化炫技 | 若客户明确要求，可做轻量 2D 甘特 |
| **用 LLM 做参数/权重调优** | LLM 不能可靠评估目标函数，也无法做统计显著性；应使用贝叶斯优化/racing（P1-6） | LLM 可解释调优结果，不负责搜索 |
| **先做需求预测联动** | 没有需求数据/准确率评估时，预测误差会污染排产；这不是当前核心缺口 | 有稳定需求数据后再做（P2-11） |
| **多智能体自治（Orchestrator 先行）** | 工具、权限、评测、审计都没就绪；厂商也刚在 2026 发布 Orchestrator，仍在早期 | P0/P1 工具化完成后再做（P2-10） |
| **逐任务 LLM 解释** | token 成本高、上下文易过期、数字易漂移；应做「结构化归因 + 摘要」 | 可按需对单个任务深入解释 |
| **AI 直接生成客户交期承诺** | 没有 solver 验证的日期就是编造，法律/信誉风险高；ATP 必须走 ESTIMATE/VERIFIED | LLM 只解析问题、改写结果（P0-3） |

---

## 7. 关键风险与验收指标

### 7.1 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 基准集过拟合 | 用少量题面调优，线上仍不稳定 | 训练/留出题集分离；按订单族/资源类型分层；记录方差 |
| LLM 数字漂移 | 解释中的任务数/分钟/扣分与证据不一致 | 逐项数字校验；不一致整段丢弃，回退证据（已有 C45 规则） |
| 意图误解析 | 「下周三」、客户优先级、数量单位歧义 | 参数回显 + 用户确认；解析失败不调用求解 |
| 计划抖动 | 频繁重排导致现场不执行 | churn KPI、冻结窗口、变更审批、稳定性权重 |
| 权限/注入 | Agent 调工具越权、跨租户、恶意指令 | 白名单工具、租户上下文、只读优先、审计、速率限制 |
| 求解性能 | 复合 Move + portfolio 吃 CPU | 限制并行数、按优先级排队、缓存、超时降级 |
| 语义误译 DSL | 中文「尽量少换型」被译成错误规则/权重 | schema 校验、8 条规则白名单、diff 预览、沙箱 A/B、人工激活 |
| 厂商宣称误导 | 把路线图当已落地 | 本报告已标证据等级；采购/对标时要求 demo + 客户 reference |

### 7.2 验收指标（建议）

| 改造项 | 指标 |
|---|---|
| P0-1 | 基准集 hard score=-1 题面数量下降 ≥50%；time-to-best 不退化；同一 seed 结果可复现；portfolio 相对单 solver 的胜率 |
| P0-2 | 每个 -1hard 题面至少输出 1 个可复现冲突；数字 100% 可回溯；计划员可据此手工解开 |
| P0-3 | 意图解析准确率 ≥90%（评测集）；0 例编造日期；ESTIMATE/VERIFIED 档位 100% 标注；P95 延迟 |
| P1-4 | 影响面 precision/recall；方案生成 P95；churn 低于阈值；0 自动发布 |
| P1-5 | DSL schema 通过率；语义评测通过率；0 自动 ACTIVE；回滚成功率 100% |
| P1-6 | 留出题集 hard/soft 双改善；方差下降；推荐配置可解释 |
| P1-7 | churn 指标可复现；每次移动有依据；计划员接受度 |
| P1-8 | 摘要数字 100% 一致；不可比场景不输出伪推荐 |

---

## 8. 待核实清单

以下结论受资料来源/时间限制，建议在产品决策前二次核实：

1. **SAP PP-DS 求解器细节**：optimizer 的具体算法/是否 CPLEX、多线程能力、与 IBP 的边界；本文依据 SAP PRESS 目录与 SAPinsider 文章，未拿到当前官方配置文档。[S4][S5]
2. **SAP Production Planning and Operations Agent 的 GA 时间**：官方 H2 2025 指南称计划 2026 Q1，第三方称 2025 Q4 已发布；需查最新 release notes。[S2]
3. **Kinaxis 当前规模/引擎细节**：4TB 内存数据来自 2017 白皮书，可能过时；Enterprise Scheduling 的 Gantt 拖拽/冻结细节需 demo 核实。[S13]
4. **o9 的车间级 APS 能力**：公开资料偏供应链计划；是否存在原生有限产能 Gantt/工装约束，需产品 demo 核实。[S19]
5. **Blue Yonder APS 专用 AI**：Agentic Agents 集中在仓储/库存/网络；APS 模块是否有 LLM/Agent，公开证据不足。[S15][S16]
6. **Siemens autonomous scheduling 的成熟度**：IDC 转述「投资/能力」，APS 2510 release notes 未列 LLM；需确认是 GA 还是 roadmap。[S20][S21]
7. **Dassault Virtual Companions 与 Ortems/Quintiq 的集成深度**：全球可用是平台层；调度场景是否已接入 Ortems/Quintiq，需客户案例。[S24][S26]
8. **Oracle Production Scheduling 的 AI**：SCM Agents 很强，但 APS 求解器本身是否有 AI/ML，公开证据弱；GOP 数据表为 2018 年，需核实当前版本。[S31][S32]
9. **Anaplan 是否适合排产**：Optimizer 是 LP，不是有限产能调度；若用于排产需大量建模，不能按 APS 对标。[S34]
10. **Critical Manufacturing 的 AI 排产**：Gartner 2026 仅泛称 trusted AI；未找到 AI scheduling 产品文档。[S40]
11. **Plataine 的客户效果数字**：95% 时间节省、25% OTD、15% 吞吐等为厂商口径；需独立验证。[S35]
12. **Asprova 是否有 LLM/Agent 路线图**：当前公开资料只有 Solver（GA/迭代），未发现 LLM。[S27]

---

## 9. 来源清单

> 访问日期均为 2026-11-05。标注「待核实」的条目表示来源较旧、第三方转述或原文不可达。

| 编号 | 来源 | 类型/时间 | 链接 |
|---|---|---|---|
| S1 | SAP Integrated Business Planning 产品页 | 厂商产品页 | https://www.sap.com/products/scm/integrated-business-planning.html |
| S2 | SAP H2 2025 Innovation Guide | 厂商发布指南（2025 H2） | https://global.sap.com/germany/events/teched/news-guide/ai.html |
| S3 | SAP Architecture Center：Live AI Use Cases（制造/供应链） | 厂商文章（2026-04-28） | https://architecture.learning.sap.com/news/2026/04/28/live-ai-use-case |
| S4 | SAP PRESS：PP-DS with SAP S/4HANA 目录（optimizer/heuristics/planning board） | 厂商生态出版物（待核实具体版本） | https://www.sap-press.com/pp-ds-with-sap-s4hana_4951/toc/3309 |
| S5 | SAPinsider：PP/DS exception-based setup matrix | 行业媒体/实践文章 | https://sapinsider.org/simplify-shop-floor-sequence-optimization-with-a-pp-ds-exception-based-setup-matrix/ |
| S6 | SAP S/4HANA Advanced ATP（aATP） | 厂商产品页 | https://www.sap.com/products/scm/advanced-atp.html |
| S7 | SAP Digital Manufacturing Cloud：Resource Orchestration 应用帮助 | 厂商文档（2024-12） | https://sarindiadigital.com/wp-content/uploads/2024/12/SAPDM2408_REO.pdf |
| S8 | Kinaxis：Maestro Agents 发布（含 Jabil/药企/电子客户引语） | 官方新闻稿（2025-10-17） | https://www.kinaxis.com/en/news/press-releases/2025/kinaxis-accelerates-agentic-era-supply-chain-orchestration-launch-maestro |
| S9 | Kinaxis：Maestro Agent Studio 发布 | 官方新闻稿（2026-02-05） | https://www.kinaxis.com/en/news/press-releases/2026/kinaxis-introduces-maestro-agent-studio-unlocking-next-level-decision |
| S10 | Kinaxis：2026 Q2 财报（Orchestrator Agent/Advanced Solver Studio） | 官方财报（2026-08-05） | https://www.kinaxis.com/en/news/press-releases/2026/kinaxis-inc-reports-second-quarter-2026-financial-results |
| S11 | Kinaxis：Enterprise Scheduling 产品页 | 厂商产品页 | https://www.kinaxis.com/en/solutions/enterprise-scheduling-arc |
| S12 | Kinaxis：Optimization 产品页 | 厂商产品页 | https://www.kinaxis.com/en/optimization |
| S13 | Kinaxis：In-memory 技术白皮书（4TB 内存） | 厂商白皮书（2017-12，待核实） | https://www.kinaxis.com/sites/default/files/2017-12/rsch-in-memory-database-technology-eac.pdf |
| S14 | Blue Yonder：Advanced Planning & Scheduling 产品页 | 厂商产品页 | https://blueyonder.com/solutions/supply-chain-planning/advanced-planning-and-scheduling |
| S15 | Blue Yonder：ICON 2025 Cognitive Solutions/Agentic Agents | 官方博客（2025-05-05） | https://blueyonder.com/blog/2025/moving-beyond-boundaries-icon-2025-kicks-off-with-groundbreaking-cognitive-solutions-launch |
| S16 | Blue Yonder：Electrolux 客户故事（AI agents「探索中」） | 官方客户故事 | https://blueyonder.com/customers/electrolux-group |
| S17 | Blue Yonder：Order Promiser 14 种策略 | 官方博客（2026） | https://blueyonder.com/blog/2026/six-signs-your-order-promising-is-quietly-broken |
| S18 | o9：APEX 发布（Agile/Adaptive/Autonomous） | 官方新闻稿（2026-03-26；BusinessWire 站点维护，仅检索摘要，待核实原文） | https://www.businesswire.com/news/home/20260326413622/en/o9-Launches-APEX-The-Agile-Adaptive-Autonomous-Planning-and-Execution-Capability-to-Accelerate-Enterprise-Value-Creation-in-the-AI-Era |
| S19 | o9：IDC Technology Spotlight（AI Agents/PGA，2024–2025） | 第三方分析师报告（厂商赞助，待核实） | https://content.o9solutions.com/rs/777-IUC-230/images/o9_IDC_Technology_Spotlight_Report.pdf |
| S20 | Siemens：What's new in Opcenter APS 2510 | 官方博客（2025-11-14） | https://blogs.sw.siemens.com/opcenter/whats-new-in-opcenter-aps-2510/ |
| S21 | Siemens：IDC MarketScape 2025 APS Leader（GA/MCTS/autonomous/GenAI help） | 官方博客（2025-11-24，IDC 转述） | https://blogs.sw.siemens.com/opcenter/siemens-recognized-as-a-leader-in-aps-idc-marketscape-spotlight-on-opcenter-advanced-planning-scheduling/ |
| S22 | Siemens：Preactor APS 产品页 | 厂商产品页 | https://www.sw.siemens.com/en-US/technology/preactor-aps/ |
| S23 | Dassault：DELMIA Ortems 产品页 | 厂商产品页 | https://www.3ds.com/products/delmia/ortems |
| S24 | Dassault：AI Agents in Manufacturing（Virtual Companions/autonomous rescheduling） | 厂商产品页 | https://www.3ds.com/products/delmia/ai-agents-manufacturing |
| S25 | Dassault：DELMIA Quintiq Scheduler 产品手册 | 厂商手册（2024-09） | https://edstechnologies.com/wp-content/uploads/2024/09/scheduler-delmia-quintiq-product-brochure.pdf |
| S26 | Dassault：3DEXPERIENCE Virtual Companions 全球可用（Aura/Leo/Marie） | 厂商社区文档（2026-10-06） | https://3dswym.3dexperience.3ds.com/wiki/3dexperience-platform-user-s-community/3dexperience-what-s-new-generative-experiences-virtual-companions_j2a9vLz7S1mcpgEPwSFmSg |
| S27 | Asprova：Solver Option 产品 PDF（100 万候选/案例） | 厂商文档（2022） | https://www.asprova.jp/companyinfo/file/Solverleaflet.pdf |
| S28 | Asprova：MS/APS 产品页（Gantt/手工调整/多场景） | 厂商产品页 | https://www.asprova.eu/en/ms-aps/ |
| S29 | Oracle：SCM AI Agents 发布（2025-01-30） | 官方新闻稿 | https://www.oracle.com/cn/news/announcement/oracle-ai-agents-help-transform-supply-chain-workflows-2025-01-30/ |
| S30 | Oracle：Supply Chain Planning 产品页（AI 亮点/AI Advisor） | 厂商产品页 | https://www.oracle.com/cn/scm/supply-chain-planning/ |
| S31 | Oracle Production Scheduling 数据表（Gantt/拖拽/what-if/动态修复） | 第三方数据表（2022-11，待核实） | https://www.appsassocdev.wpengine.com/wp-content/uploads/2022/11/Oracle-Production-Scheduling-Apps-Associates-Data-Sheet.pdf |
| S32 | Oracle Global Order Promising 数据表（ATP/CTP/PTP） | 第三方数据表（2018-07，待核实） | https://www.centroid.com/wp-content/uploads/2018/07/oracle-global-order-promising-cloud-ds.pdf |
| S33 | Anaplan：Role-based AI Agents/CoModeler（2025-12-09） | 官方新闻稿 | https://www.anaplan.com/news/anaplan-introduces-new-suite-role-based-ai-agents/ |
| S34 | Anaplan：AI Optimizer 数据表（LP/线性优化） | 厂商文档 | https://www.anaplan.com/content/dam/anaplan/assets/documents/datasheet/anaplan-ai-optimizer-datasheet.pdf |
| S35 | Plataine：Production Scheduler 产品页（AI Agents/NL/Gantt） | 厂商产品页 | https://www.plataine.com/application/production-scheduler/ |
| S36 | Plataine：JEC 2025 发布 AI Agents | 新闻稿（2025-03-04） | https://smb.americanpress.com/article/Plataine-Launches-New-AI-Agents-for-Manufacturing-at-JEC-2025 |
| S37 | Plataine：Practimum-Optimum 算法论文（30k–50k tasks） | arXiv（2024-08） | https://arxiv.org/pdf/2408.10040 |
| S38 | Plataine：Takase Kanagata 多工厂排产案例 | 官方新闻/案例 | https://www.plataine.com/news/high-precision-automotive-and-industrial-component-manufacturer-takase-kanagata-selects-plataine-to-scale-production-scheduling-across-multi-plant-operations/ |
| S39 | Critical Manufacturing：Advanced Planning & Scheduling 产品页 | 厂商产品页 | https://www.criticalmanufacturing.com/mes-for-industry-4-0/advanced-planning-and-scheduling/ |
| S40 | Critical Manufacturing：Gartner Market Guide 2026 新闻（trusted AI） | 官方新闻（2026-03-25） | https://www.criticalmanufacturing.com/de/pressemitteilungen/critical-manufacturing-im-gartner-market-guide-fuer-mes-2026-als-representative-vendor-genannt/ |
| S41 | Kinaxis：Celestica ATP/CTB 案例 | 厂商案例（2017，待核实） | https://www.kinaxis.com/sites/default/files/2017-12/improving-order-promise-efficiency-celestica-case-study-kinaxis.pdf |
| S42 | Oracle Fusion SCM 26A planning agents（Planning Advisor/Order Release Assistant） | 第三方转述（待核实） | https://nexinfo.com/resources/blog/oracle-fusion-cloud-supply-chain-planning-ai-agents-moving-from-manual-planning-review-to-intelligent-planning-execution-with-nexinfo/ |
| S43 | Oracle NL 放行规则示例（「延迟超过 7 天则挂起」） | 第三方杂志（待核实） | https://www.creativeflakes.com/insights/fusion-scm-ai-magazine |
| S44 | SAP Cloud ERP Private 2025 FPS01 innovations（生产 AI） | 厂商 PDF（2026-03-18） | https://assets.dm.ux.sap.com/sap-user-groups/pdfs/260318_sap_cloud_erp_private_2025_fps01_innovations.pdf |

---

## 10. 一页纸总结

- **国际主流 APS 的排产内核仍然是确定性优化/启发式**；LLM/Agent 主要在外围做意图解析、查询、解释、摘要、编排。
- **最值得抄的不是「让 AI 排产」，而是五件事**：Agent 建在平台工具/证据上（Kinaxis）、放行前检查 Agent（SAP）、不可行原因产品化（Siemens）、NL 只改策略/DSL（Oracle）、变化后建议+对比+批准（Plataine/Kinaxis）。
- **本项目最该补的三件事**：求解稳定性/复合邻域、不可行归因、ATP 交期助手；分别解决「排得出」「说得清」「敢承诺」。
- **最不该做的**：LLM 直接排产、NL 直接改约束并自动生效、全自动发布、通用聊天机器人接写 API、3D 大屏、LLM 调参。
- **判断标准**：任何 AI 功能上线前，先问三个问题——结果能被 solver 验证吗？数字能逐项回溯吗？人能不能一键拒绝/回滚？三个都是「是」，才值得做。
