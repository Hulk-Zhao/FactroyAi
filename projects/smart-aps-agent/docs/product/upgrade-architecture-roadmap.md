# 升级架构路线：求解可信、不可行归因、交期承诺

> 文档状态：提案（2026-11-05），待 W1 / 架构评审后进入 Wave 0。
>
> 输入依据：
> - `docs/research/aps-competitor-global.md`（国际竞品矩阵、AI 实证、P0/P1/P2）
> - `docs/research/aps-competitor-cn.md`（国内竞品矩阵、AI 落地评级、CTP / 多工厂 / 行业包）
>
> 核对方式：本文所有“现状”结论均基于对当前仓库的只读确认（文件、方法、行号），未运行求解、未执行 `package` / `repackage` / `spring-boot:run`、未连接 18085 与 `aps_demo` / `aps_local`。
>
> 硬约束：本轮只新增本文件；不修改其它文件；不 commit / push；不碰 18085（PID 34232）与 `aps_demo` / `aps_local`。

---

## 0. 一页结论

### 0.1 三条主线的车道切分（先看这张表）

| 波次 | 车道 | 负责范围 | 独占文件（核心） | 共享只读 | 串行前置 | 出口 |
|---|---|---|---|---|---|---|
| W0 | **W1 契约基座** | C50–C55 发号、形状冻结、OpenAPI 桩、`solver-api` 新 DTO | `docs/api/contract-freeze.md`、`docs/api/openapi/*.yaml`、`controller/contract/**` | 全部业务代码 | 无 | 契约评审通过，后端/前端均可按桩开发 |
| W0 | **A0 求解内核解耦** | 拆进程级静态锁与静态权重，增加求解槽位与后置钩子 | `TimefoldSolverAdapter`、`SoftConstraintWeights`、`SolverConfiguration` | `ApsConstraintProvider`、`TimefoldModelBuilder` | 无（可与 W1 并行，但不改 `solver-api` 新 DTO） | 两个 solver 可同时跑且权重不串；单题分数不变 |
| W0 | **A3 基准骨架** | 题面固化、跑分器、基线报告 | `solver-timefold/src/test/**/bench/**`、`docs/perf/bench-*` | 无 | 无（只依赖现有 `ScheduleProblem` JSON） | 20–50 题可重复跑，输出基线 |
| W1 | **A1 Portfolio 运行时** | 多候选并行、选优、超时取消、失败隔离、候选指标 | `TimefoldSolverAdapter`、`SolveTaskLifecycleService`、`ScheduleService`、新增 `portfolio/**` | `ScheduleProblemAssembler`、`ScheduleResultWriter` | **A0** | 3 候选在 20–800 任务内不劣于单解，候选可观测 |
| W1 | **B1 归因内核** | 不可行证据采集、阻塞集、冲突对、修复候选生成 | 新增 `InfeasibilityAnalyzer`、`RepairAdvisor`、`EvidenceCollector` | `ApsConstraintProvider`、`ConstraintJustifications`、`TimefoldModelBuilder` | **A0 的后置钩子** | 失败/超时也有结构化证据，不误报“不可行” |
| W1 | **C1 CTP 物料与验证** | 多级 BOM 物料可齐日、FAST 选候选、ACCURATE 验证承诺 | 新增 `ctp/**`、`AtpService` 的 CTP 扩展 | `MrpAnalysisService`（BOM 展开逻辑需预约抽取）、`ScheduleProblemAssembler` | **W1 的 C53 + A0 的并发槽** | 只在求解验证通过后给出日期 |
| W1 | **FE-A / FE-B / FE-C** | 三个独立页面/组件，先用契约桩 + mock | 各自新增组件与 API 文件 | `mocks/handlers.ts`、`SolveTaskView.vue`（单写者） | W1 的 OpenAPI 桩 | 契约未落地前不阻塞 |
| W2 | **B2 证据 API 与前端** | 证据持久化、失败归因接口、反事实接口、归因面板 | 新增 `SolveEvidenceService`、`InfeasibilityPanel.vue`；C51/C52 的 controller 由 W1 写 | `ConstraintJustificationRepository`、`SolveTaskView.vue`、`SolveContractController` | **B1 + C51/C52** | 失败任务可解释，反事实只显示已验证项 |
| W2 | **C2 CTP API 与前端** | CTP 接口、承诺面板、审计事件 | `CtpPromisePanel.vue`、`api/ctp.ts`；C53 的 controller 由 W1 写 | `AtpEstimate.vue`（单写者 FE-C）、`OrdersContractController`、`audit_log` | **C1 + C53** | 承诺/不可承诺/超视野三态闭环 |
| W2 | **A2 进程隔离（条件）** | 独立 solver worker、租约、跨进程取消/进度 | 新增 worker 模块 / 配置（W9 改 pom） | `ScheduleProblemAssembler` | A1 跑分证明单 JVM 不够 | 多任务并发不再排队到分钟级 |
| W3 | **W9 集成与运行时** | 构建令牌、制品清单、共享实例登记、部署 | `pom.xml`、`deploy/**`、`scripts/**`、`docs/deploy/**` | 全部 | 各车道合并完成 | 制品与源码绑定，18085 可回退 |

### 0.2 五个硬结论

1. **同 JVM 多 solver 现在不可行，不是“加个线程池”就行。**
   `TimefoldSolverAdapter` 有进程级 `SOLVER_LOCK`（`aps-solver/solver-timefold/.../TimefoldSolverAdapter.java#L108`），拿不到锁等 5 秒后直接 FAILED（L113、L162–L170）；`SoftConstraintWeights` 是静态 `ACTIVE`（`constraint/SoftConstraintWeights.java#L23`），`finally` 会全局 `disable`（L83）。并行候选会在 5 秒内互相踢掉，或者用错权重。
   **必须先做 A0 解耦**（线程本地权重 + 实例级求解槽位），否则 A1 不能开工。

2. **真正的瓶颈不是“没有并行”，而是构造启发式与内存。**
   `docs/perf/m2-qa-01-perf.md#L102-L107` 的实测：50 单 / 300 任务在默认 60s 下是 `-34hard`，要 300s 才 0hard；500 单 / 3000 任务仅 CH 就 167.8s，60s 仍是未完成。单会话堆峰值 300 任务约 833MB、3000 任务约 1458MB。
   **Portfolio 只对 20–800 任务、硬可行性难的中小题有效**；对 3000 任务，3 个候选会同时复制 CH 与堆，反而更慢、更容易 OOM。大题应先做约束/CH 优化或评估企业版，而不是盲目并行。

3. **`ConstraintMatchTotal` 不是现成可用的主路径。**
   `docs/api/contract-freeze.md#L1363` 已明确社区版无 `ConstraintMatchTotal` / `SolutionManager.analyze`；当前归因是 `ConstraintJustifications` 谓词重放。虽然 2.8.0 core jar 里能看到 `ConstraintMatchTotal` / `ConstraintMatchPolicy` 内部类，但它是 `impl` 包、且 justification/indictment 仍受商业特性约束。
   **B 的主路径必须是“题面静态分析 + 谓词重放”**；`ConstraintMatchTotal` 只做一次限时 spike，失败就放弃，不把产品建在内部 API 上。

4. **CTP 不能复用“全量 ATP + 10 秒 ACCURATE”。**
   `AtpService` 的 ACCURATE 在 Web 请求线程里同步调用 solver（`aps-service/.../atp/AtpService.java#L206-L227`），默认 10s，且和求解任务抢同一把锁。`TimefoldModelBuilder` 的起始时间网格硬编码 168h（`TimefoldModelBuilder.java#L56`、L520），超过 7 天的交期无法表达。
   **CTP 必须“物料门 → FAST 选候选 → ACCURATE 验证 → 只承诺验证过的日期”**；日期来自求解结果，FAST 只用来选一个要验证的候选。

5. **开发侧的头号风险不是写得慢，而是共享 `target`、共享实例、共享契约。**
   已有真实事故：制品被其他车道重编译覆盖且不可恢复（`docs/testing/m2-build-incidents.md#L97-L107`）；`package` 覆盖运行中的 jar、thin jar 起不来（L48、L75、L79）；两个车道对同一文件并发编辑。**必须先把发号、单写者、构建令牌、资源登记做起来，再谈并行。**

### 0.3 如果只能并行两条车道

**先开 A（求解可信）+ B（不可行归因）**，但有一个硬前置：Wave 0 必须先由 A0 在 `TimefoldSolverAdapter` 增加一个“求解后置钩子”，并在 `SolveTaskLifecycleService.finishOutcome` 增加一个**通用**钩子调用；B 只实现 `SolvePostProcessor` 与新增证据类，不编辑 lifecycle；C51/C52 的 controller 由 W1 写。此后 A 只改 `adapter` / `portfolio` / `lifecycle` 的 portfolio 部分，B 只改新增证据类，两边不再共享文件。

- 选 A+B 的理由：A 解决“排得好、排得可信”，B 解决“排不出来时说得清”；B 的证据又能反哺 C 的“不可承诺原因”。两者共用同一个后置钩子，Wave 0 一次接缝即可长期并行。
- 备选 A+C：如果 W1 在 Wave 0 无法冻结 C51/C52，或 A0 钩子来不及，则先开 A+C；C 的物料/ATP 侧与 A 的 solver 内核天然隔离，能更早交付客户可见的“敢承诺”。B 顺延到 A 之后。

---

## 1. 现状核对（只读，带位置）

### 1.1 求解执行链

| 事实 | 位置 | 对路线的影响 |
|---|---|---|
| 适配器是单例 Bean | `aps-admin/.../config/SolverConfiguration.java` 的 `@Bean TimefoldSolverAdapter` | 所有调用方（solve task / ATP / 增量）共享同一个实例；槽位必须放在实例/配置层，不能放在调用方 |
| 进程级 `SOLVER_LOCK` | `TimefoldSolverAdapter.java#L108` | 同 JVM 同时只能有一个 solver；A0 必须拆 |
| 等锁 5s 后失败 | `TimefoldSolverAdapter.java#L113`、L162–L170 | 并行候选会被误杀；ATP 高并发会 50010 |
| 静态权重 | `SoftConstraintWeights.java#L23` | 候选之间权重串台；A0 改线程本地 |
| 全局 `finally` 关权重 | `TimefoldSolverAdapter.java#L225-L226` | 并发时一个候选结束会把另一个候选的权重清掉 |
| 社区版无多线程移动 | `TimefoldSolverAdapter.java#L135-L139` 探测 `MULTI_THREADED_SOLVING_AVAILABLE`；`resolveMoveThreads` L450–L469 恒返回 `NONE` | 单次求解内不能靠 `moveThreadCount` 加速；应用层并行是唯一合法路径 |
| 已有 `subchain` / `ruin` 移动选择器 | `TimefoldSolverAdapter.java#L410-L436` 读取 `parameters.moveSelectors` | A 可以先用现成选择器做候选差异化，不必改约束提供器 |
| 已完成资源分组 | `ApsConstraintProvider.java#L84-L99`，`ConstraintPredicates.overlapPairCount` | 小组内仍是两两比较，但已不是全局 n²；perf 报告是修复前数据，必须重跑基线再判断并行收益 |
| 起始时间网格 168h | `TimefoldModelBuilder.java#L56`、L520 | CTP 超过 7 天无法表达；V1 必须明确 168h 上限，超视野另立契约 |
| `scoreHistory` 未填 | `SolveMetrics.java` 只有字段；`TimefoldSolverAdapter.java#L192-L197` 只打日志 | time-to-best / best score 时间轴目前拿不到；A 必须加监听 |

### 1.2 异步、重试、线程池与连接池

