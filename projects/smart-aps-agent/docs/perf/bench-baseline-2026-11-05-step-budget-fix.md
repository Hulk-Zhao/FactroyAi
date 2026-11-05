# A3 基准骨架 — 基线记录（容量修订版，步数 34 题 / 墙钟 42 题，seed=0；另 5 题 stress 参考默认不执行）

> 车道：Wave 0 · A3「基准骨架」。本文件只记录测试侧基线与口径，不改变主代码。
>
> **这是容量修订后的新基准，不是“修好旧基准”。** 题面（`case.json`）已变，
> `fingerprint` 必然变化，旧的分数不可继续沿用。
>
> - **步数基线（回归主口径，可复现）**：
>   [bench-baseline-step.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline-step.json)
>   （SHA256 `5D397C789FAE9046E905C4617066C488F555318216FD34D20A2C65B68C92C62A`）。
>   34 题（小 19 + 中 15），SMALL 1,000 步 / MEDIUM 10,000 步，`terminationRequest=STEP_COUNT`。
> - 第二次连跑副本（确定性证据）：
>   [bench-baseline-step-run2.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline-step-run2.json)
>   （SHA256 `5605C43DC3AB2688164D515316628FD2AECBCC3FD4370AAA17221804DE5445C6`）。
> - **墙钟基线（对照口径，保留）**：
>   [bench-baseline.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline.json)
>   （SHA256 `B227ADC4630A6CCDC3B9F3432D1FC3DE8652F06FC97F17D459F56384A3FBD2E8`）。42 题，SLA 3s/5s/8s。
> - 两种口径的分数 **不可互比**：预算一个是步数、一个是秒；对照只用于看“同题在不同预算下大概到哪里”。

## 0. 作废声明与容量修订（2026-11-05）

### 0.1 作废声明

**此前的 42 题墙钟基线作废，原因：5 题容量错误（S08 / M03 / M09 / M13 / L02）+ S08 辅助资源窗口缺陷。**
原始题面已保留为 stress-reference（`S08-stress` / `M03-stress` / `M09-stress` / `M13-stress` / `L02-stress`），
可用于对照改小前后的求解行为差异；它们默认不进入回归门禁，见第 6 节。
旧文件不删除，按日期归档为历史对照（内容为修订前快照）：

| 归档文件 | 原文件 | 说明 |
| --- | --- | --- |
| [bench-baseline-2026-11-05-pre-capacity-fix.md](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline-2026-11-05-pre-capacity-fix.md) | `bench-baseline.md` | 修订前文档（墙钟 + 步数口径说明） |
| [bench-baseline-wall-2026-11-05-pre-capacity-fix.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline-wall-2026-11-05-pre-capacity-fix.json) | `bench-baseline.json` | 修订前墙钟 42 题报告 |
| [bench-baseline-step-2026-11-05-pre-capacity-fix.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline-step-2026-11-05-pre-capacity-fix.json) | `bench-baseline-step.json` | 修订前步数 34 题（MEDIUM 2,000 步） |
| [bench-baseline-step-run2-2026-11-05-pre-capacity-fix.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline-step-run2-2026-11-05-pre-capacity-fix.json) | `bench-baseline-step-run2.json` | 修订前第二次连跑 |

旧基线的 5 题 hard 分别为：S08 `-3`、M03 `-51`、M09 `-72`、M13 `-1,931`、L02 `-5,714`（墙钟）；
步数 2,000 步下为 `-6 / -69 / -105 / -289`（L02 不在步数口径内）。

### 0.2 容量核算口径

- `DAY_SHIFT` = 8h（8:00–16:00），`START_TIME_STEP_HOURS = 1`，时间网格按整点。
- 任务块 = 600s 静态换型 + `qty × 600s` 加工：
  - `qty=12` → **2h10** 块；1 小时网格下一个 8h 班次只能放 **2 个**（8:00、11:00；第三个 14:00–16:10 越界），每资源每周 **14 个**；
  - `qty=6` → **1h10** 块；一个 8h 班次可放 **4 个**（8:00、10:00、12:00、14:00），每资源每周 **28 个**。
- 判定：`任务数 / 主资源数 ≤ 每资源每周上限`，且班次内可排块数 ≥ 每日所需。
  只有 `calendar=DAY_SHIFT` 的题受这个上界约束；`calendar=NONE` 的资源全天可用，
  不受 8h 班次限制（M01/M02/M04–M08/M10 属此类，见第 7 节）。

### 0.3 逐题裁定与结果

5 题全部选择 **第一类：改题面容量使其真正可行**（未改期望、未降级参考题）。
改完后均以墙钟口径实测 `hard=0`；M03/M09/M13 另在步数口径 10,000 步实测 `hard=0`。

| 题目 | 原需求 / 主资源 | 每资源需要 | 原上限 | 原问题 | 处理与理由 | 改后实测（墙钟） |
| --- | ---: | ---: | ---: | --- | --- | --- |
| S08 | 6 / 2 | 3.0 | 14 | 数量不紧，但辅助资源只有 day0 一段 16h；day0 主资源仅 4 个位（2 资源 × 2 块），6 个任务放不下 | **修生成器窗口**：新增 `secondaryWindowDays: 7`，辅助资源改为 7 天 × 16h（主资源与任务块不变） | hard=0 / soft=-20,500 / assigned 6/6 |
| M03 | 120 / 6 | 20 | 14 | **超限** | 工序 6→3、`qty` 12→6：60 个 1h10 块，60/6=10 ≤14，且每资源每班次 ≤4 块（主资源数不变，仍 6） | hard=0 / soft=-179,285 / assigned 60/60 |
| M09 | 168 / 9 | 18.7 | 14 | **超限** | 工序 6→3、`qty` 12→6（1h10 块）：84/9=9.33 ≤14（主资源数不变，仍 9） | hard=0 / soft=-726,740 / assigned 84/84 |
| M13 | 270 / 12 | 22.5 | 14 | **超限** | 工序 6→2、`qty` 12→6（1h10 块）：90/12=7.5 ≤14（主资源数不变，仍 12） | hard=0 / soft=-1,664,080 / assigned 90/90 |
| L02 | 480 / 20 | 24 | 14 | **超限** | 工序 6→2、`qty` 12→6（1h10 块）：160/20=8 ≤14 | hard=0 / soft=-3,075,715 / assigned 160/160 |

> 选择理由：仅缩小任务块（延长班次也已试过）仍因 6 道工序的 FS 链在前 7 天内排不满；
> 把任务块缩到 1h10（`qty=6`）并降低工序数后，5 题才真正达到 `hard=0`。这里“容量”的
> 分母是题面原有的主资源数（M09=9、M13=12，不是 6），改后均不增加资源。
> 期望保持 `SOLVED / hardScore: 0`，因为这些题现在确实可达。

### 0.4 S08 的窗口缺陷（单独说明，未悄悄改掉）

S08 的辅助资源窗口原先只有 day0 一段 8:00–24:00（16h）。这是 `BenchProblemFactory` 的生成缺陷：
主资源按 `DAY_SHIFT` 生成 7 天窗口，辅助资源却只生成 1 天，导致 6 个任务挤在 day0 的 4 个主资源位上，
无论怎么排都硬违约。本次不是把它“标成单日特例题”，而是按其他题的多日口径补齐：
新增 `secondaryWindowDays`（默认 1，S08 显式取 7），辅助资源窗口变为 7 天 × 16h。
改动在题面 `case.json` 中可见，并在下方 0.5 指纹表中体现。

### 0.5 新旧指纹对照（5 题）

| 题目 | 旧 fingerprint | 新 fingerprint |
| --- | --- | --- |
| S08-smoke-all-features | `a4600d71fabf4c2a1740ebef0d8e889b23b2f2853e821a6c64fdd3c87328520f` | `6dcf00dfcf32d5e033540c887929cf8ca2b2120f739a0e55e2606c0658e0044e` |
| M03-feasible-20o-calendar | `de2ef5ec1e74498952b981225672174c02a14c04b14c13704ca328ae10a45e4f` | `1983f532e13afd5e40de14d4640fb89371f3ceabfab542bdaee7ebeecd380854` |
| M09-feasible-28o-calendar | `c568ed208118658e8ee4ad230c2e111c8caf9d7a2620d8ff46ee9ea9be598da0` | `fba4c414dc1527b95d9f105ee94a5d7bcb7c931607d8461e177b6d6799b7bab8` |
| M13-hard-45o-calendar | `e2e7cf365d49b23d2f8b40d3654cb5cb79d4bc0a1e036bd03ad497bd545b9a0c` | `7f76ece1a871831c7a98367a5202be2d2e200982de4e58f6fb8443e2fcff2e7e` |
| L02-hard-80o-calendar | `52b5412c2c9ce9457892139f4c505025f7552228fbca83f3300a5e7fd456c346` | `0a7b62b8c54a9ecf19cb3e5c2bb142e7c0dfa7e9f57dbc0c9e404d34419bcc87` |

其余 37 题指纹不变。

## 结论

- 题量：回归门禁共 42 题，按档位 **小 20 / 中 15 / 大 7**；按类别 8 smoke、12 feasible-medium、8 hard-feasible、
  6 infeasible、4 incremental、4 large-ref。另保留 5 题容量修订前的原始题面（`*-stress`，
  `category=stress-reference`），默认不执行，只作规模参考（第 6 节）。
- **5 题容量错误已修复**，墙钟口径下 S08/M03/M09/M13/L02 全部 `hard=0`；
  M03/M09/M13 在步数口径 10,000 步下也全部 `hard=0`（见 5.3）。
- **步数模式连跑两次，34 题逐题 `hard / soft / improvements / assignedTaskCount / feasible /
  stepCount / fingerprint / problemDigest / hardViolationCodes` 完全一致（差异数 0）**。
  固定 seed + 步数终止下分数可复现；仍随墙钟变化的只有 `durationMs` 与 `timeToBestMs`，
  它们不进入回归门禁。两次都一致的实测证据见第 5.4 节。
- 步数基线一次全量 **34 题 / 137,134 ms**（含每题建模，不含 JVM 启动）：小档 1,945 ms、
  中档 133,843 ms。第二次 129,873 ms（只差墙钟开销）。
  MEDIUM 从 2,000 步上调到 10,000 步，原因是容量修订后的 M03/M09/M13 在 2,000 步时
  仍为 `-3/-6/-13`，10,000 步才稳定到 `hard=0`。
- **步数基线不覆盖 LARGE**：没有可用的步数预算（L04 8,000 步 165.9s、L05 1,500 步 544.6s、
  L07 4,000 步 >10 min），且步数不足以完成构造启发式时局部搜索会抛
  `IllegalStateException`。LARGE 仍以墙钟基线为参考（本次 L02 已 `hard=0`，其余大档仍只作时延参考）。
- **S20 在步数模式不可终止**：卡死在单次计分内部（Bavet `MIN_MAKESPAN` 的 `min/max` 聚合），
  100/200/500 步均在 30s 守护外被强杀；`stepCountLimit` 无法中断计分内部循环。
  **本次保持排除，未因其他车道可能已修复而悄悄加回**；等 A1 明确回报修复后再另行评估。
- 墙钟基线保留为对照：固定 seed 仍不可逐次复现（本次 M11 `hard=-448`，旧快照为 `-380/-447/-761`），
  故回归门禁改用步数模式。
- **最大口径差异**：本入口直接构造 Timefold 求解器并使用 `BestSolutionChangedEvent`，
  不经过 `TimefoldSolverAdapter`；`SolveMetrics.scoreHistory` 仍为空，`timeToBest` 也未在
  生产 API 中提供。生产侧需 A0 补齐。

## 1. 题量分布与档位判定口径

> 下表只统计 42 题回归门禁题；5 题 `*-stress` 原始题面不计入档位/类别统计，
> 也不参与任何聚合指标（见第 6 节）。

| 档位 | 题数 | 订单数 / 任务数 | 判定口径 |
| --- | --- | --- | --- |
| SMALL | 20 | 1–3 单 / 2–15 任务 | 订单 ≤ 3 **且** 任务 ≤ 15 |
| MEDIUM | 15 | 10–50 单 / 60–300 任务 | 订单 ≤ 50 **且** 任务 ≤ 350（不含 SMALL） |
| LARGE | 7 | 60–500 单 / 160–3000 任务 | 订单 > 50 **或** 任务 > 350 |

档位由 `BenchProblemFactory.classify(orderCount, taskCount)` 统一计算，跑分报告同时记录
`declaredSizeClass` 与 `computedSizeClass`，不一致即视为题面配置错误（本次 42/42 一致）。

### 类别分布

| 类别 | 题数 | 说明 | 预算（timeout / unimproved） |
| --- | --- | --- | --- |
| SMOKE | 8 | 单特性与全特性小样，验证题面/约束链路 | 3s / 2s |
| FEASIBLE_MEDIUM | 12 | 中档可硬可行样例题 | 3s（小）/ 5s（中） / 2s / 3s |
| HARD_FEASIBLE | 8 | 紧交期、日历、换型、依赖叠加 | 5s（中）/ 8s（大） / 3s / 5s |
| INFEASIBLE | 6 | 冻结重叠、日历缺口、冻结越窗、辅助资源不足、horizon 溢出、缺路由 | 3s / 2s |
| INCREMENTAL | 4 | 部分任务带初始解（非冻结）+ 新单 | 3s / 2s |
| LARGE_REF | 4 | 大档容量/时延参考，不设通过线 | 8s / 5s |

## 2. 题面固化方式与指纹

- 目录：`aps-solver/solver-timefold/src/test/resources/bench/cases/<caseId>/case.json`。
- 单文件字段：`caseId / kind / sizeClass / tags / seed / budget{timeoutSeconds,
  unimprovedSecondsSpentLimit, maximumStepCount} / problem{...} / expectation{...}`。
- 物化：`BenchProblemFactory` 由 JSON 规格确定性生成 `ScheduleProblem`；
  UUID 由 `caseId + 序号` 派生（`UUID.nameUUIDFromBytes`），时间锚点固定为
  `2026-01-05T00:00:00`，不读系统时钟，不依赖数据库。全流程内存题面，用完即弃，未建临时库。
- 本版新增 `problem.secondaryWindowDays`（辅助资源窗口天数，默认 1；S08 取 7）与
  `problem.shiftHours`（`DAY_SHIFT` 班次小时，默认 8；本版 5 题均使用默认 8，未启用非 8h 班次）。