| 事实 | 位置 | 对路线的影响 |
|---|---|---|
| 求解 worker 单线程 | `SolveTaskLifecycleService.java#L197-L204` | 第二个任务排队；重试会占住唯一 worker |
| 队列 32 | `aps-admin/src/main/resources/application.yml#L61` | 超 32 快速拒绝；portfolio 不应放大单任务队列，而应放大单任务内候选 |
| 重试在工作线程里 sleep | `SolveTaskLifecycleService.java#L541-L551` | 一次 30s 重试会让所有排队任务停 30s；A 必须改成定时器 |
| 锁竞争被归为 ENGINE_ERROR | `TimefoldSolverAdapter.java` 的 `failedSolution` 未设置 `engineFallbackReason`；`ScheduleService` 的 `fallbackOrFailed` 默认 `ENGINE_ERROR` | 会触发 30s 重试；A0 要新增 `SOLVER_BUSY` 不可重试原因 |
| 求解期间不占 DB 连接 | `ScheduleService` 无 `@Transactional` 包住 `execute`；`ScheduleResultWriter` 只在 L68 起短事务 | portfolio 复用“一次装配、一次写赢家”即可，不会成倍占用 Hikari |
| Hikari 10 | `application-dev.yml#L14-L18` | CTP 突发读会打满；靠单飞 + 缓存，不要盲目加连接 |
| ATP ACCURATE 同步在 Web 线程 | `AtpService.java#L206-L227` | 高并发 ATP 会占满 Tomcat 线程并抢求解槽；CTP 不能照抄 |
| `async-enabled` 文档与代码不一致 | `application.yml#L55` 注释“不再提供 async-enabled 部署开关”；`docs/dev/architecture.md#L206` 仍写“代码默认 true、yml 未配” | 以代码/yml 为准：异步提交始终生效；A 的负载模型按“始终异步”设计 |
| 回调/幂等/限流在内存 | `SolveTaskLifecycleService` 的 `running` / `idempotency` / facade 限流 | A2 多进程会失效；必须迁 DB/Redis 才能跨进程 |
| 取消是“丢弃结果” | `scheduleTask.cancel` + watchdog；Timefold 同步 `solve` 不响应中断 | 不要承诺“秒级停止”；正在跑的候选最多占用自身 timeout |
| 状态机只有单任务一行 | `solve_task` + `metrics` JSONB | portfolio 候选不建新表，放 `metrics.portfolio`；避免 DDL 串行点 |

### 1.3 构建、契约、运行时

| 事实 | 位置 | 对路线的影响 |
|---|---|---|
| `package` 在共享 `target` 覆盖运行中 jar | `docs/testing/m2-build-incidents.md#L97-L107`、L75-L79 | 必须 worktree / 构建令牌 / 制品清单 |
| 契约唯一写者 W1 | `docs/api/contract-freeze.md#L1961-L1963` | C50+ 只能 W1 发号与落档；其它车道提交申请 |
| 编号“落档前确认文件尾部最大号” | 同上 | 不许猜号；C49 是当前尾号，C50 起分配 |
| 社区版无 `ConstraintMatchTotal` | `docs/api/contract-freeze.md#L1363` | B 主路径为谓词重放，spike 才碰内部类 |
| OpenAPI 先定 YAML | `docs/api/openapi/README.md#L36-L39` | 契约 → YAML → controller → 前端；不允许反向 |
| 前端 mock 开关 | `aps-frontend/src/mocks/index.ts#L6-L15` 的 `VITE_ENABLE_MOCK` | 契约桩 + mock 可让前端先跑 |
| 部署是运行时镜像挂载 fat jar | `docs/deploy/docker-compose.md#L8-L11` | 多进程 worker 可复用同一 jar；制品 SHA 必须随挂载记录 |
| Docker 默认 `MaxRAMPercentage=75` | `deploy/backend/Dockerfile#L18` | 容器内 `availableProcessors` 可能等于宿主机；候选数必须显式封顶 |
| 制品清单已有 SHA256 | `scripts/stack-bundle.ps1#L71-L133` | 沿用并补 `source_sha` / `dirty` / `migration_range` |

### 1.4 与调研报告建议的冲突（必须明确）

| 调研建议 | 现状 | 冲突 / 替代方案 |
|---|---|---|
| 应用层并行跑 3–5 个独立 solver | 进程锁 + 静态权重 | 不能直接做；先 A0 解耦，再按 CPU/堆封顶（默认 3，最大 5） |
| 从 `ConstraintMatchTotal` 取硬冲突 | 社区版无该能力，现为谓词重放 | 主路径改“题面静态分析 + 重放”；`ConstraintMatchTotal` 只做 spike，不承诺 |
| `moveThreadCount` 接通多线程 | 社区版恒 `NONE` | 不要把它列为 A 的交付；只作为企业版评估项 |
| CTP 复用 ATP ACCURATE | 10s、同锁、168h、无物料 | CTP 独立“验证再承诺”链路；物料先行；超 7 天另立模型视野 |
| 3000 任务也并行 | CH 167.8s、1.46GB | 明确 gating：>800 任务单候选；先优化 CH/约束或评估企业版 |
| 用 score curve 算 time-to-best | `scoreHistory` 从未填充 | A 先加 `BestSolutionChangedEvent` 监听，再谈指标 |
| 修复 resourceNoOverlap 的 n² | 源码已按资源分组（L84-L99），但组内仍两两 | perf 数据是修复前；先重跑基线，别把“并行”当性能修复 |
| 新增归因表 / CTP 表 | `constraint_justification.implicated_facts JSONB`、`solve_task.metrics JSONB`、`audit_log` 已够 | V1 零 DDL；避免迁移串行点；确有审计强需求再 V15 |

---

## 2. 第一半：系统并发能力改造路线

### 2.1 目标与非目标

**目标**

- 一个求解任务内可以并行跑 N 个候选（不同 seed / 移动选择器 / 预热策略），选出一个可信结果。
- 候选之间不串权重、不串分数、不串租户；一个候选失败不影响其它候选。
- 只有赢家写库；失败/超时也能留下证据（交给 B）。
- 超时、取消、排队、拒绝都有明确语义，前端不产生“假运行/假成功”。

**非目标（V1 明确不做）**

- 不做跨租户公平调度、优先级抢占（A2 之后再说）。
- 不做 CTP 的 portfolio（CTP 只做单次验证，避免延迟翻倍）。
- 不做“单次求解内多线程移动”（社区版做不到，见 2.4）。
- 不做求解过程中的实时快照迁移（A2 多进程再设计）。

### 2.2 三种并行形态比较

| 形态 | 启动成本 | 隔离性 | 题面/结果传输 | 取消/进度 | 运维复杂度 | 适用 |
|---|---|---|---|---|---|---|
| **同 JVM 多线程（推荐 A1）** | 最低（无 JVM 冷启动） | 差（OOM/GC 互相影响） | 共享内存中的 `ScheduleProblem`，每候选各自 build plan | 进程内 Future/flag；仍不能中断 Timefold 同步 solve | 低 | 单任务内 portfolio；20–800 任务；单机多核 |
| **多进程（条件 A2）** | 中（JVM 启动 + 连接池） | 好（进程级） | 要么 worker 自己读库装配，要么 JSON 传题面（大） | 需要 DB 租约 + 取消标志 + 主进程转发 STOMP | 高 | 多任务并发 SLA、故障隔离、横向扩容 |
| **每解一容器** | 高（镜像 + JVM 冷启动约数秒） | 最好 | 需要对象存储/消息 | 复杂 | 很高 | 离线批量、租户级隔离；**不适合 10s ATP/CTP** |

**结论：A1 先做同 JVM 多线程 portfolio；A2 只在跑分证明单 JVM 无法满足多任务 SLA 时启动；容器化单解不进入本轮。**

### 2.3 推荐架构（A0 → A1）

#### 2.3.1 数据流

```
SolveTaskLifecycleService (单 worker，负责任务状态机)
  └─ ScheduleService.execute
      ├─ ScheduleProblemAssembler.assemble(...)          一次装配，得到不可变 ScheduleProblem
      ├─ SolvePortfolioCoordinator
      │   ├─ 生成 CandidateSpec[N]（profile/seed/config）
      │   ├─ SolverCandidateExecutor（固定 N 线程）
      │   │   └─ TimefoldSolverAdapter.solve(candidate)
      │   │       ├─ acquire 求解槽（Semaphore，N）
      │   │       ├─ SoftConstraintWeights.activate（ThreadLocal）
      │   │       ├─ TimefoldModelBuilder.build（每候选独立 plan）
      │   │       ├─ SolverFactory.create + solve
      │   │       ├─ ConstraintJustifications.from(best)
      │   │       └─ finally: ThreadLocal.remove + release
      │   └─ CandidateSelector（完整性 + hard + soft + 时延 + seed 确定序）
      ├─ ScheduleResultWriter.persist(winner)            只写赢家
      ├─ SolveEvidenceService（仅 B：失败/超时证据，独立事务）
      └─ SolveTaskRepository.finish(metrics.portfolio)
```

关键点：**装配一次、候选各自 build、只写赢家、证据单独写**。这样 DB 读不翻倍、写不并发、`solve_task` 仍是一行。

#### 2.3.2 A0：去静态化（必须先合入）

1. **`SoftConstraintWeights` 从静态引用改为线程本地**
   - 保留现有 `activate` / `disable` / `isEnabled` / `active` 方法签名，调用点不变。
   - 内部 `AtomicReference<Map<...>> ACTIVE` 改为 `ThreadLocal<Map<...>>`。
   - `activate` 写入当前线程；`disable` 用 `remove()`（不是设置空 map），避免线程池复用泄漏。
   - `ApsConstraintProvider.defineConstraints` 在 `SolverFactory.buildSolver()` 的同一线程读取；候选线程先 `activate` 再 `buildSolver`。
   - `ConstraintJustifications.from(best)` 在 solve 返回后、`finally` 之前读取；同一线程，语义不变。
   - 反例：禁止使用 `InheritableThreadLocal` 自动继承，避免线程池里父线程权重泄漏到子任务；必须显式 set/clear。

2. **`SOLVER_LOCK` 改为实例级公平信号量**
   - `private final Semaphore solverSlots = new Semaphore(permits, true)`；`tryAcquire(waitSeconds, TimeUnit.SECONDS)`。
   - `permits` 由 `SolverConfiguration` 注入，默认 `max(1, min(availableProcessors - 2, 4))`；配置键见 2.3.3。
   - 拿不到槽：快速失败，设置 `engineFallbackReason = SOLVER_BUSY`，**不重试**（替代当前被误判为 `ENGINE_ERROR` 后 sleep 30s）。
   - 释放只在真正 acquire 成功时执行；异常路径不得多释放。
   - `MULTI_THREADED_SOLVING_AVAILABLE` 仍是静态只读能力探测，保留。

3. **增加后置钩子（B 的接缝，A0 完成）**
   - `SolverAdapter` 接口加一个**可选**默认方法 `void afterSolve(SolveOutcome outcome)`，或由 `TimefoldSolverAdapter` 持有 `ObjectProvider<SolvePostProcessor>`；实现必须容错，不能让归因失败拖垮求解。
   - `SolveTaskLifecycleService.finishOutcome` 增加一个单点调用：`postProcessor.onFinish(taskId, outcome)`；B2 只实现 `SolvePostProcessor`，不改 lifecycle 的其它逻辑。
   - 钩子必须在 A1 合并前就存在，否则 A/B 会争同一文件。

4. **测试改造**
   - 现有 `SoftWeightsConcurrencyTest` 断言的是“第二个线程被锁阻塞/失败”；A0 必须把它改为“两个线程不同权重、分数互不影响”。
   - 新增 `PortfolioScoreConsistencyTest`：同一题面、同一 seed、同一配置，单解与 portfolio 赢家的 hard/soft 一致；`JustificationScoreConsistencyTest` 必须继续通过。
   - 新增 `TenantIsolationTest`：两个候选不同 tenant，日志/指标/证据不串。