- 指纹定义：
  - `fingerprint = SHA256(canonical(case.json))`，覆盖问题规格、预算、seed、期望；
  - `problemDigest = SHA256(canonical(物化问题))`，覆盖订单/工序/资源/日历窗口/
    冻结与初值/换型矩阵/锚点；生成器代码改动导致物化结果变化时同样换指纹；
  - `caseFileSha256` 保留原始字节摘要，用于识别只改缩进/键序的无关变更。
- 三者在 `bench-baseline.json` 的每例记录中都有；跑分时逐题在 stdout 打印完整 `fingerprint`。
- 完整指纹清单见附录 A。

## 3. 跑分命令与总耗时

### 3.1 规范入口（必须带 `-am`）

```powershell
mvn -o -B -ntp -am -pl aps-solver/solver-timefold `
    -Dtest=SolverBenchRunner -Dsurefire.failIfNoSpecifiedTests=false `
    -Daps.bench.caseFilter=L01 `
    -Daps.bench.report=target/bench/a0-entry-verify.json test
```

- **`-pl` 不带 `-am` 会解析到 `~/.m2` 里的旧 `solver-api` 快照**，实测报
  `NoSuchMethodError: SchedulingOrder.<init>`。`-am` 让 `aps-common` / `solver-api` 走本地源码构建。
- `-Dsurefire.failIfNoSpecifiedTests=false`：`SolverBenchRunner` 类名不以 `Test` 结尾，
  该参数避免“只指定了测试类但没匹配到”时构建失败。
- 在 PowerShell 下所有 `-D...` 参数都要加引号（`"-Dsurefire.failIfNoSpecifiedTests=false"`），
  否则带 `.` 的参数会被拆成两段并报 `Unknown lifecycle phase`。

### 3.2 步数基线（回归主口径）

```powershell
mvn -o -B -ntp -am -pl aps-solver/solver-timefold `
    -Dtest=SolverBenchRunner -Dsurefire.failIfNoSpecifiedTests=false `
    -Daps.bench.termination=STEP `
    -Daps.bench.stepCounts=SMALL:1000,MEDIUM:10000 `
    -Daps.bench.caseExclude=L,S20 `
    -Daps.bench.report=target/bench/bench-report-step.json test
```

- 步数模式下**只**给 `stepCountLimit`，不再叠加 `spentLimit` 或
  `unimprovedSecondsSpentLimit`；报告 `terminationRequest` 回显 `STEP_COUNT`。
- **`unimprovedSecondsSpentLimit` 与 `maximumStepCount` 不要同时给**：步数模式优先。
  墙钟模式叠加无改进上限时，无改进会先于墙钟触发。
- `-Daps.bench.caseExclude=L,S20` 按 `caseId` 前缀排除：LARGE 无可用步数预算（见 5.5），
  S20 在计分内部死循环（见 5.5）；`category=stress-reference` 的 5 题默认也会跳过，
  报告里 `excludedCases` 会列出实际排除的 13 题（LARGE 7 + S20 + stress 5）。
- 本次全量：**34 题 / 137,134 ms**（`totalDurationMs`，含每题建模与求解，不含 JVM 启动）；
  档位小计：小 1,945 ms、中 133,843 ms。第二次连跑 129,873 ms。
- 报告落盘：`aps-solver/solver-timefold/target/bench/bench-report-step.json`，
  归档副本 `docs/perf/bench-baseline-step.json` 与 `bench-baseline-step-run2.json`。
  注：归档的这两份 JSON 生成时 stress 题尚未加入，故其 `excludedCases` 只有 8 项；
  42 题门禁题的题面指纹与分数不受影响，按当前命令复跑只会多列出 5 个 stress 排除项。

### 3.3 墙钟基线（对照口径）

```powershell
mvn -o -B -ntp -am -pl aps-solver/solver-timefold `
    -Dtest=SolverBenchRunner -Dsurefire.failIfNoSpecifiedTests=false `
    -Daps.bench.report=target/bench/bench-report.json test
```

- 42 题门禁题全部跑（`category=stress-reference` 默认跳过），预算取每题
  `case.json` 的 `timeoutSeconds` / `unimprovedSecondsSpentLimit`
  （小 3s/2s、中 5s/3s、大 8s/5s）；**本次全量 177,942 ms**。
- 该口径 **不可逐次复现**，只保留作对照（见第 7 节）。

### 3.4 历史绕行说明

A0 修复 `SoftWeightsConcurrencyTest`（`BLOCKED_MIN_MILLIS` 已不存在）之前，
`test-compile` 被该测试阻塞，曾用 `mvn compile` + `dependency:build-classpath` +
手工 `javac` bench 子包 + `java -cp ...` 运行。**该绕行已不再需要**；
若将来测试编译再次被阻塞，可沿用 `java -cp "target/classes;target/test-classes;
..\solver-api\target\classes;..\..\aps-common\target\classes;<bench-cp.txt>"
com.hengce.aps.solver.timefold.bench.SolverBenchRunner`，但它不是规范入口。

### 本次基线对应的源码状态

- 分支 `main`，HEAD `8fe9249e99b1de5ac7ef42612bbb2741bcfd3b3c`
  （2026-11-04 19:22:55 +0800，`fix: M3 安全加固…与契约状态对账`）。
- **有未提交改动**：`git status --porcelain` 264 项（本次基线时统计）。其中包含 A0/A1 车道的
  `TimefoldSolverAdapter`、`TimefoldModelBuilder`、`ScheduleProblem`、`SchedulingOrder`、
  `SetupMatrixEntry`、`SoftConstraintWeights` 等主代码改动，也包含 A3 的 `bench/`、
  5 个 `case.json` 与 `docs/perf/bench-baseline.*`。
  跑分报告的 `headSha` 为 `unset`（本地工作树快照）；**该快照不能当作“某个已提交版本的基线”复用**，
  A0/A1 合入后必须重建基线。
- 主代码只读：A3 未修改任何 `src/main` 文件；只改了
  `solver-timefold/src/test/**`（bench 资源与 runner javadoc/参数读取）与 `docs/perf/**`。
- 运行环境隔离：18085 进程未重启（PID 32120 只读），`aps_local` 未连接，`aps_demo` 未写入；
  题面全部内存物化，未建临时库。

## 4. 墙钟基线（对照口径，seed=0，SLA 3s/5s/8s）

机器：AMD Ryzen 7 9800X3D（8C/8T）、64 GB、Windows 11 26200、Temurin JDK 21.0.13、
`-Xmx2g`、Timefold 2.8.0 community；软约束权重使用 `SoftConstraintWeights.defaults()`
（20/15/10/10/10/10/15/10），`configHash = sha256:2062091e105c63037324bfd5773127c2fafaba5aaaf16316264434198949472a`
（仅硬约束对照为 `sha256:7edc3bb261ddf163f6a80bfcb99f3bf13e95a1179db3ef1a0a415a015e82cb27`，本次未用）。

| 档位 | 题数 | 总耗时 | 耗时中位数 | hard 中位数 | soft 中位数 | timeToBest 中位数 | hard=0 题数 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SMALL | 20 | 45.2 s | 2,214 ms | 0 | -10,830 | 212 ms | 15/20 |
| MEDIUM | 15 | 75.2 s | 5,009 ms | -4 | -2,352,015 | 4,787 ms | 5/15 |
| LARGE | 7 | 56.2 s | 8,025 ms | -1,251 | -10,670,175 | 5,101 ms | 1/7 |