#### 2.3.3 候选 profile 与配置

| Profile | 移动选择器 | seed | 适用 | 预期作用 |
|---|---|---|---|---|
| `baseline` | 现有默认 | 请求 seed | 所有题 | 保底，不劣于当前 |
| `subchain` | `subchain` | seed+1 | 资源可换、跨资源移动重要 | 跨资源复合移动（现有 `SubListChange/Swap` 已在列表变量上支持） |
| `ruin` | `subchain,ruin` | seed+2 | 卡在局部最优、硬冲突多 | 跳出局部最优 |
| `warm` | 现有默认 + 初值 | seed+3 | 有 `initialAssignments` 的增量求解 | 少走 CH，缩短 time-to-best |
| `late`（可选） | 默认 + late acceptance | seed+4 | 软目标重、需要长尾 | 只在跑分证明有效后开 |

配置（新键统一挂在 `aps.solve.portfolio`，避免和 C 的 `aps.ctp` 混）：

```yaml
aps:
  solve:
    max-concurrent-solvers: 0        # 0=自动：max(1, min(cores-2, 4))
    portfolio:
      enabled: false                 # 跑分通过后再开，默认不改变现网行为
      candidates: 3                  # 默认 3；上限 5
      max-candidates: 5
      min-tasks-for-portfolio: 20
      max-tasks-for-portfolio: 800   # 超过走单候选，避免 CH/内存翻倍
      selection: LEXICOGRAPHIC
      candidate-timeout-ratio: 1.0   # 候选超时 = 请求超时 × 该比例
      warm-start-candidate: true     # 仅当有初值时生效
```

**默认关闭**是刻意的：A1 合并时现网行为不变；跑分与灰度通过后再按任务规模打开。这样不会出现“为了并行而并行”的性能回退。

#### 2.3.4 结果怎么选优

候选必须按以下顺序通过：

1. **完整性**：`status == SOLVED`；`requiredTaskCount == assignedTaskCount`（复用 `ScheduleService` 现有的完整性守卫，见 L469–L476）；`hardScore == 0`。
2. **硬分**：`hardScore` 降序（0 最高）。
3. **软分**：`softScore` 降序（`HardSoftScore.compareTo`）。
4. **时延**：`durationMs` 升序。
5. **确定性**：`seed` 升序，再按 profile 固定顺序（`baseline` < `subchain` < `ruin` < `warm`）。

规则：

- 只有通过完整性 + hard 0 的候选才能被持久化；失败候选的 partial solution **只进诊断**，不进 `task` 表。
- 全部候选都失败：`solve_task` 为 FAILED/TIMEOUT；保留“最佳 partial”的 hard/soft 与证据给 B；不得把 partial 当计划发布。
- 平局必须确定：同一题面、同一组 seed，重复跑赢家必须相同；否则说明有非确定性来源（墙钟终止/线程竞争），要在跑分中暴露。

#### 2.3.5 超时与取消

- **全局预算**：从 `ScheduleService.execute` 入口记 `deadline = now + timeoutSeconds`；装配、求解、写库都算在预算内，但候选不叠加预算。
- **候选预算**：每个候选 `min(timeoutSeconds × candidate-timeout-ratio, globalRemaining)`；并行启动，墙钟约等于最慢候选，而非 N 倍。
- **不再启动**：过了 deadline 不再提交新候选；已有候选最多跑到自己的 termination。
- **取消语义**：`PortfolioRun.cancelled` 原子标志；取消后不再启动新候选，已跑候选继续到自身 termination，结果丢弃；`solve_task` 置 CANCELLED。
- **不能承诺秒级中断**：Timefold 社区版同步 `solve` 不响应线程中断；`Future.cancel(true)` 只能打断等待，不能打断 CPU 循环。前端提示必须是“已取消，正在收尾”，不是“已停止”。
- **watchdog 兼容**：现有 `timeout + 5s` 的 watchdog 保留；portfolio 总墙钟不得超过 `timeout + candidateTimeout`，否则 watchdog 会先杀任务，指标会丢。A1 要把总预算收敛到 `timeout × 1.0`（候选并行，不需要 1.5 倍）。

#### 2.3.6 失败怎么隔离

| 失败类型 | 处理 | 是否重试 |
|---|---|---|
| `SOLVER_BUSY`（槽位不足） | 快速失败，返回 503/`50300`；portfolio 不启动 | 否；等调度层重试 |
| 单候选抛 `IllegalArgumentException` / 配置错误 | 标记该候选 FAILED；其它候选继续；若 ≥2 个同因失败，熔断剩余候选 | 否 |
| 单候选 `Exception`（数据/模型） | 标记 FAILED；记录 stack 摘要；其它候选继续 | 视 `SolveRetryReasons` 白名单 |
| `OutOfMemoryError` | 不吞；标记任务 FAILED，记录 `portfolio.oom`；JVM 交给上层重启策略 | 否 |
| 所有候选 TIMEOUT | 任务 TIMEOUT；选最佳 partial 给 B 做证据 | 否（预算已尽） |
| 装配失败 / 空题面 | 维持现有 FAILED 语义，不启动候选 | 按现有 |

隔离原则：**候选异常不能污染 `SoftConstraintWeights` 线程本地**（finally 必须 remove）；候选线程必须自带 `TenantContext`（ThreadLocal 不会从 worker 继承），finally 清理；候选线程不允许写库。

#### 2.3.7 CPU / 内存怎么算

单候选 = 1 个核心（社区版单线程移动）。因此：

```
N_cpu  = max(1, availableProcessors - 2)          # 留 2 核给 Web/DB/GC
N_mem  = max(1, floor((maxHeap - 800MB) / perCandidateHeap))
N      = min(config.candidates, 5, N_cpu, N_mem)
```

`perCandidateHeap ≈ max(300MB, 1.2 × 单会话峰值)`。用现有实测做保守估算：

| 题面规模 | 单会话峰值（perf 文档） | 单候选估算（含 1.2 安全系数） | 8GB 堆可放 | 12GB 堆可放 |
|---|---|---|---|---|
| 50 单 / 300 任务 | 约 833MB | 约 1.0GB | 约 3 个 | 约 4 个（封顶 5） |
| 500 单 / 3000 任务 | 约 1458MB | 约 1.75GB | 约 2 个 | 约 4 个，但**不建议** |

注意：

- 容器里 `availableProcessors()` 可能等于宿主机核数；必须用配置封顶，不能只信自动值。
- Docker 默认 `MaxRAMPercentage=75`；如果容器 16GB，堆约 12GB，看起来能放 4 个 300 任务候选，但还要留 MetaSpace/直接内存/线程栈；建议实际 `N ≤ 3`。
- 3000 任务默认 `N=1`；除非跑分证明 2 候选在 8GB 堆下稳定且时延可接受。
- 启动前做**准入检查**：`taskCount > maxTasksForPortfolio` 或 `N_mem < 2` 时，降级为单候选并在 `metrics.portfolio.degraded=true` 记录原因。
- 不要用 `-Xmx` 拍脑袋；先在目标容器跑 `N=1/2/3` 的堆与 GC 观测，再定默认。

#### 2.3.8 A 的分阶段与出口条件

| 阶段 | 改动 | 必须通过的验证 | 出口 |
|---|---|---|---|
| **A0 解耦** | 线程本地权重、实例信号量、后置钩子、`SOLVER_BUSY` | 两线程不同权重不串；单题分数与改造前一致；`SoftWeightsConcurrencyTest` 重写；锁竞争不再触发 30s 重试 | 并行地基可用，B 可接钩子 |
| **A1 Portfolio** | coordinator、候选池、选优、指标、准入、降级 | 20–50 题基准；3 候选在 300 任务上墙钟不劣于单解 +15%；无 OOM；赢家确定性；失败隔离 | 默认关闭但可灰度；指标齐全 |
| **A2 进程隔离（条件）** | worker 模块、DB 租约、跨进程进度/取消 | 2 个任务并发时第二任务不再等 300s；worker 崩溃主进程可感知；取消最终生效 | 只在 A1 跑分不达标时启动 |

### 2.4 Timefold 2.8 社区版硬约束怎么绕

**现状（只读确认）**

- `TimefoldSolverAdapter.java#L135-L139`：反射探测 `ai.timefold.solver.enterprise.api.solver.SolverManager` / enterprise 服务类；不存在则 `MULTI_THREADED_SOLVING_AVAILABLE=false`。
- `resolveMoveThreads`（L450–L469）：任何非 `NONE` 的 `moveThreadCount` 都会抛 `UnsupportedOperationException`，因为社区版没有多线程移动实现。
- `docs/api/contract-freeze.md#L1363`：社区版无 `ConstraintMatchTotal` / `SolutionManager.analyze`。

**绕法（应用层并行）**

- 合法、可行，但**必须先 A0 去静态化**；否则不是“并行”，是“互相踢”。
- 代价：代码风险（静态态改线程态）、内存（N 份 plan）、CPU（N 核）、调度复杂度（槽位/准入/取消）。
- 上限：`N ≤ min(配置, 核数-2, 堆预算)`，通常 3，最大 5；不是无限横向扩展。
- 它不能加速**单次**求解内部的搜索；只能通过“不同起点/不同选择器”提高找到 0hard 的概率和缩短 time-to-best。对 3000 任务的 CH 瓶颈无效。

**什么时候值得买企业版**

| 触发条件 | 现在是否成立 | 判断 |
|---|---|---|
| 单题 P95 超过 SLA，且应用层 portfolio 已把核吃满 | 待 A1 跑分 | 先跑分，不先买 |
| 3000 任务 CH 167.8s 是主瓶颈，需要单次求解内部并行 | 已实测 | 需厂商验证企业版是否加速 CH；**不要假设** moveThread 一定救 CH |
| B 需要官方 indictment / 最小冲突集，重放做不出来 | 当前 replay 可先做 | 不是充分理由；先做 replay + 静态分析 |
| CTP 承诺 QPS 超过单机槽位 | 待 C1 压测 | 优先多进程/多机，通常比企业版便宜 |
| 需要官方 SLA / 商业支持 / 审计 | 业务决定 | 这是商业理由，不是技术理由 |

**购买前必须让厂商书面确认**：多线程移动对 CH 是否有效、`SolutionManager.analyze` 的 indictment 在 2.8.x 的 API 稳定性、按核/按实例的报价与上限。公开资料没有统一价格，本文不编造价位。

### 2.5 求解线程、Web 线程池、数据库连接池的关系

#### 2.5.1 现状负载模型

| 资源 | 现状 | 说明 |
|---|---|---|
| Tomcat 线程 | 默认 200 | 求解任务是异步提交，Web 线程只入队/轮询；但 **ATP ACCURATE 是同步**，会占 Web 线程最长 10s |
| 求解 worker | 1 | 第二个 solve task 排队；重试 sleep 30s 会阻塞整个队列 |
| 求解槽 | 1（`SOLVER_LOCK`） | ATP / solve task / 增量共用；并发高时 5s 后失败 |
| Hikari | 10（dev） | 求解期间不持有连接；装配/写库是短查询；CTP 突发读可能打满 |
| 队列 | 32 | 超出快速拒绝；portfolio 不应增加任务队列，而应增加单任务内候选 |
| 进度推送 | STOMP + 内存 publisher | 单进程；A2 多进程要转发 |

**当前瓶颈顺序**：求解 CPU/锁 > 重试 sleep > 装配 DB 读 > Hikari（仅 CTP 突发）> Web 线程（仅同步 ATP）。

#### 2.5.2 目标负载模型