### 逐题结果

`TTB` = timeToBest（毫秒）；`assigned` = 已赋值任务数；hard=0 才算硬可行。

| 题目 | 类别 | 任务 | assigned | hard | soft | 耗时 ms | TTB ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| S01-smoke-basic | SMOKE | 6 | 6 | 0 | -8,100 | 2,981 | 978 |
| S02-smoke-setup-matrix | SMOKE | 6 | 6 | 0 | -8,500 | 2,474 | 472 |
| S03-smoke-explicit-deps | SMOKE | 6 | 6 | 0 | -8,100 | 2,386 | 384 |
| S04-smoke-calendar | SMOKE | 6 | 6 | 0 | -17,700 | 2,153 | 152 |
| S05-smoke-frozen | SMOKE | 6 | 6 | 0 | -8,100 | 2,506 | 505 |
| S06-smoke-secondary | SMOKE | 6 | 6 | 0 | -9,900 | 2,350 | 348 |
| S07-smoke-priority | SMOKE | 6 | 6 | 0 | -8,060 | 2,099 | 98 |
| S08-smoke-all-features | SMOKE | 6 | 6 | **0** | -20,500 | 2,141 | 140 |
| S09-feasible-small-setup | FEASIBLE_MEDIUM | 9 | 9 | 0 | -11,520 | 2,405 | 403 |
| S10-feasible-small-priority | FEASIBLE_MEDIUM | 9 | 9 | 0 | -11,080 | 2,448 | 447 |
| S11-incremental-basic | INCREMENTAL | 6 | 6 | 0 | -8,100 | 2,206 | 205 |
| S12-incremental-setup | INCREMENTAL | 9 | 9 | 0 | -11,520 | 2,482 | 482 |
| S13-incremental-calendar | INCREMENTAL | 6 | 6 | 0 | -17,600 | 2,222 | 219 |
| S14-incremental-frozen | INCREMENTAL | 6 | 6 | 0 | -7,600 | 2,275 | 274 |
| S15-infeasible-frozen-overlap | INFEASIBLE | 6 | 6 | -2 | -10,580 | 2,009 | 8 |
| S16-infeasible-calendar-gap | INFEASIBLE | 15 | 15 | -117 | -23,690 | 2,015 | 13 |
| S17-infeasible-frozen-outside-calendar | INFEASIBLE | 6 | 6 | -1 | -39,380 | 2,010 | 8 |
| S18-infeasible-secondary-shortage | INFEASIBLE | 8 | 8 | -7 | -17,260 | 2,011 | 11 |
| S19-infeasible-horizon-overflow | INFEASIBLE | 10 | 10 | -5 | -1,622,100 | 2,015 | 14 |
| S20-infeasible-missing-routing | INFEASIBLE | 2 | 2 | 0 | -3,380 | 2,009 | 7 |
| M01-feasible-10o-8op | FEASIBLE_MEDIUM | 80 | 80 | 0 | -1,421,505 | 5,008 | 4,916 |
| M02-feasible-15o-setup | FEASIBLE_MEDIUM | 120 | 120 | -4 | -2,017,095 | 5,008 | 4,787 |
| M03-feasible-20o-calendar | FEASIBLE_MEDIUM | 60 | 60 | **0** | -179,285 | 5,003 | 4,946 |
| M04-feasible-25o-setup3fam | FEASIBLE_MEDIUM | 150 | 150 | -1 | -2,352,015 | 5,010 | 4,783 |
| M05-feasible-30o-priority | FEASIBLE_MEDIUM | 150 | 150 | 0 | -1,915,055 | 5,008 | 4,690 |
| M06-feasible-12o-secondary | FEASIBLE_MEDIUM | 96 | 96 | -6 | -2,741,955 | 5,008 | 4,577 |
| M07-feasible-18o-deps | FEASIBLE_MEDIUM | 126 | 126 | -1 | -2,117,145 | 5,009 | 4,985 |
| M08-feasible-22o-setup | FEASIBLE_MEDIUM | 176 | 176 | -21 | -3,774,625 | 5,009 | 4,927 |
| M09-feasible-28o-calendar | FEASIBLE_MEDIUM | 84 | 84 | **0** | -726,740 | 5,006 | 4,781 |
| M10-feasible-30o-tightdue | FEASIBLE_MEDIUM | 240 | 240 | -358 | -18,709,360 | 5,011 | 5,005 |
| M11-hard-30o-8op | HARD_FEASIBLE | 240 | 240 | -448 | -18,364,785 | 5,013 | 4,998 |
| M12-hard-40o-setup | HARD_FEASIBLE | 280 | 276 | -790 | -20,466,225 | 5,015 | 179 |
| M13-hard-45o-calendar | HARD_FEASIBLE | 90 | 90 | **0** | -1,664,080 | 5,006 | 4,917 |
| M14-hard-50o-tightdue | HARD_FEASIBLE | 300 | 272 | -1,011 | -26,978,335 | 5,040 | 252 |
| M15-hard-35o-all | HARD_FEASIBLE | 280 | 280 | -802 | -21,159,660 | 5,020 | 217 |
| L01-hard-60o | HARD_FEASIBLE | 360 | 323 | -1,090 | -24,108,150 | 8,016 | 381 |
| L02-hard-80o-calendar | HARD_FEASIBLE | 160 | 160 | **0** | -3,075,715 | 8,009 | 7,881 |
| L03-hard-100o | HARD_FEASIBLE | 600 | 305 | -1,251 | -25,928,140 | 8,022 | 1,531 |
| L04-ref-100o-600t | LARGE_REF | 600 | 306 | -1,247 | -14,287,015 | 8,025 | 1,547 |
| L05-ref-150o-900t | LARGE_REF | 900 | 235 | -1,255 | -10,670,175 | 8,032 | 5,101 |
| L06-ref-250o-1500t | LARGE_REF | 1,500 | 0 | -1,500 | 0 | 8,041 | 8,034 |
| L07-ref-500o-3000t | LARGE_REF | 3,000 | 0 | -3,000 | 0 | 8,064 | 8,054 |

## 5. 步数基线（回归主口径，确定性）

### 5.1 口径

- 终止：`maximumStepCount > 0` 时 **只** 给 `stepCountLimit`，不叠加 `spentLimit` 与
  `unimprovedSecondsSpentLimit`；报告 `terminationRequest=STEP_COUNT`，`stepCount` 回显预算。
- 步数预算：**SMALL 1,000 步 / MEDIUM 10,000 步**；LARGE 不设（见 5.5）。
  MEDIUM 由 2,000 上调：容量修订后的 M03/M09/M13 在 2,000 步时仍为 `-3/-6/-13`，10,000 步才到 0。
- seed 固定 0；软权重 `SoftConstraintWeights.defaults()`，
  `configHash = sha256:2062091e105c63037324bfd5773127c2fafaba5aaaf16316264434198949472a`
  （每档汇总里也逐档写了同一个哈希）。
- 题面指纹与墙钟基线 **逐题一致**（34/34 相同）；两种口径只差终止方式与预算。

### 5.2 汇总