| 资源 | 目标 | 理由 |
|---|---|---|
| Web 线程 | 只做入队、轮询、轻查询；ATP/CTP 不直接跑长求解 | 避免 200 个线程被 10–15s 占满 |
| 任务 worker | 仍 1 个，负责状态机与装配 | 保证同一 sandbox 不并发写；portfolio 在任务内部并行 |
| 候选池 | 固定 N（默认 3），有界队列 N | 候选数由 CPU/堆决定，不随请求无限增长 |
| 求解槽 | 总 N，CTP 预留 1，solve 用 N-1（或动态借用） | 防止 CTP 被后台求解饿死 |
| 重试 | `ScheduledExecutorService` 定时，不占 worker | 30s 重试不再阻塞队列 |
| Hikari | 维持 10；CTP 加单飞 + 5 分钟缓存 + epoch 失效 | 不靠加连接解决慢查询 |
| 队列 | 维持 32；新增准入与 `Retry-After` | 过载时明确拒绝，不无限堆积 |

#### 2.5.3 具体改动清单

| 文件 | 改动 | 风险 |
|---|---|---|
| `TimefoldSolverAdapter` | 去静态锁/权重；`SOLVER_BUSY`；候选监听；`scoreHistory` | 分数/归因回归；必须 A0 先合 |
| `SolveTaskLifecycleService` | 重试改定时器；portfolio 编排；取消语义；A0 已加通用后置钩子 | 状态机回归；B2 不编辑此文件 |
| `ScheduleService` | 装配一次、调 coordinator、只写赢家；完整性守卫沿用 | 失败路径不能写 partial |
| `AtpService` | ACCURATE 从 Web 线程移出，或至少加并发 1 + 预留槽；避免 portfolio | 现有前端超时语义变化 |
| `application.yml` | 新增 `aps.solve.portfolio.*`、`aps.ctp.*`、`aps.solve.max-concurrent-solvers` | 与 C 共用文件，需配置管家串行 |
| `SolveProgressPublisher` | 增加候选 best score（可选字段，老客户端忽略） | STOMP 消息体积；节流 1s |
| `SolveTaskRepository` | `metrics.portfolio` 写入 | JSONB 体积；限制候选数组长度/曲线点数 |

#### 2.5.4 瓶颈会在哪

| 场景 | 现在 | 目标 | 瓶颈 |
|---|---|---|---|
| 1 个 300 任务求解 | 1 核，300s | 3 候选，3 核，墙钟约 300s | CPU 饱和；Web/ATP 被抢，需留 2 核 |
| 2 个求解任务同时到 | 第二个排队 300s | 仍排队（单 worker） | 任务队列；需 A2 才能多任务并行 |
| 10 个 ATP ACCURATE | 9 个 5s 后失败 | 预留槽 + 并发 1 + 缓存 | 求解槽；不是 Hikari |
| 1 个 CTP + 1 个后台 solve | 抢锁，CTP 可能失败 | CTP 预留槽，solve 用 N-1 | 求解槽/CPU |
| 重试 1 次 | 队列停 30s | 定时器，worker 继续 | 无 |
| CTP 突发 20 次 | 20 个 Web 线程等锁 | 单飞合并 + 缓存 | Hikari/DB 读，不是 solver |

### 2.6 基准集与回归怎么跑

#### 2.6.1 题库组成（20–50 题）

| 类别 | 数量 | 目的 | 通过标准 |
|---|---|---|---|
| 冒烟 | 8 | 1–3 单、5–15 任务 | SOLVED + 0hard；分数不劣于基线 |
| 可行中题 | 12 | 10–30 单、60–150 任务 | 0hard；soft 中位数不劣于基线 2% |
| 难可行 | 8 | 30–50 单、200–300 任务、紧交期 | 0hard 达成率；time-to-best |
| 不可行 | 6 | 日历缺口、交期不可能、冻结冲突、setup、辅助资源、缺路由 | 证据召回；**不得误报“可承诺”** |
| 大题参考 | 4 | 100–500 单、1000–3000 任务 | 只做容量/时延回归，不做 pass/fail |
| 增量/热启动 | 4 | 有初值 + 新单 | 赢家不劣于单解；time-to-best 更短 |
| 合计 | 42 | — | — |

题面固化：`aps-solver/solver-timefold/src/test/resources/bench/cases/<caseId>/problem.json` + `meta.json`（订单数、任务数、期望状态、分数下界、标签、不可行证据期望）。基线：`baseline.json`（commit、config hash、每例 hard/soft/耗时）。

#### 2.6.2 跑分器位置（不新增 Maven 模块）

- 放在 `aps-solver/solver-timefold/src/test/java/.../bench/SolverBenchRunner.java`；类名不以 `Test` 结尾，默认 Surefire 不跑。
- 运行：`mvn -o -pl aps-solver/solver-timefold -Dtest=SolverBenchRunner test`；不碰 `package`。
- 输出：`target/bench/bench-report.json` + `docs/perf/bench-YYYYMMDD.md`（A3 独占 `docs/perf`）。
- 不新增 pom；如果未来需要独立模块，由 W9 统一改 pom，不在 A3 里做。

#### 2.6.3 seed 策略

| 用途 | seeds | 规则 |
|---|---|---|
| 调参/训练 | 0,1,2 | 可以看结果，但不得写进生产默认配置 |
| 验证 | 11,12,13 | 只看中位数/IQR，不逐例调参 |
| 隐藏 holdout | 101,102,103（由 QA 保管） | 车道不可见；发布前只跑一次 |
| 确定性回归 | seed=0 + 步数终止 | 必须逐例分数一致 |

#### 2.6.4 分数一致性

- **确定性模式**：`parameters.deterministic=true`，CH 与 LS 都用 `stepCountLimit`，不用墙钟；同 seed 同配置必须 hard/soft 完全一致。社区版没有 step 指标，`SolveMetrics` 不填 step 即可，不影响分数。
- **SLA 模式**：墙钟终止，不要求逐位一致；比较 5 个 seed 的中位数与 IQR，硬分不允许回退，软分回退 ≤2%，time-to-best 回退 ≤10%。
- **归因一致性**：`JustificationScoreConsistencyTest` 必须继续通过；portfolio 赢家的 justification 分数必须等于 solution 分数。
- **赢家确定性**：同题面、同 seed 集合，重复 3 次赢家 profile/score 一致；不一致视为缺陷。

#### 2.6.5 防止“为了过基准而调参”

1. **三套数据**：训练 / 验证 / 隐藏 holdout；holdout 不参与调参。
2. **配置哈希**：每次跑分记录 `configHash`；生产默认配置只能来自验证集结论，不能按 caseId 分支。
3. **禁止 caseId 条件逻辑**：代码评审明确拒绝 `if (caseId.equals(...))`、按题面大小写死 profile 等。
4. **全量报告**：必须报告每一例，不只报平均；任何“只报通过例”视为无效。
5. **基线冻结**：`baseline.json` 绑定 commit；改基线必须 W1/A 负责人签字，并说明原因。
6. **统计门槛**：至少 5 seeds；比较中位数/IQR；单次好运不算通过。
7. **回归门禁**：hard 回退 = 阻断；soft 回退 >2% = 阻断；time-to-best >10% = 阻断；不可行证据召回 <90% = 阻断。

### 2.7 观测：并行求解必须补齐的指标

| 指标 | 类型 | 来源 | 用途 | 告警 |
|---|---|---|---|---|
| `solve.portfolio.candidates` | gauge | `metrics.portfolio.candidateCount` | 本次实际候选数 | 长期为 5 说明配置过大 |
| `solve.portfolio.candidate.duration` | timer（tags: profile/status） | 候选起止 | 各 profile 成本 | P95 超总预算 80% |
| `solve.portfolio.winner.profile` | counter | 选优结果 | 哪个 profile 胜出 | 单一 profile 100% 说明其它候选无用 |
| `solve.time_to_best` | timer | `BestSolutionChangedEvent` 首点 | 用户体验 | 比基线恶化 >10% |
| `solve.score.timeline` | 采样（1s/事件，限 200 点） | 监听器 | 复盘中段停滞 | 长期无改进 |
| `solve.slot.wait` | timer | 信号量 acquire | 槽位是否不足 | P95 > 超时 50% |
| `solve.queue.depth` / `solve.rejected` | gauge/counter | worker/准入 | 过载 | 拒绝率 >1% |
| `solve.candidate.oom` | counter | 候选 catch | 内存风险 | 任意一次即告警 |
| `solve.portfolio.degraded` | counter（reason） | 准入 | 多少题被迫单候选 | 比例 >30% 说明 gating 太松 |
| `atp.accurate.duration` / `ctp.promise.duration` | timer | ATP/CTP | 承诺体验 | P95 > 15s |
| `db.pool.active` / `assembly.duration` | gauge/timer | Hikari/装配 | DB 瓶颈 | 活跃 100% 持续 30s |
| `process.cpu.usage` / `jvm.memory.used` | gauge | Micrometer | CPU/内存预算 | CPU >85% 持续 1min |
| `infeasible.verdict` / `counterfactual.verified` | counter | B | 归因质量 | 误报“可行”任意一次即阻断 |
| `ctp.promised` / `ctp.unpromisable` / `ctp.out_of_horizon` | counter | C | 承诺闭环 | 承诺后验证失败任意一次即阻断 |

存储：

- 候选明细放 `solve_task.metrics.portfolio`（JSONB，数组截断到 8 个候选、曲线截断到 200 点）。
- `scoreHistory` 由监听器填充；`timeToBestMs` = 首个 `BestSolutionChangedEvent` 的 elapsed。
- 聚合胜出率来自跑分报告，不在每次请求写表；后续需要可按 JSONB 查询，不急着建表。
- Micrometer 已有 actuator 依赖；Prometheus registry 没有，本轮不新增依赖；若运维要拉取，由 W9 单独加 `micrometer-registry-prometheus`。

### 2.8 A 的验收标准与“不做什么”

**验收**

- A0：单题分数不变；两线程不同权重不串；锁竞争返回 `SOLVER_BUSY` 不重试；B 钩子可挂。
- A1：42 题跑分；3 候选在 300 任务上墙钟不劣于单解 +15%；hard 不回退；无 OOM；赢家确定；失败隔离。
- 指标：候选明细、time-to-best、槽位等待、降级原因齐全。
- 灰度：默认 `portfolio.enabled=false`，按任务规模灰度打开；可一键关闭。

**不做**

- 不在 A1 改约束提供器的业务规则（B/C 也不要）。
- 不新增 `TaskPlan` / `SchedulePlan` record 组件；V1 不需要。
- 不用 `moveThreadCount` 作为交付项。
- 不把 3000 任务纳入 portfolio 默认范围。
- 不在 A1 做多进程；A2 条件启动。

---

## 3. B：不可行归因与反事实

### 3.1 能证明什么、不能证明什么

- 能说：在给定题面、给定时间预算内，求解器**没有找到**满足全部硬约束的方案；证据显示哪些任务未被分配、哪些资源区间冲突、哪些规则被违反。
- 不能说：数学意义上“此题不可行”。除非穷举或企业版 indictment 给出可验证的证明，否则一律用 `NO_FEASIBLE_SOLUTION_FOUND`，不用 `INFEASIBLE_PROVEN`。
- “最小修复”是**启发式排序 + 求解验证**，不是严格最小集；对外文案用“建议优先尝试的修复”，不用“唯一最小修复”。

### 3.2 证据模型（C51）

新增 `InfeasibilityReport`（放 `solver-api`，W1 在 Wave 0 冻结形状）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `solveTaskId` | long | 关联任务；失败任务也必须有 |
| `verdict` | enum | `NO_FEASIBLE_SOLUTION_FOUND` / `NO_EVIDENCE` / `PARTIAL_EVIDENCE` |
| `blockingTasks` | array | 未分配/被阻塞的任务：taskId、orderNo、operation、reason |
| `conflictPairs` | array | 资源/顺序冲突对：taskA、taskB、resourceId、overlapStart/End |
| `resourceWindows` | array | 资源可用窗口与需求时长：resourceId、windowStart/End、requiredHours、gapHours |
| `violatedRules` | array | ruleCode、constraintName、deduction、count |
| `assumptions` | array | “未考虑物料”“视野 168h”“冻结任务按不可动”等 |
| `generatedAt` / `solverRunId` / `candidateSeed` | — | 复现用 |
| `truncated` | boolean | 超过 8 条时的截断标记；完整原始证据在 DB |

### 3.3 证据采集流程

1. **静态分析（优先）**：不依赖求解结果，直接读 `ScheduleProblem`：
   - 未分配任务 / 缺失路由 / 资源无可用窗口覆盖工时；
   - 冻结任务与请求移动冲突；
   - 订单 `notBefore` + 工时 > 视野；
   - 辅助资源/工装缺失。
2. **谓词重放**：`ConstraintJustifications.from(best, problem)`，按 `ruleCode` 聚合；这是现有 C19/C44 路径，必须复用。
3. **冲突对**：按资源排序区间，找 overlap；对未分配任务，计算其候选资源窗口被谁占满。
4. **分组与截断**：每类最多 8 条进 API；完整 JSON 进 `constraint_justification.implicated_facts`。
5. **可选 spike**：用内部 `InnerScoreDirector` + `ConstraintMatchPolicy.ENABLED_WITHOUT_JUSTIFICATIONS` 取 `ConstraintMatchTotal`；限时验证，失败即回退重放。**不得**把 `SolutionManager.analyze()` 作为依赖（社区版会走商业服务）。

### 3.4 最小修复建议与反事实

| 修复类型 | 触发证据 | 修改 | 成本权重 | 验证 |
|---|---|---|---|---|
| 扩日历窗口 | `resourceWindows.gap` | 增加班次/加班窗口 | 中 | 5–10s 重解 |
| 增加资源/外协 | 资源窗口全满 | 加资源或外协产能 | 高 | 5–10s 重解 |
| 放宽冻结 | 冻结冲突 | 解冻指定任务 | 低 | 5–10s 重解 |
| 延交期 | 订单 `notBefore`/交期不可能 | 放宽 dueDate | 低 | 5–10s 重解 |
| 拆单/分批 | 单批超出窗口 | 拆成两个批次 | 中 | 5–10s 重解 |
| 物料替代 | 物料缺口（C49 substitutes） | 换替代料 | 中 | CTP 物料重算 + 重解 |

流程：

1. `RepairAdvisor` 按“可解除的阻塞任务数 / 成本权重”排序，最多生成 3 个候选。
2. 每个候选构造修改后的 `ScheduleProblem`（不写库），调用 `SolverAdapter.solve`，超时 5–10s。
3. 结果状态：
   - `VERIFIED_FEASIBLE`：hard=0 且任务全分配；对外显示“可行”，附新日期/资源。
   - `STILL_INFEASIBLE`：hard<0；显示“仍不可行”，附新证据。
   - `UNVERIFIED`：超时/求解不可用；显示“未验证”，不得说可行。
   - `OUT_OF_BUDGET`：超过 30s 总预算；不显示。
4. 只有 `VERIFIED_FEASIBLE` 能进入“建议”；反事实的日期/分数必须来自求解结果，不能由 LLM 或规则编造。

### 3.5 持久化与 API（零 DDL 方案）

复用现有表，不新增迁移：

- `constraint_justification`（V1 L781–L802）已有：
  - `solve_task_id`、`constraint_id`、`score_impact`、`implicated_facts JSONB array`、`explanation_zh`。
- B2 写入约定：
  - `constraint_id = "INFEASIBLE.<KIND>"`（如 `INFEASIBLE.BLOCKING_TASK`、`INFEASIBLE.CONFLICT_PAIR`、`INFEASIBLE.REPAIR`）。
  - `implicated_facts` 数组元素：`{kind, taskIds, resourceId, start, end, detail, attemptNo}`。
  - `explanation_zh` 为确定性文案；LLM 只改写，不覆盖事实。
- `ConstraintJustificationRepository` 增加**只读**方法 `findRawBySolveTaskId(Long)` 返回 JSONB 原样；现有 C19 `findBySolveTaskId` 不动（仍最多 8 条）。
- 失败/超时：`SolveTaskLifecycleService.finishOutcome` 的单点钩子调用 `SolveEvidenceService.persistOnFailure(taskId, outcome)`；先删同 task 的旧 `INFEASIBLE.*` 再插入（重试幂等）。
- 成功但有 hard 违规：同一套写入，`score_impact=HARD`。
- 无证据：不写行，API 返回 `NO_EVIDENCE`。

API（C51/C52，W1 写）：

- `GET /solve-tasks/{id}/infeasibility`：返回 `InfeasibilityReport`；失败任务也可查；无证据 200 `NO_EVIDENCE`。
- `POST /solve-tasks/{id}/counterfactual`：body `{kind, params}`；返回 `VERIFIED_FEASIBLE` / `STILL_INFEASIBLE` / `UNVERIFIED` / `OUT_OF_BUDGET`；只读，不写计划。
- 现有 `GET /solve-tasks/{id}/justifications` 保持 C19 语义（最多 8 条），不扩容。

### 3.6 LLM 边界

- 现有 `AgentExplainService` 已是“确定性事实 + 可选 LLM 改写”；B 复用，不新开链路。
- LLM 输入只给结构化事实（数字、任务、资源、日期）；输出只改 `narrative`。
- 发布前做数字校验：narrative 里的每个日期/任务号/资源号必须能在 facts 中找到；找不到则回退确定性文案。
- LLM 关闭/超时：仍返回 facts 与确定性文案；`llmStatus` 标记，不阻塞归因。

### 3.7 与 A 的接缝

- B 不直接改 `TimefoldSolverAdapter` 的求解逻辑；只实现 A0 预留的 `SolvePostProcessor`。
- B 不碰 `ApsConstraintProvider` 的业务规则；如果确实需要新增“证据用约束”，必须单独预约，并证明不影响求解分数。
- B 的失败证据写入在 winner-only 之后，不与 A 的结果写入抢同一事务；单独短事务。
- B 的反事实重解走同一个 `SolverAdapter` 槽位；必须限 3 个候选 × 5–10s，不能把一次失败归因拖成 30s+。

### 3.8 B 的验收标准

- 6 个不可行基准例：阻塞任务识别率 ≥90%；冲突对正确率 ≥80%；`NO_FEASIBLE_SOLUTION_FOUND` 不误报为“不可行证明”。
- 失败/超时任务都能查到 `InfeasibilityReport`；重试不重复插入。
- 3 个反事实例：`VERIFIED_FEASIBLE` 的新方案必须 hard=0 且全分配；`UNVERIFIED` 不得显示“可行”。
- 前端：无证据、部分证据、已验证修复三态清晰；LLM 关闭时仍可用。

---

## 4. C：交期承诺（CTP）

### 4.1 ATP 与 CTP 的差别

| 维度 | 现有 ATP | CTP（本路线） |
|---|---|---|
| 输入 | 物料 + 数量 + 日期 | 同上 + 客户/优先级/替代料策略/拆批策略 |
| 产能 | ACCURATE 10s，`assembleAtp` | 单次 ACCURATE 验证，15s 上限，预留槽 |
| 物料 | 默认不考虑 | 多级 BOM + 库存 + 在途 + 替代（C49） |
| 日期 | FAST 估计 / ACCURATE 结果 | **只返回求解验证过的日期**；FAST 只选候选 |
| 失败 | TIMEOUT / 不可估算 | `UNPROMISABLE` + B 证据 + 原因 |
| 写库 | 只读，不建 solve_task | 只读 + `audit_log` 承诺事件；不建 solve_task |

### 4.2 算法：物料门 → FAST 选候选 → ACCURATE 验证

1. **物料可齐日 `materialReadyAt`**
   - 复用/抽取 `MrpAnalysisService` 的多级 BOM 展开（私有 `expand`，含 `MAX_BOM_DEPTH` 与 C49 替代料）；V1 由 C1 预约抽取成 `BomExplosionService`，MRP 改为委托，必须跑 MRP 金样回归。
   - 读库存 + 在途预计到货 + 替代料；计算顶层与关键件的可齐日。
   - 数据缺失（无预计到货/无提前期）→ `UNKNOWN_MATERIAL_DATE`，保守返回 `UNPROMISABLE`，不猜。
   - V1 假设：所有组件在首工序前齐套；不支持“边做边等料”的部分齐套。若要支持，另立 C54 每工序物料门（需要模型加字段，见 4.6）。

2. **构造 CTP 题面**
   - 路由/工序来自现有订单规格；`SchedulingOrder.notBefore = max(现有 notBefore, materialReadyAt)`；`dueDate = requestedDate`。
   - 用 `assembleAtp` 生成只读快照（含已确认订单占用），再在 `CtpProblemDecorator` 里替换该订单的 `notBefore`；**不改 `ScheduleProblemAssembler`**，避免热文件冲突。
   - 视野：`horizonEnd = min(requestedDate, base + 168h)`；超出 → `OUT_OF_HORIZON`（不是“不可行”）。

3. **FAST 选候选（不是承诺）**
   - 用现有 `FAST` 估计器找出一个“最早看起来可行”的候选日期 `d0`；FAST 结果只用于缩小验证范围，不返回给客户作为承诺。
   - 若 FAST 说无候选，直接 `UNPROMISABLE`（附原因），不再烧求解预算。

4. **ACCURATE 验证（日期来自求解）**
   - 单候选、单次 solve，`timeout = min(request.timeout, 15s)`；不使用 portfolio。
   - 通过条件：`status=SOLVED`、`hard=0`、该订单全部工序已分配、完成时间 ≤ `requestedDate`。
   - `promisedDate` = 求解结果中该订单的实际完成日期（不是 `d0`，也不是请求日期）。
   - 失败：最多尝试 `d0+1` 天（`maxAttempts=2`）；仍失败 → `UNPROMISABLE`，附 B 证据。
   - 任何情况下不得用 FAST 日期或规则日期替代求解日期。

5. **缓存与单飞**
   - key：`tenant + materialCode + qtyBucket + requestedDate + materialPolicy + planEpoch`。
   - TTL 5 分钟；BOM/库存/在途/日历/计划发布任一变更 → `materialEpoch++`，旧缓存自然失效。
   - 同一 key 并发请求合并为一次求解（`CompletableFuture` 单飞，复用 MRP 已验证的模式）；不同 key 串行进入 CTP 槽。

### 4.3 物料服务的数据与边界

- 只读：`bom_items`、库存、在途、供应商/替代料；不写订单/计划。
- 多级：`MAX_BOM_DEPTH` 保护；循环 BOM 检测。
- 替代：C49 的替代料/预计到货字段；替代策略 `STRICT` / `SUBSTITUTE` 由请求决定。
- 自制件：V1 不递归重排产能，只按现有库存/在途/预计完工；否则会变成“全 MRP + 全排产”的 NP 难题。若要递归，必须新契约与新算法，不在 C1。
- 假设必须随响应返回：`assumptions: ["ORDER_LEVEL_MATERIAL_GATE", "HORIZON_168H", "NO_RECURSIVE_MAKE"]`。

### 4.4 并发与容量

| 项 | 设计 |
|---|---|
| HTTP | V1 同步 `POST /orders/ctp`，上限 15s；并发 CTP 请求 ≥1 时返回 `429/503` + `Retry-After`，不无限排队 |
| 求解槽 | 总槽 N；CTP 预留 1；后台 solve 用 N-1；CTP 空闲时是否借用由配置决定，默认不借 |
| 单飞 | 同 key 合并；不同 key 串行（`max-concurrent=1`） |
| DB | 物料读批量查询；不包长事务；单飞 + 缓存；不占 Hikari 连接等求解 |
| 缓存 | 5 分钟 + epoch；承诺结果可审计但不作为长期缓存 |
| 审计 | `audit_log` 写 `CTP_PROMISE`（request/response/evidence 放 JSONB），不新建表 |
| 超视野 | `OUT_OF_HORIZON`，返回 `horizonEnd`；不要谎称不可行 |