| 档位 | 题数 | 步数 | configHash | 总耗时 | 耗时中位数 | hard 中位数 | soft 中位数 | TTB 中位数 | hard=0 题数 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SMALL | 19 | 1,000 | `sha256:2062091e…472a` | 1.9 s | 82 ms | 0 | -17,260 | 46 ms | 14/19 |
| MEDIUM | 15 | 10,000 | `sha256:2062091e…472a` | 133.8 s | 6,313 ms | -4 | -2,488,840 | 6,291 ms | 5/15 |
| LARGE | 0 | — | — | — | — | — | — | — | — |

全量 **34 题 / 137,134 ms**。中档 5 题 hard=0（M03/M04/M05/M09/M13）；
其余中档题在预算结束时仍在改善，属收敛速度问题，不是容量不可行（见 7.7）。
墙钟 5s 同样只有 5/15 达到 hard=0，两者不可互比。

### 5.3 逐题结果（run 1）

`TTB` = timeToBest（毫秒）；`assigned` = 已赋值任务数；hard=0 才算硬可行。
S20 与 LARGE 的步数结果为空，原因见 5.5；它们的墙钟结果见第 4 节。

| 题目 | 类别 | 任务 | assigned | hard | soft | 耗时 ms | TTB ms | 改进次数 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| S01-smoke-basic | SMOKE | 6 | 6 | 0 | -10,500 | 500 | 490 | 9 |
| S02-smoke-setup-matrix | SMOKE | 6 | 6 | 0 | -10,900 | 169 | 164 | 12 |
| S03-smoke-explicit-deps | SMOKE | 6 | 6 | 0 | -10,500 | 106 | 101 | 9 |
| S04-smoke-calendar | SMOKE | 6 | 6 | 0 | -28,500 | 82 | 54 | 10 |
| S05-smoke-frozen | SMOKE | 6 | 6 | 0 | -9,300 | 73 | 45 | 8 |
| S06-smoke-secondary | SMOKE | 6 | 6 | 0 | -10,500 | 85 | 80 | 10 |
| S07-smoke-priority | SMOKE | 6 | 6 | 0 | -9,860 | 92 | 52 | 14 |
| S08-smoke-all-features | SMOKE | 6 | 6 | **0** | -33,700 | 122 | 21 | 6 |
| S09-feasible-small-setup | FEASIBLE_MEDIUM | 9 | 9 | 0 | -14,520 | 105 | 87 | 19 |
| S10-feasible-small-priority | FEASIBLE_MEDIUM | 9 | 9 | 0 | -14,080 | 93 | 90 | 21 |
| S11-incremental-basic | INCREMENTAL | 6 | 6 | 0 | -35,910 | 52 | 40 | 31 |
| S12-incremental-setup | INCREMENTAL | 9 | 9 | 0 | -27,720 | 85 | 53 | 21 |
| S13-incremental-calendar | INCREMENTAL | 6 | 6 | 0 | -30,900 | 53 | 46 | 22 |
| S14-incremental-frozen | INCREMENTAL | 6 | 6 | 0 | -24,300 | 38 | 28 | 10 |
| S15-infeasible-frozen-overlap | INFEASIBLE | 6 | 6 | -2 | -10,580 | 49 | 10 | 2 |
| S16-infeasible-calendar-gap | INFEASIBLE | 15 | 15 | -117 | -23,690 | 73 | 23 | 2 |
| S17-infeasible-frozen-outside-calendar | INFEASIBLE | 6 | 6 | -1 | -39,380 | 69 | 13 | 3 |
| S18-infeasible-secondary-shortage | INFEASIBLE | 8 | 8 | -7 | -17,260 | 67 | 25 | 2 |
| S19-infeasible-horizon-overflow | INFEASIBLE | 10 | 10 | -5 | -1,622,100 | 32 | 13 | 2 |
| M01-feasible-10o-8op | FEASIBLE_MEDIUM | 80 | 80 | -1 | -2,189,290 | 2,301 | 2,284 | 455 |
| M02-feasible-15o-setup | FEASIBLE_MEDIUM | 120 | 120 | -4 | -2,036,655 | 4,928 | 4,614 | 627 |
| M03-feasible-20o-calendar | FEASIBLE_MEDIUM | 60 | 60 | **0** | -615,180 | 1,366 | 1,103 | 399 |
| M04-feasible-25o-setup3fam | FEASIBLE_MEDIUM | 150 | 150 | 0 | -2,149,810 | 6,313 | 6,291 | 942 |
| M05-feasible-30o-priority | FEASIBLE_MEDIUM | 150 | 150 | 0 | -1,996,825 | 4,569 | 4,345 | 953 |
| M06-feasible-12o-secondary | FEASIBLE_MEDIUM | 96 | 96 | -4 | -2,488,840 | 15,310 | 14,768 | 520 |
| M07-feasible-18o-deps | FEASIBLE_MEDIUM | 126 | 126 | -3 | -2,218,795 | 4,013 | 3,966 | 822 |
| M08-feasible-22o-setup | FEASIBLE_MEDIUM | 176 | 176 | -14 | -3,226,175 | 8,699 | 8,583 | 1,103 |
| M09-feasible-28o-calendar | FEASIBLE_MEDIUM | 84 | 84 | **0** | -1,159,550 | 1,752 | 1,533 | 493 |
| M10-feasible-30o-tightdue | FEASIBLE_MEDIUM | 240 | 240 | -16 | -13,648,055 | 11,204 | 11,202 | 1,465 |
| M11-hard-30o-8op | HARD_FEASIBLE | 240 | 240 | -38 | -14,795,210 | 15,711 | 15,678 | 1,414 |
| M12-hard-40o-setup | HARD_FEASIBLE | 280 | 280 | -22 | -13,738,205 | 19,034 | 18,809 | 1,712 |
| M13-hard-45o-calendar | HARD_FEASIBLE | 90 | 90 | **0** | -2,610,280 | 1,437 | 1,319 | 533 |
| M14-hard-50o-tightdue | HARD_FEASIBLE | 300 | 300 | -48 | -19,341,820 | 16,982 | 16,944 | 1,578 |
| M15-hard-35o-all | HARD_FEASIBLE | 280 | 280 | -27 | -13,843,965 | 20,224 | 20,181 | 1,741 |

### 5.4 两次连跑的确定性证据

同一命令、同 seed=0、同题面指纹、同 `configHash`，在 19:46:15 与 19:50:02 各跑一次：

- 比对字段：`hard`、`soft`、`improvements`、`assignedTaskCount`、`feasible`、`stepCount`、
  `fingerprint`、`problemDigest`、`hardViolationCodes`。**34 题全部一致，`FULL_DIFF_COUNT=0`**。
- 抽查：S01 两次均 `hard=0 / soft=-10,500 / improvements=9`；
  S08 均 `0 / -33,700 / 6`；M03 均 `0 / -615,180 / 399`；
  M11 均 `-38 / -14,795,210 / 1,414`；M14 均 `-48 / -19,341,820 / 1,578`。
- 两次仍有差异的只有墙钟量（34 题中 32 题 TTB 不同）：S01 耗时 500→446 ms、TTB 490→438 ms；
  M11 15,711→14,175 ms；M14 16,982→16,196 ms。分数不受影响。
- 结论：**固定 seed + 步数模式下分数与改进次数具备确定性**；`durationMs` / `timeToBestMs`
  不是确定性量，不能用来做回归门禁。若这类连跑出现分数不一致，就应判定“不确定”，
  本次没有出现，故不适用。

### 5.5 排除项（必须与基线一起看）