### 4.5 契约与前端

C53（W1 发号）：

```text
POST /orders/ctp
CtpRequest {
  materialCode, qty, requestedDate,
  customerCode?, priority?,
  allowSplitBatch, allowSubstitute,
  materialPolicy: STRICT | SUBSTITUTE,
  timeoutSeconds?   // 上限 15
}
CtpResult {
  inquiryId,
  verdict: PROMISED | UNPROMISABLE | OUT_OF_HORIZON | NO_ROUTE | NO_MATERIAL | SOLVER_TIMEOUT | BUSY,
  promisedDate?, promisedQty?,
  materialReadyAt?, capacityVerifiedAt?,
  solver: { engine, seed, hardScore, softScore, durationMs, terminationReason, solveTaskId: null },
  material: { levels, shortages, substitutes, assumptions },
  evidence: [...],
  assumptions: [...],
  generatedAt
}
```

- 只有 `verdict=PROMISED` 时 `promisedDate` 非空；其它一律 null。
- `BUSY` 用 429/`50300`，带 `Retry-After`；`OUT_OF_HORIZON` 是业务状态，不是错误。
- 前端：`AtpEstimate.vue` 增加“承诺”模式；`CtpPromisePanel.vue` 展示三态、物料明细、证据、假设；契约桩 + mock 先行；`promisedDate` 必须标注“来自求解验证”。

C54（可选，后置）：

- 多级 BOM 明细与替代链的完整结构；V1 可先放 `CtpResult.material` 的开放对象里，等前端需要稳定结构再发 C54。

### 4.6 C 的限制（必须写进产品文案）

1. **视野 168h**：`TimefoldModelBuilder` 起始网格硬编码 168h；超过 7 天的交期返回 `OUT_OF_HORIZON`。要支持更长，需把 horizon 从题面/配置传入模型（`ScheduleProblem` 无 horizon 字段，改 record 会牵动 15 处构造；由 A/C 在 W2 评估，不在 C1）。
2. **物料门是订单级**：`SchedulingOrder.notBefore` 只约束订单首工序；不支持每工序不同齐套日。部分齐套/边做边等料不支持。
3. **不做自制件递归产能**：V1 只读现有库存/在途/预计完工；递归 MRP 再排产是下一阶段。
4. **不是“数学证明不可行”**：`UNPROMISABLE` 是“在预算内未找到可行”，不是不可行证明。
5. **不承诺未验证日期**：任何 UI/文案都不得展示 FAST 日期作为交期。

### 4.7 C 的验收标准

- 10 个 CTP 例：物料齐套、缺口、替代、超视野、无路由、求解超时、并发 BUSY 全覆盖。
- **零假承诺**：所有 `PROMISED` 必须能被同题面重解验证 hard=0 且完成日 ≤ promisedDate。
- P95 ≤15s（单机、预留槽、缓存命中另算）；并发 5 次同 key 只跑 1 次求解。
- `aps_demo` / `aps_local` 不被写；只读查询 + `audit_log`（若开启审计）。

---

## 5. 第二半：开发并发改造路线

### 5.1 车道切分（详细）

| 车道 | 可并行于 | 负责范围 | 独占文件 | 共享只读 | 串行前置 |
|---|---|---|---|---|---|
| **W1 契约** | A0、A3 | C50–C55、OpenAPI、DTO 形状、错误码 | `docs/api/contract-freeze.md`、`docs/api/openapi/*.yaml`、`docs/api/enums.md`、`controller/contract/**` | 全部 | 无 |
| **A0 内核** | W1、A3 | 去静态锁/权重、槽位、后置钩子、`SOLVER_BUSY` | `TimefoldSolverAdapter`、`SoftConstraintWeights`、`SolverConfiguration` | `ApsConstraintProvider`、`TimefoldModelBuilder` | 无 |
| **A3 基准** | W1、A0 | 题库、跑分器、基线 | `solver-timefold/src/test/**/bench/**`、`docs/perf/bench-*` | `ScheduleProblem` 格式 | 无 |
| **A1 Portfolio** | B1、C1、FE | 候选并行、选优、准入、指标 | `TimefoldSolverAdapter`（A0 之后）、`SolveTaskLifecycleService`、`ScheduleService`、新增 `portfolio/**` | `ScheduleProblemAssembler`、`ScheduleResultWriter` | A0 |
| **B1 归因内核** | A1、C1、FE | 静态分析、重放、阻塞集、修复候选 | 新增 `InfeasibilityAnalyzer`、`RepairAdvisor`、`EvidenceCollector` | `ApsConstraintProvider`、`ConstraintJustifications`、`TimefoldModelBuilder` | A0 钩子 |
| **B2 证据 API/FE** | C2、FE | 证据写入、C51/C52、归因面板 | 新增 `SolveEvidenceService`、`controller/contract` 的 C51/C52（W1 写）、`InfeasibilityPanel.vue` | `ConstraintJustificationRepository`、`SolveTaskView.vue` | B1 + C51/C52 |
| **C1 CTP 物料/验证** | A1、B1、FE | BOM 可齐日、FAST 候选、ACCURATE 验证、单飞/缓存 | 新增 `ctp/**`、`AtpService` 的 CTP 扩展 | `MrpAnalysisService`（抽取预约）、`ScheduleProblemAssembler` | C53 + A0 槽位 |
| **C2 CTP API/FE** | B2、FE | C53 接口、承诺面板、审计 | `OrdersContractController` 的 C53（W1 写）、`CtpPromisePanel.vue`、`api/ctp.ts` | `AtpEstimate.vue`（FE-C 独占）、`audit_log` | C1 + C53 |
| **FE-A** | 任意 | Portfolio 候选面板 | `SolvePortfolioPanel.vue`、`api/solvePortfolio.ts` | `SolveTaskView.vue`（FE-B 单写） | W1 桩 |
| **FE-B** | 任意 | 归因/证据面板 | `SolveTaskView.vue`、`InfeasibilityPanel.vue`、`views/gantt/*`、`api/infeasibility.ts` | `mocks/handlers.ts` | W1 桩 + C51 |
| **FE-C** | 任意 | CTP 承诺面板 | `AtpEstimate.vue`、`CtpPromisePanel.vue`、`api/ctp.ts`、`types/ctp.ts` | `OrdersView.vue`（一次插入）、`mocks/handlers.ts` | W1 桩 + C53 |
| **W9 构建/运行时** | 无（收口） | 构建令牌、制品、18085、共享 DB | `pom.xml`、`deploy/**`、`scripts/**`、`docs/deploy/**` | 全部 | 各车道合并完成 |

**可并行**：W1 ∥ A0 ∥ A3；之后 A1 ∥ B1 ∥ C1 ∥ FE-A/B/C；再之后 B2 ∥ C2。

**必须串行**：W1 契约 → 任何引用新字段的代码；A0 → A1/B1；B1 → B2；C1 → C2；W9 构建 → 部署。

### 5.2 文件所有权矩阵

图例：`独` = 独占；`只` = 只读；`约` = 需书面预约，一次一个；`W1` = 契约单写者；`—` = 不涉及。

| 文件 | A | B | C | FE | 规则 / 说明 |
|---|---|---|---|---|---|
| `aps-solver/solver-api/.../SolverAdapter.java` | 独（A0） | 只 | 只 | — | A0 加后置钩子/默认方法；B/C 不改 |
| `.../SolveConfig.java` | 独 | 只 | 只 | — | A 加 portfolio 参数；B/C 不改 |
| `.../ScheduleProblem.java` | 独 | 只 | 只 | — | V1 不改字段；C 用装饰器 |
| `.../SchedulingOrder.java` | 只 | 只 | 约 | — | C 若加每工序物料门需改；V1 不改 |
| `.../SchedulingOperation.java` | 只 | 只 | 约 | — | 同上 |
| `.../ScheduleSolution.java` | 只 | 只 | 只 | — | 输出契约；B 只读 |
| `.../ConstraintJustification.java` | 只 | 约 | 只 | — | B 新增 `ConstraintEvidence`，不改旧 record |
| `.../SolveMetrics.java` | 独 | 只 | 只 | — | A 填 `scoreHistory`/`portfolio` extra |
| `.../task/SolveRetryReasons.java` | 独 | 只 | 只 | — | A 新增 `SOLVER_BUSY` 非重试 |
| `aps-solver/solver-timefold/.../TimefoldSolverAdapter.java` | 独（A0/A1） | 只 | 只 | — | B 通过钩子；C 不改 |
| `.../TimefoldModelBuilder.java` | 独 | 只 | 只 | — | 168h 网格；C 若要扩展视野需 A 预约 |
| `.../model/TaskPlan.java` / `SchedulePlan.java` | 独 | 只 | 只 | — | **禁止 B/C 同时加 record 组件** |
| `.../constraint/ApsConstraintProvider.java` | 只 | 约（仅证据映射） | 只 | — | 业务规则变更需单独评审；A 不改 |
| `.../constraint/SoftConstraintWeights.java` | 独（A0） | 只 | 只 | — | 线程本地化；B/C 不改 |
| `.../constraint/ConstraintJustifications.java` | 只 | 约 | 只 | — | B 扩展采集；不改求解 |
| `.../constraint/ConstraintPredicates.java` | 只 | 只 | 只 | — | 证据复用；如需改谓词，走预约 |
| `aps-service/.../schedule/ScheduleService.java` | 独（A1） | 约（失败钩子） | 只 | — | A 改选优/写赢家；B 只加单点调用 |
| `.../schedule/ScheduleProblemAssembler.java` | 只（A1 不修改） | 只 | 只 | — | C 用 `CtpProblemDecorator`，不改热文件 |
| `.../schedule/ScheduleResultWriter.java` | 只 | 只 | 只 | — | 只写赢家；B 证据另写 |
| `.../schedule/SolveTaskLifecycleService.java` | 独（A1） | 只 | 只 | — | A0 已插入通用 `postProcessor` 调用；B2 只实现 `SolvePostProcessor`，不再编辑此文件 |
| `.../schedule/SolveProgressPublisher.java` | 独（A1） | 只 | 只 | — | 候选进度；节流 |
| `.../schedule/atp/AtpService.java` | 只 | 只 | 独（C1） | — | C 扩展 CTP；A 不改 ATP |
| `.../mrp/MrpAnalysisService.java` | 只 | 只 | 约（抽取 BOM） | — | C1 抽取 `BomExplosionService`；必须 MRP 回归 |
| `aps-web/.../controller/contract/SolveContractController.java` | 只 | 只 | 只 | — | W1 独占 C51/C52 |
| `aps-web/.../controller/contract/OrdersContractController.java` | 只 | 只 | 只 | — | W1 独占 C53 |
| `aps-web/.../controller/SolveTaskController.java` | 只 | 只 | 只 | — | 契约面；W1/A1 预约 |
| `docs/api/contract-freeze.md` | 只 | 只 | 只 | 只 | **W1 唯一写者** |
| `docs/api/openapi/solve.yaml` / `orders.yaml` | 只 | 只 | 只 | 只 | **W1 唯一写者** |
| `docs/api/enums.md` | 只 | 只 | 只 | 只 | **W1** |
| `db/migration/*` | 只 | 只 | 只 | — | V1 零 DDL；如必须，W1 发号、单文件单写者 |
| `aps-admin/.../application.yml` | 约 | 只 | 约 | — | 配置管家一次一个车道；键前缀隔离 |
| `aps-frontend/src/views/SolveTaskView.vue` | — | 独（FE-B） | — | FE-B | FE-A 只加子组件挂载，由 FE-B 合并 |
| `aps-frontend/src/components/solve/InfeasibilityPanel.vue`（新） | — | 独（FE-B） | — | FE-B | B 专用 |
| `aps-frontend/src/components/solve/SolvePortfolioPanel.vue`（新） | — | — | — | FE-A | A 专用 |
| `aps-frontend/src/views/gantt/OrderGanttView.vue` / `ResourceGanttView.vue` | — | 独（FE-B） | — | FE-B | 归因叠加；A/C 不改 |
| `aps-frontend/src/components/orders/AtpEstimate.vue` | — | — | — | FE-C | CTP 承诺模式；FE-A/B 不改 |
| `aps-frontend/src/components/orders/CtpPromisePanel.vue`（新） | — | — | — | FE-C | C 专用 |
| `aps-frontend/src/api/solvePortfolio.ts`（新） | — | — | — | FE-A | A 专用 |
| `aps-frontend/src/api/infeasibility.ts`（新） | — | — | — | FE-B | B 专用 |
| `aps-frontend/src/api/ctp.ts`（新） | — | — | — | FE-C | C 专用 |
| `aps-frontend/src/types/solve.ts` | — | 独（FE-B） | — | FE-B | 新增证据类型；A 用 `types/portfolio.ts` |
| `aps-frontend/src/types/portfolio.ts`（新） | — | — | — | FE-A | A 专用 |
| `aps-frontend/src/types/ctp.ts`（新） | — | — | — | FE-C | C 专用 |
| `aps-frontend/src/mocks/handlers.ts` | — | — | — | 约 | 各 FE 新增独立 handler 模块，`handlers.ts` 只加 import；由 FE 负责人合并 |
| `aps-frontend/src/mocks/data.ts` | — | — | — | 约 | 大文件；优先拆 `mocks/data/<lane>.ts` |
| `pom.xml` / 模块 `pom.xml` | 只 | 只 | 只 | 只 | **W9 独占** |
| `deploy/**` / `scripts/**` / `docs/deploy/**` | 只 | 只 | 只 | 只 | **W9 独占** |
| `docs/testing/m2-build-incidents.md` | 只 | 只 | 只 | 只 | **W9 独占** |
| `docs/perf/**` | 独（A3） | 只 | 只 | — | A3 写跑分报告 |

### 5.3 契约变更串行点

#### 5.3.1 C50+ 怎么分配

- **唯一写者**：W1 / `dev-CONTRACT`（`contract-freeze.md#L1961`）；其它车道只能提交“契约变更卡”，不直接改文件。
- **发号规则**：落档前实际 `grep` 文件尾部最大 C 号（C49 之后从 C50 起）；**不猜号、不复用空洞**；一个号对应一个契约变更（一个资源/一个语义），不是一个车道。
- **预留表**（Wave 0 由 W1 写进 `contract-freeze.md`，未落档前只在本文档内部使用）：

| 编号 | 主题 | Owner | 状态 | 说明 |
|---|---|---|---|---|
| C50 | `SolvePortfolio` 候选摘要（可选字段） | A1 | 预留 | `SolveTaskDetail.metrics.portfolio` 已有开放对象；若前端要稳定展示再启用 |
| C51 | `SolveInfeasibilityEvidence` + `GET /solve-tasks/{id}/infeasibility` | B2 | 预留 | 失败/超时也可查；`NO_EVIDENCE` 语义 |
| C52 | `CounterfactualPlan` + `POST /solve-tasks/{id}/counterfactual` | B2 | 预留 | 只返回验证状态，不写计划 |
| C53 | `CtpInquiry` + `POST /orders/ctp` | C2 | 预留 | 同步；`PROMISED` 才带日期 |
| C54 | `CtpMaterialBreakdown` 稳定结构 | C2 | 预留（可延后） | V1 先放开放对象 |
| C55 | `SolvePortfolioProgress`（STOMP 候选进度） | A1 | 预留（可延后） | 只有前端需要实时候选曲线时启用 |
| C56+ | 未分配 | — | — | 由 W1 按需发号 |

- **冲突处理**：如果两个车道同时需要 C50，W1 按“先落档先得”，另一个顺延到下一个空号；**不许**同一号两个语义。
- **已发布号不可重编**：OpenAPI 一旦被前端引用，编号/字段名不再改；废弃用 `deprecated: true` + 新号。

#### 5.3.2 OpenAPI 与契约的单向依赖

强制方向：

```
contract-freeze.md（规范，W1）
  → docs/api/openapi/*.yaml（机器可读，W1）
    → controller/contract/**（实现，W1）
      → 前端 types/api（消费，FE）
```

规则：

1. 先有 YAML，再有 controller；controller 不得定义 YAML 里没有的字段/状态码。
2. 前端不得反向要求后端加字段；需要新字段 → 提契约卡给 W1。
3. 新增端点/字段必须有 `x-contract-id: C5x`、`x-implementation-status: placeholder|implemented`。
4. 错误码必须来自 `docs/api/enums.md`；`PROMISED/UNPROMISABLE` 是业务状态，不是 HTTP 错误。
5. 合并前检查：YAML 路径集合 vs controller 映射集合、schema 必填字段、示例 JSON 可解析；由 W1 做最后一道。
6. 禁止在已实现契约上直接改语义；用新号 + 兼容期。

#### 5.3.3 前端怎么在契约未落地前不阻塞

- Wave 0 由 W1 发 `placeholder` 的 YAML + 示例；FE 用 `VITE_ENABLE_MOCK`（`mocks/index.ts#L6-L15`）和 MSW handler 开发。
- 每个 FE 车道新增独立 handler 文件（`mocks/handlers-solve-portfolio.ts`、`handlers-infeasibility.ts`、`handlers-ctp.ts`），`handlers.ts` 只加 import；避免同一文件并发编辑。
- FE 类型放在独立文件（`types/portfolio.ts` / `types/ctp.ts`），不挤 `types/solve.ts`。
- 契约未实现时，API 层遇到 `501/503` 显示“能力未上线”，**不伪造成功**；mock 只用于开发，不用于验收。
- 后端实现后，FE 只切 `VITE_ENABLE_MOCK=false` 做联调；字段不一致由 W1 裁决，FE 不改契约。

### 5.4 构建与制品纪律

**风险（已发生）**

- 共享 `target/`：一个车道 `package` 会把另一个车道正在运行的 jar 覆盖；事故记录 `docs/testing/m2-build-incidents.md#L97-L107`，且不可恢复。
- `package` 覆盖运行中的 fat jar 导致 `ClassNotFound` / 404（L75-L79）。
- 只拷 thin jar 启动失败（L48）。
- 两个车道同时构建，编译产物互相污染，出现“本车道没改这个文件但测试挂了”。

**纪律**

1. **构建令牌**：仓库外 `..\_aps-artifacts\BUILD.lock`（或等价文件锁），只有 W9 或拿到令牌的车道能执行 `package`；令牌记录 owner/开始/结束。
2. **每车道 worktree**：`git worktree add ..\_wt\<lane>`，各自 `target/`，彻底隔离；共享仓库只做 `test`/`test-compile` 也要令牌，因为会写 `target/classes`。
3. **禁止非 W9 `package`/`repackage`**：验证用 `mvn -o -pl <module> -am test`；不要用 `-DskipTests package` 当编译检查。
4. **禁止覆盖标准名 jar**：产物先写 `..\_aps-artifacts\<timestamp>-<lane>\aps-admin.jar`，验收后再由 W9 复制到 `aps-admin/target`；旧 jar 先备份。
5. **制品与源码绑定清单**（沿用 `scripts/stack-bundle.ps1#L71-L133` 的 SHA256，补齐）：
   - `source_sha`、`dirty`（`git status --porcelain` 非空）、`branch`
   - `built_at`、`built_by`、`lane`
   - `jar_sha256`、`jar_size`、`BOOT-INF/lib` 数量
   - `migration_range`（如 `V1..V14`）
   - `test_summary`（用例数/失败数/跳过数）
   - `config_hash`（`aps.solve.*`/`aps.ctp.*` 关键键）
6. **部署前校验**：挂载 jar 的 SHA256 必须等于清单；`jar tf` 能列出 `BOOT-INF/classes` 与依赖；禁止 thin jar。
7. **构建期间不重启 18085**：构建与运行分离；W9 统一窗口。

### 5.5 共享运行时资源纪律

**登记表模板**（每次占用必须填，放车道回报里；W9 汇总）：

| 字段 | 必填 |
|---|---|
| 资源 | 18085 / `aps_demo` / `aps_local` / 端口 / DB |
| 标识 | PID、启动时间、jar SHA256、Flyway 版本 |
| Owner | 车道 + 人 |
| 租期 | 开始/结束（时间） |
| 用途 | 演示/联调/压测/迁移 |
| 停前状态 | `health`、RUNNING solve_task 数、活跃连接数 |
| 回退 | 旧 jar 路径、DB 备份/快照、重启命令 |
| 结果 | 新 PID、health、意外映射数、lib 数 |

**规则**

1. **18085（PID 34232）是演示实例**：只有 W9/DEMO owner 可停；任何车道不得因为“我这边空闲”停它。
2. **停前必须做**：记录 PID/启动时间/jar SHA；查 `solve_task` 是否有 RUNNING；查 `health`；确认没有人在演示；备份当前 jar；通知 owner。
3. **停后必须做**：校验新 jar SHA/`BOOT-INF/lib` 数；启动；`health`；Flyway 版本；日志无意外映射；更新登记表。
4. **`aps_demo` / `aps_local` 是共享库**：禁止 `flyway clean`；禁止修改已应用迁移的 checksum；迁移只由 W9 在窗口内执行；测试用 `aps_test_<lane>`，跑完 drop。
5. **端口分配**：18085 演示；18086–18099 车道联调（登记）；18100+ 临时；DB 端口 55432 共享，库名按车道隔离。
6. **谁能停**：资源 owner 或 W9；紧急停机需事后补登记；不得停别人的端口。
7. **回退**：保留上一版 jar + DB 备份；迁移只能前滚，不能回滚 checksum；失败时恢复 jar 并重启，迁移问题单独处理。

### 5.6 并行度与质量问题

**天生不适合并行的改动**

| 改动 | 原因 | 正确做法 |
|---|---|---|
| 同时改 `TaskPlan` / `SchedulePlan` record 组件数与 `ApsConstraintProvider` | 模型与约束强耦合，组件数变更会破坏所有构造与谓词 | Wave 0 冻结“V1 不加组件”；必须加时由一人一次改完，另开分支 |
| 同时改 `SolverAdapter` 签名与所有调用点 | 编译面广，容易半合入 | A0 一次改完；B/C 只通过钩子/新增方法 |
| 同时改 `contract-freeze` + openapi + controller + FE types | 单向依赖，任何一处先改都会漂移 | W1 串行落档；FE 用桩并行 |
| 同时改 `application.yml` | 单文件合并冲突，且配置影响运行时 | 配置管家一次一个车道；键前缀隔离 |
| 同时改 `pom.xml` / `deploy/**` | 制品/部署强一致 | W9 独占 |
| 同时改 `ScheduleProblemAssembler` + `ScheduleResultWriter` | 任务计数/冻结/覆盖语义在同一链路 | A1 独占；C 用装饰器；B 不改 |
| 同时改 `SolveTaskLifecycleService` 状态机 | 状态/取消/重试耦合 | A1 先合；A0 已加通用钩子；B2 只实现接口 |

**“先合契约、再并行实现”的拆法**