1. **S20-infeasible-missing-routing**：步数模式下即使只给 100/200/500 步，也会在
   `BavetConstraintSession.calculateScore` → `AbstractGroupNode`（`MIN_MAKESPAN` 的
   `min/max` 聚合）内部死循环，30 秒守护强杀；`stepCountLimit` 只能在步之间检查，
   无法中断单次计分。墙钟模式能跑完（2,009 ms、hard=0），是因为它在进入该状态前就到达时限。
   因此步数基线继续用 `-Daps.bench.caseExclude=S20` 排除，报告 `excludedCases` 中有记录。
   **本版保持排除**：即使其他车道已排查或修复，也不在本基线中悄悄加回；等 A1 明确回报
   修复结论后，由后续指令决定是否重新纳入。
2. **LARGE 全档**：没有可用的步数预算。实测 L04（600 任务）8,000 步 165,875 ms；
   L05（900 任务）1,500 步 544,555 ms（约 9 分钟）；L07（3,000 任务）4,000 步 >10 分钟。
   另外步数不足以完成构造启发式时，局部搜索会抛
   `IllegalStateException: Local Search phase needs to start from an initialized solution`
   （单题异常已被 runner 捕获，不会中断整批，但该题结果无意义）。
   因此 LARGE 的步数基线为空，仍以第 4 节墙钟结果为参照；L02 已容量修订并 `hard=0`，
   其余大档仅作时延/容量参考。
3. **早前“S07 在 10,000 步卡住”的说法**：逐题复核（1,000 步）显示 S01–S19 全部正常完成，
   S07 仅 92 ms；真正不可终止的是 S20。

## 6. 规模参考（stress-reference，默认不执行）

这 5 题保存的是**容量修订前的原始题面**（`caseId` 加 `-stress` 后缀，`category=stress-reference`，
`kind` / `sizeClass` 与原题一致），用来回答“规模到这个量级时求解行为如何”，
**不回答“能不能排到 0”**。它们默认不进入回归门禁，只有显式传
`-Daps.bench.includeStress=true` 才运行。

### 6.1 题面与期望口径

| 题目 | 原始任务 / 主资源 | 每资源需要 | 容量上界 | 修订后同题 | 修订后需要 |
| --- | ---: | ---: | ---: | --- | ---: |
| S08-stress | 6 / 2 | 3.0 | 辅助窗口只有 day0 16h，day0 仅 4 个主资源位 | S08（6 / 2，窗口 7 天） | 3.0 |
| M03-stress | 120 / 6 | 20 | 14 | M03（60 / 6） | 10 |
| M09-stress | 168 / 9 | 18.7 | 14 | M09（84 / 9） | 9.33 |
| M13-stress | 270 / 12 | 22.5 | 14 | M13（90 / 12） | 7.5 |
| L02-stress | 480 / 20 | 24 | 14 | L02（160 / 20） | 8 |

- 期望字段写的是 `status: REFERENCE`，**不设 `hardScore` 通过线**，`note` 里写明容量上界与
  “hard<0 为预期结果”。报告中的 `hardMatchesExpectation=true` 只表示“没有通过线可判”，
  **不代表题面可行**；看这类题只看 `hard` 数值、`assigned`、耗时与 `terminationMode`。
- `hard<0` 是**预期结果**：容量上界被突破，`assigned=N/N` 只说明构造启发式把每个任务都放到了
  某个资源上，不代表没有冲突；`hardViolationCodes` 会显示实际违约类型（本次均为
  `resourceNoOverlap` / `operationDependency` / `primaryResourceOnly`）。
- 5 题的原始题面已用“恢复 `caseId`/期望字段后的临时副本”逐题核对过旧指纹
  （S08/M03/M09/M13/L02 全部命中 0.5 表中的旧 `fingerprint`），因此不是凭记忆重写。

### 6.2 默认排除与显式运行命令

runner 会无条件跳过 `category=stress-reference` 的题（即使不传 `caseExclude`），
并在报告的 `excludedCases` 里列出它们。日常回归/墙钟命令不需要任何额外参数；
需要跑规模参考时显式指定：

```powershell
mvn -o -B -ntp -am -pl aps-solver/solver-timefold `
    -Dtest=SolverBenchRunner -Dsurefire.failIfNoSpecifiedTests=false `
    -Daps.bench.includeStress=true `
    -Daps.bench.caseFilter=S08-stress,M03-stress,M09-stress,M13-stress,L02-stress `
    -Daps.bench.report=target/bench/bench-report-stress.json test
```

硬超时来自每题 `case.json` 的 `timeoutSeconds`（不是无限等待），
本组预算为 3s（S08）/ 5s（M03、M09、M13）/ 8s（L02），另加无改进上限 2s/3s/5s。

### 6.3 实测（2026-11-05 20:05，墙钟口径，单次运行）

| 题目 | 任务 | assigned | hard | soft | 耗时 ms | terminationMode |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| S08-stress | 6 | 6 | -6 | -5,410 | 2,278 | WALL_CLOCK |
| M03-stress | 120 | 120 | -50 | -3,076,360 | 5,028 | WALL_CLOCK |
| M09-stress | 168 | 168 | -58 | -4,873,305 | 5,023 | WALL_CLOCK |
| M13-stress | 270 | 270 | -2,693 | -19,555,440 | 5,027 | WALL_CLOCK |
| L02-stress | 480 | 480 | -8,469 | -39,071,280 | 8,090 | WALL_CLOCK |

合计 5 题 / 26,251 ms；全部 `terminationMode=WALL_CLOCK`，没有超预算挂起。
stress 题的 `fingerprint` 与旧原始题不同，仅因为 `caseId` 与 `expectation` 字段按 stress 口径改名/改写：

- S08-stress `0d0cfa41da19ccd80bb6933ecea30882e469f53ddd9b7ff86b9e31adde2c8210`
- M03-stress `8606670b8a6388e96ca027ac411ef3abe6c34facfe02d387e83203c1b09a6734`
- M09-stress `20a2bc4e1572d3ce59c461561cfc9d289ebdaaef277c48862ec878a9992fe079`
- M13-stress `3c00293a6f916528b0b51fda6c6367eb830b5d6e83dd285a295a51385beb3a95`
- L02-stress `0cd9dfb04d76ee8211e57b5baad531f2d7f547dd67df0a28f60d45b344f4c82a`

### 6.4 引用纪律

- 这些题**只回答规模/时延行为**（耗时、是否超时、hard 负多少），**不回答“能不能排到 0”**；
  任何“中档可排到 0 硬违约”的结论只能引用修订后的 M03/M09/M13，不能引用 stress 题。
- 对外引用任何性能/规模数字，必须同时写明：**档位 + 题号 + 口径（墙钟/步数）+ 运行日期/轮次**。
  例：“`M13-stress`（MEDIUM，墙钟 5s/3s，2026-11-05 第 1 次运行）：`hard=-2,693`，5,027 ms”。
- stress 题不参与基线中位数、hard=0 题数、回归通过率等任何聚合统计。

## 7. 口径差异与未满足项

1. **未走生产适配器**：本入口直接 `SolverFactory` 构建求解器，不含 `TimefoldSolverAdapter`
   的 `SOLVING_LOCK`、请求参数解析、状态映射、归因合并、TTL/请求指标。因此分数反映
   “引擎 + 约束 + 默认软权重”，不反映适配器层的排队与超时行为。
2. **`scoreHistory` 仍为空**：`TimefoldModelBuilder.toSolution` 传的是 `List.of()`，
   `SolveMetrics.scoreHistory` 没有任何时间线。本报告的时间线来自
   `BestSolutionChangedEvent` 监听器（上限 200 点），与 API 口径不同，不能直接对比。
3. **`timeToBest` 生产不可用**：适配器没有该指标/日志。本报告的 `timeToBest` 定义为
   “首个达到最终最好分的 `BestSolutionChangedEvent` 相对求解开始的毫秒数”；对
   未进入局部搜索的大档题（首解即终解），该值等于首次构造完成时间，不具优化含义
   （L06/L07 约等于全预算）。
4. **SLA 墙钟模式不可逐次复现**：固定 seed 只固定移动序列，不固定线程/CPU 调度。
   同一 seed 多次运行示例：M11 hard = -380 / -447 / -761 / -448；
   M10 hard = -361 / -373 / -364 / -358；S01 timeToBest = 1,074 / 1,199 / 2,137 ms。
   **回归门禁改用步数模式（第 5 节）**：34 题连跑两次 hard/soft/improvements 完全一致；
   但 `durationMs`/`timeToBest` 仍随墙钟抖动。
5. **大档构造启发式超预算**：L06（1,500 任务）与 L07（3,000 任务）在 8s 内
   `assigned=0`；额外用 60s 探测 L07 仍为 0。因此大档当前只能作“时延/容量参考”，
   不能作解质量对比。步数模式对大档也不可行（见 5.5）。
6. **日历类题的系统性硬违约已消除（归因修正）**：
   - 模型侧：`TimefoldModelBuilder.buildStartTimeRange` 原先只按“候选整点不早于窗口起点”裁剪，
     未排除“任务时长跨过窗口终点”的候选；2h10 任务在 8h 班次末尾开始会触发
     `calendarAvailability`。A1 已补 `fitsAnyMovableTask`（候选必须整块落在窗口内）。
   - 题面侧：S08/M03/M09/M13/L02 的**容量本身超限**（见 0.2/0.3）。
     上一版本文档把归因全部推给模型侧，复核后确认还叠加了题面容量错误，特此更正。
   - 两项修复后，这 5 题在墙钟与步数口径下均无 `calendarAvailability` 违约，`hard=0`。
7. **“可硬可行”标签与预算不匹配（收敛问题，非容量）**：M02/M06/M07/M08/M10 在 5s 墙钟与
   10,000 步下均未到 `hard=0`；M01 墙钟到 0 但步数只到 `-1`，M04 步数到 0 但墙钟 `-1`。
   这些题的 `calendar=NONE`、主资源全天可用，容量上界不构成约束；`timeToBest` 接近预算上限，
   说明仍在改善，属搜索收敛速度问题，留作 A1 的模型/移动选择排查项。
8. **两种口径不可互比**：墙钟按秒、步数按步；中档同一题在两种口径下 hard 可相差数十分。
   A/B 或回归必须锁定同一口径、同一预算、同一 `configHash`、同一 `caseExclude`。
9. **未覆盖项**：没有适配器路径基线；没有多 seed 统计（仅 seed=0 + 两次连跑）；
   首题包含 JIT 预热；未做 warm-up 弃跑；未做 GC 日志采集；未测并发多单；
   步数基线没有 LARGE，也没有 S20。

## 8. 防调参纪律

- **禁止只改 seed / 软权重让分数变好看**：任何调参必须提交 A/B，A 与 B 使用**同一批题、
  同一预算、同一机器**，且两边的逐题 `fingerprint` 必须与基线完全一致。
- A/B 必须同时给出 hard 与 soft，并至少包含：步数口径全量（`SMALL:1000,MEDIUM:10000`、
  `caseExclude=L,S20`、`configHash` 与基线一致）连跑两次且分数一致，以及墙钟 42 题的
  对照结果。
- 若必须改题面（订单/工序/日历/预算），视为**新基准**：指纹会变，必须重建基线并在
  报告里标注旧基线的废弃，不允许在同一批指纹上“调”分数。
- 只允许调整求解器参数（预算/构造启发式/移动选择）时，需注明改动点与回归命令；
  任何 PR 若无法用同一批指纹复现 A/B 表，视为无效调参。
- 基线不是通过线：hard=0 题数的变化必须与具体题号一起报告，禁止只报中位数。

## 9. 对 A0 / A1 的需求项

**A0（适配器/生命周期/权重）**

1. 填充 `SolveMetrics.scoreHistory`（含 elapsedMs、hard、soft；建议限长）与 time-to-best，
   或提供等价的 `SolverEventListener` 注入点；当前 `List.of()` 口径让跑分只能绕过适配器。
2. 在 `SolveConfig` / 求解请求中显式暴露 `unimprovedSecondsSpentLimit` 与
   `maximumStepCount`（目前只有 `timeoutSeconds`），否则确定性回归无法从生产入口复现。
3. 提供“无锁只读求解”或并发跑分模式；`SOLVING_LOCK` 下无法做并行/重复 A/B。
4. ~~修复 `SoftWeightsConcurrencyTest` 编译错误~~ —— **已由 A0 修复**（`BLOCKED_MIN_MILLIS`
   已不存在），`test-compile` 现已通过，本基线已改用规范 Maven 入口执行。
5. ~~明确默认软权重并提供 `configHash`~~ —— **已交付并在本基线使用**：
   `sha256:2062091e105c63037324bfd5773127c2fafaba5aaaf16316264434198949472a`
   （仅硬约束 `sha256:7edc3bb261ddf163f6a80bfcb99f3bf13e95a1179db3ef1a0a415a015e82cb27`）。
6. 步数终止语义已对齐：`maximumStepCount > 0` 时只按步数终止，`terminationRequest` 回显
   `STEP_COUNT`/`WALL_CLOCK`。本跑分器已按同语义实现并验证（34 题两次一致）。

**A1（模型/约束/大档）**

1. ~~`buildStartTimeRange` 未按“任务时长是否完整落在窗口内”裁剪候选~~ —— **已由 A1 修复**
   （`fitsAnyMovableTask`）；容量修订后 S08/M03/M09/M13/L02 的 `calendarAvailability` 已归零。
2. 大档构造启发式：1,500–3,000 任务在 8s（甚至 60s）内 0 赋值，需要分块/增量构造、
   或按资源先分桶再排任务；同时暴露构造进度指标。
3. 中档收敛速度：M01/M02/M04/M06/M07/M08/M10 在 5s 墙钟与 10,000 步下仍未全部达到 `hard=0`；
   这些题不受 8h 班次容量约束，请确认是预算不足还是模型/移动选择问题；若是模型，请给出
   可复现的最小题面。
4. 若确认 `primaryResourceOnly`/`operationDependency` 在部分题上不可修复，请在约束层
   提供明确的可达性诊断（而非只给负分）。
5. **S20 计分死循环**：`S20-infeasible-missing-routing` 在步数模式必挂
   （`BavetConstraintSession.calculateScore` → `AbstractGroupNode`，`MIN_MAKESPAN`
   的 `min/max` 聚合；100 步即复现）。请排查 `ApsConstraintProvider` 的
   `groupBy(min(startTime), max(endTime))` 在“缺路由 + 少任务”题面下的传播，或确认
   是否为 Timefold 2.8.0 缺陷并给出规避配置。**本基线仍保留 `caseExclude=S20` 与“不可终止”
   说明；修复后请明确回报，再由后续指令决定是否加回。**