1. Wave 0：W1 冻结 C50–C53 形状 + OpenAPI 桩；A0 加钩子/槽位；A3 固化题面格式。
2. Wave 1：A1/B1/C1 各自在新增包/新增类里实现，不碰对方文件；FE 用 mock 并行。
3. Wave 2：B2/C2 接 API；A1 指标接前端（可选）。
4. Wave 3：W9 构建/部署/演示；全链路回归。

**质量闸**

- 每个车道自测 + 相邻车道契约测试；共享文件变更需 owner 书面同意。
- 合并顺序：W1 → A0 → (A1 ∥ B1 ∥ C1) → (B2 ∥ C2) → W9。
- 每个 PR 必须写“本卡改动文件清单”，且与 `git diff --name-only` 完全一致；不一致直接打回。
- 禁止“顺手重构”：A 不改 B 的归因类，B 不改 A 的 portfolio；需要重构先立卡。

### 5.7 合并节奏与集成点

| 波次 | 合并内容 | 集成验证 | 不通过则 |
|---|---|---|---|
| Wave 0 | W1 契约桩、A0 解耦、A3 基线 | A0 双线程分数隔离；W1 YAML/控制器路径一致；A3 基线可跑 | 不进入 Wave 1 |
| Wave 1 | A1、B1、C1、FE 桩 | A1 42 题；B1 6 不可行例；C1 10 CTP 例；FE mock 三态 | 对应车道回退，不阻塞其它车道 |
| Wave 2 | B2、C2、FE 联调 | 失败任务可解释；CTP 零假承诺；契约字段一致 | 关闭开关，保留后端能力 |
| Wave 3 | W9 制品/部署 | 制品 SHA、health、Flyway、18085 回退演练 | 恢复旧 jar |

### 5.8 每车道回报模板（防止“只列本卡改动”）

```markdown
## 回报：<车道>/<卡号>

### 1. 改动范围
- 声明：本卡只改以下文件（与 `git diff --name-only` 一致）
- 文件清单（逐行）：
  | 文件 | 类型（独占/预约/只读） | 改动摘要 |
- 未改但受影响的共享文件：<列出并说明为何不改>

### 2. 契约
- 使用/新增的 C 号：
- OpenAPI 是否已由 W1 落档：
- 前端桩/mock 是否已对齐：

### 3. 验证
- 跑过的测试/命令（不写 package）：
- 关键结果（分数/时延/用例数）：
- 未跑/跳过及原因：

### 4. 构建/运行时
- 是否构建：是/否；构建令牌 owner：
- 是否占用共享实例：是/否；登记表字段：
- 制品 SHA（如构建）：

### 5. 风险与遗留
- 已知风险：
- 需要其它车道配合的事项：
```

---

## 6. 风险清单

| # | 风险 | 触发 | 影响 | 缓解 | Owner |
|---|---|---|---|---|---|
| 1 | Portfolio 在大题上更慢/OOM | 3000 任务默认开 3 候选 | 时延翻倍、JVM 崩 | gating >800 单候选；按堆算 N；准入降级 | A1 |
| 2 | 静态权重改线程本地后分数漂移 | `activate`/`remove` 时序错 | 软分/归因不一致 | A0 金样；`JustificationScoreConsistencyTest`；双线程测试 | A0 |
| 3 | 线程本地泄漏 | 线程池复用未 remove | 下一个任务用错权重 | finally `remove()`；禁止 Inheritable；压测 | A0 |
| 4 | 取消不能真正停止 | 用户点取消 | CPU 继续跑到 timeout | 文案“已取消，收尾中”；预算上限；不承诺秒停 | A1 |
| 5 | 锁竞争误判重试 | `SOLVER_BUSY` 未设原因 | 30s 重试风暴 | A0 设 `SOLVER_BUSY`；重试白名单排除 | A0 |
| 6 | 重试阻塞队列 | 30s sleep 在唯一 worker | 所有任务停 30s | 改定时器；重试不占 worker | A1 |
| 7 | ATP 饿死 | CTP/后台 solve 抢槽 | ATP 50010/超时 | 预留槽；ATP 并发 1；缓存 | C1/A1 |
| 8 | Web 线程被 CTP 占满 | 同步 15s × 多请求 | 页面卡死 | 并发 1 + 429；后续异步化 | C1 |
| 9 | 证据误报“不可行” | 静态分析不完整 | 客户不信任 | 统一 `NO_FEASIBLE_SOLUTION_FOUND`；假设显式 | B1 |
| 10 | 反事实成本爆炸 | 3 候选 × 10s | 归因接口超时 | 总预算 30s；超预算 `OUT_OF_BUDGET` | B2 |
| 11 | `ConstraintMatchTotal` 内部 API 不稳 | spike 依赖 impl 包 | 升级即坏 | 只做 spike；主路径重放；版本锁定 2.8.0 | B1 |
| 12 | CTP 超 168h 无法表达 | 长交期询单 | 给不出日期或错判 | `OUT_OF_HORIZON`；W2 评估 horizon 参数化 | C1 |
| 13 | 订单级物料门太粗 | 多级 BOM 各件齐套日不同 | 承诺偏保守/偏乐观 | 文案声明假设；C54 每工序后续 | C1 |
| 14 | 物料数据缺失仍承诺 | 无预计到货/提前期 | 假承诺 | `UNKNOWN_MATERIAL_DATE` → `UNPROMISABLE` | C1 |
| 15 | CTP 缓存过期 | 库存/BOM/计划变更 | 旧日期 | epoch 失效 + TTL 5min | C1 |
| 16 | 契约号冲突 | 两车道同时要 C50 | 返工/漂移 | W1 发号；预留表；先落档先得 | W1 |
| 17 | OpenAPI/控制器/前端漂移 | 先写 controller 或 FE | 联调失败 | 单向依赖 + W1 检查 + 契约测试 | W1 |
| 18 | 共享 `target` 覆盖 | 非 W9 `package` | 运行中实例坏、不可恢复 | 构建令牌 + worktree + 禁止覆盖 | W9 |
| 19 | 18085 被误停 | 车道“空闲”判断 | 演示中断、无回退 | 登记 + owner 批准 + 停前检查 + 备份 | W9 |
| 20 | 共享 DB 迁移冲突 | 两车道改迁移/checksum | 启动失败 | V1 零 DDL；迁移只 W9 窗口；不改已应用文件 | W9 |
| 21 | 基准过拟合 | 按 caseId 调参/只看训练集 | 现网退化 | holdout + configHash + 全量报告 + 评审拒绝 | A3 |
| 22 | 墙钟终止导致跑分不稳 | 用 SLA 模式做逐位比较 | 假回归 | 确定性模式用步数；SLA 只比中位数/IQR | A3 |
| 23 | `application.yml` 合并冲突 | A/C 同时加键 | 配置丢失 | 配置管家；前缀隔离；一次一个 | W9/A1 |
| 24 | 部署挂错 jar | 标准名被覆盖 | 启动旧版本 | SHA 校验 + 清单 + 备份 | W9 |
| 25 | 多进程后内存幂等失效 | A2 直接上线 | 重复任务/取消丢失 | A2 前迁 DB/Redis 租约与幂等 | A2 |

---

## 7. 决策记录（ADR 摘要）

| ID | 决策 | 理由 | 复审条件 |
|---|---|---|---|
| D1 | A1 用同 JVM 多线程 portfolio，不先做多进程 | 最低成本、无题面传输、单任务内并行足够 | 多任务并发 SLA 不达标 → A2 |
| D2 | Portfolio 默认关闭，按任务规模灰度 | 避免现网性能回退；大题不适用 | 跑分证明 3000 题也稳定 |
| D3 | 不把 `moveThreadCount` 作为交付 | 社区版恒 NONE；避免假承诺 | 购买企业版并厂商验证 |
| D4 | B 主路径为“静态分析 + 谓词重放” | 社区版可维护；`ConstraintMatchTotal` 是内部 API | spike 证明稳定且社区可用 |
| D5 | “最小修复”= 启发式排序 + 求解验证 | 严格最小集不可得；避免过度承诺 | 企业版 indictment 可用 |
| D6 | CTP 验证再承诺，FAST 不作承诺 | 日期必须来自求解；避免假日期 | 求解时延稳定 <5s 可放宽候选数 |
| D7 | CTP V1 168h + 订单级物料门 | 模型现状；避免 record 大改 | 需要长交期/每工序齐套 → 新契约 |
| D8 | A/B/C V1 零 DDL，复用 JSONB | 避开迁移串行点；现有字段足够 | 审计/查询性能不足 → V15 |
| D9 | W1 单写契约；C50–C55 预留 | 避免编号冲突与漂移 | 契约量增大后设契约 Bot/CI 校验 |
| D10 | 只并行两条时选 A+B | 信任闭环；共用钩子一次接缝 | W1 无法冻结 C51/C52 → A+C |

---

## 8. 附录：只读核对清单

以下为本文写作时的只读确认项（未运行求解/打包/部署）：

- `TimefoldSolverAdapter`：`SOLVER_LOCK` L108、等待 5s L113、`tryLock` L162–L170、`activate` L174、`finally disable/unlock` L225–L226、社区版探测 L135–L139、`resolveMoveThreads` L450–L469、`scoreCurve` 仅日志 L192–L197。
- `SoftConstraintWeights`：静态 `ACTIVE` L23、`activate` L75、`disable` L83。
- `ApsConstraintProvider`：`isEnabled` L44、`orderNotBefore` L38、资源分组 L84–L99。
- `SolveTaskLifecycleService`：单 worker/队列 L197–L204、重试 sleep L541–L551、`finishOutcome` L656–L671（仅写任务行，无归因）。
- `ScheduleService`：装配 L412、求解 L444、完整性守卫 L469–L476、成功才持久化 L479、失败 L496 不写归因。
- `ScheduleResultWriter`：`@Transactional` / `persist` L68–L69。
- `AtpService`：同步 ACCURATE L206–L227、默认 10s、172h 视野。
- `TimefoldModelBuilder`：168h L56、起始网格 L520。
- `application.yml`：无 async 开关 L55、队列 32 L61、重试 L66–L70；`application-dev.yml` Hikari 10 L14–L18。
- `docs/api/contract-freeze.md`：W1 单写 L1961–L1963；社区版无 `ConstraintMatchTotal` L1363；C49 尾号 L2962–L2964。
- `docs/api/openapi/README.md`：先 YAML L36–L39。
- `docs/testing/m2-build-incidents.md`：制品覆盖 L97–L107；thin jar L48；package 覆盖运行实例 L75–L79。
- `docs/perf/m2-qa-01-perf.md`：300 任务 300s L102–L107；500/3000 未完成；CH 167.8s L137–L146；`moveThreadCount` 无效 L145–L146。
- `db/migration/V1__init.sql`：`constraint_justification` L781–L802（`implicated_facts JSONB array`）、`solve_task.metrics`、`audit_log` L836+。
- `aps-frontend/src/mocks/index.ts`：`VITE_ENABLE_MOCK` L6–L15。
- `deploy/backend/Dockerfile`：`MaxRAMPercentage=75` L18；`docs/deploy/docker-compose.md`：运行时镜像挂载 jar L8–L11。
- `scripts/stack-bundle.ps1`：SHA256 清单 L71–L133。

**不属于本轮范围、但已发现的文档漂移（只记录，不改）**

- `SolveContractController` 的 NL-to-DSL 注释仍写“assembler 传 List.of()、C47 未接线”，但 `ScheduleProblemAssembler` 实际已在 L356 读取 `CompiledConstraintSpec`；状态描述过期。由 W1 在契约维护时顺带更正，不在本卡修改。
- `docs/dev/architecture.md#L206` 写“代码默认 async-enabled=true、yml 未配”，但 `application.yml#L55` 注释“不再提供 async-enabled 部署开关”；以代码/yml 为准，A 的负载模型按始终异步设计。