## 附录 A：题面指纹清单

`fingerprint` 覆盖 `case.json` 全内容（问题 + 预算 + seed + 期望）。物化问题摘要
`problemDigest` 见 `bench-baseline.json`。本次 5 题指纹已变（见 0.5），其余不变。
5 题 stress-reference 的指纹见 6.3（它们的 `caseId`/`expectation` 与旧原始题不同，故哈希不同）。

| 题目 | 档位 | fingerprint |
| --- | --- | --- |
| S01-smoke-basic | SMALL | 40ab4acbfb318f336e5c1ec2acb5ae55e7dcd2ca8a0074cab227ac2e89fe0832 |
| S02-smoke-setup-matrix | SMALL | 8d255a7c30f600e223cf7593cca9adea9fb303dd547d4f681e2083b278eae816 |
| S03-smoke-explicit-deps | SMALL | 484454acafb1c7a50f152f7f2ed6de385995676bd14f2072f5da5e4bf6686065 |
| S04-smoke-calendar | SMALL | 575e7989df0c2e6e6013820a1b4d50c1f8a1aaac8ba88e54153dad7011da86e7 |
| S05-smoke-frozen | SMALL | 3e1efcca5514d4dd9032de829c7770ec11ae9c1819663e775f81c9ec83926b1d |
| S06-smoke-secondary | SMALL | 13843af180a012c9ea3b180cc6755d5a980771168312addd5165e4b9804849c7 |
| S07-smoke-priority | SMALL | b4a9c00cc3c341c06d56b5a625937ef88678e168a06c76311785dd3f6cc9d370 |
| S08-smoke-all-features | SMALL | 6dcf00dfcf32d5e033540c887929cf8ca2b2120f739a0e55e2606c0658e0044e |
| S09-feasible-small-setup | SMALL | 1b8e62a4d6ace71a000f8a889c3dbdbc59b1b531d774f9fcb5ee75f938424cb4 |
| S10-feasible-small-priority | SMALL | bd5443e4d04ff02b12d959d02ce3e544cd39bba7f754b6d6173a3e996df9b9cb |
| S11-incremental-basic | SMALL | 24888e25dad25c318aa390f4206a54a4fd4385b70be8ec4eb29561f483cce4df |
| S12-incremental-setup | SMALL | c943ed259e4a55321c5100a621d78f60dcbfd1f5fe67567a0bad58f4d65c9504 |
| S13-incremental-calendar | SMALL | 8f4943191d82e6ed9e45c22cefc4d6c300b49156f2c72fd09e55e69dba0d0d25 |
| S14-incremental-frozen | SMALL | f5f9ada047b14da47fb052d32c5780a781dcda2ca12bafc4961843b921202b9b |
| S15-infeasible-frozen-overlap | SMALL | f49886052b66090b4899be127769b68e76922317913035f560640c418ddb0db7 |
| S16-infeasible-calendar-gap | SMALL | 5cbf846717b17268375ef539ef5af46bfba8390d6f95337eaa51fed37655bfa9 |
| S17-infeasible-frozen-outside-calendar | SMALL | 03b4df70f5bbc606e6d31e335014febcecc16dbc38d97d94f98df0f22783e21b |
| S18-infeasible-secondary-shortage | SMALL | 1e4997a622a507c794bc2437b3f272ee71d04b9f500679d8b4ae343d1e1eecb6 |
| S19-infeasible-horizon-overflow | SMALL | 8d09fce24607e32f05011d70e2ffab1b513b151c3806bacf2c625172253972b0 |
| S20-infeasible-missing-routing | SMALL | 79bcf5bed1fb7281672c51f4d630db84ceb23136254690fff32f3b229096f3f0 |
| M01-feasible-10o-8op | MEDIUM | 5680c7ad753a0bd03ce3a5c5c44276085efbaa9cbd1173864a678c7c86bee835 |
| M02-feasible-15o-setup | MEDIUM | 63ab309e40439a7250295d32caa3b32b5519ad5b6301881437d9eac7349143b3 |
| M03-feasible-20o-calendar | MEDIUM | 1983f532e13afd5e40de14d4640fb89371f3ceabfab542bdaee7ebeecd380854 |
| M04-feasible-25o-setup3fam | MEDIUM | a7441f0d93fb7690c4def44a88542fe67fa3e11c772f9bbc61ffb43249ade1b4 |
| M05-feasible-30o-priority | MEDIUM | fe75dbb54f48666a11bf7134a66bce3739dc24aa8881d90b36a7cf32d1a9f728 |
| M06-feasible-12o-secondary | MEDIUM | 80e858239f530efed5d694e38338c2a2f78059954bf7cbd47f02dcf38cc12783 |
| M07-feasible-18o-deps | MEDIUM | 5ad864955c9b2e9bce6f871c5c2c1a0d530931f3136a7726501c9f595a3203ba |
| M08-feasible-22o-setup | MEDIUM | d9260259c167e366da5a10958e868d654a6d37cf1976da80c82be171bcb82e05 |
| M09-feasible-28o-calendar | MEDIUM | fba4c414dc1527b95d9f105ee94a5d7bcb7c931607d8461e177b6d6799b7bab8 |
| M10-feasible-30o-tightdue | MEDIUM | 3cf1988d0c62b648d972ea220a4ba1f4abb248dd1682b57a925aeb16848b5a5a |
| M11-hard-30o-8op | MEDIUM | e563b87ce6deb0215b881c69f49699fef55e15cbc9553e0cb26a8f4ae6975d3b |
| M12-hard-40o-setup | MEDIUM | 969b2b5ac70c944f7633b48747e63f21aa74012f65d6b532a597e1c043110adf |
| M13-hard-45o-calendar | MEDIUM | 7f76ece1a871831c7a98367a5202be2d2e200982de4e58f6fb8443e2fcff2e7e |
| M14-hard-50o-tightdue | MEDIUM | edbd9c4f6251ac5882b01a6c24dea0cfdb995920330ceed2f8a27ea573136887 |
| M15-hard-35o-all | MEDIUM | 303107c89a22a4a0b7fa1199ebd39bdb8eac876c37b63f9b61a0ae643b6f50bf |
| L01-hard-60o | LARGE | 9e859be245158aa004bc783b20af6220867b53922d074ee29e54c7a44dc07053 |
| L02-hard-80o-calendar | LARGE | 0a7b62b8c54a9ecf19cb3e5c2bb142e7c0dfa7e9f57dbc0c9e404d34419bcc87 |
| L03-hard-100o | LARGE | 87c03381ef219257bb08650bf89d85cee5aa28fea58ec00ade5e82ab226b425d |
| L04-ref-100o-600t | LARGE | 20ada32909dbd7a45b8b95bf478c4c751aad4227b59bace7aef8e84c4695cab9 |
| L05-ref-150o-900t | LARGE | fddc6613b0268dfc197fe3dc92fcdedea08c84c24e6f98ff18556492dbd416da |
| L06-ref-250o-1500t | LARGE | 92db7476d51c7f19bec11bab5cd56042d61c603679606d9e18e31556f598a787 |
| L07-ref-500o-3000t | LARGE | 6e9b2482377b9906f35121fc1e9aa88f387faa6eeae4ecf07ca0f45d9fa24f7e |
