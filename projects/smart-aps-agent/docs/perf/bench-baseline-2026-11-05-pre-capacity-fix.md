# A3 基准骨架 — 基线记录（步数 34 题 / 墙钟 42 题，seed=0）

> 车道：Wave 0 · A3「基准骨架」。本文件只记录测试侧基线与口径，不改变主代码。
>
> - **步数基线（回归主口径，可复现）**：
>   [bench-baseline-step.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline-step.json)
>   （SHA256 `73899C636A999564C0E050EC6C21CD0D83B6B1EC241A9DFD513EF09F49E0D80E`）。
>   34 题（小 19 + 中 15），SMALL 1,000 步 / MEDIUM 2,000 步，`terminationMode=STEP_COUNT`。
> - 第二次连跑副本（确定性证据）：
>   [bench-baseline-step-run2.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline-step-run2.json)
>   （SHA256 `8D876BFC3C71D2A5A8091B86298F02105FB5AB524ED2163540FC4371CC2B64F6`）。
> - **墙钟基线（对照口径，保留）**：
>   [bench-baseline.json](file:///d:/tools/smart-aps-agent/docs/perf/bench-baseline.json)
>   （SHA256 `AD6C5A7949F7F30BE7AA40D7F6CDA5F0AD381E20D36B20D62255D20E3838CC4E`）。42 题，SLA 3s/5s/8s。
> - 两种口径的分数 **不可互比**：预算一个是步数、一个是秒；对照只用于看“同题在不同预算下大概到哪里”。

## 结论

- 题量：共 42 题，按档位 **小 20 / 中 15 / 大 7**；按类别 8 smoke、12 feasible-medium、8 hard-feasible、
  6 infeasible、4 incremental、4 large-ref。
- 题面全部固化为 `case.json`（问题规格 + 预算 + seed + 期望），每题给出指纹；
  任何题面/预算/seed 变化都会换指纹。步数基线 34 题的指纹与墙钟基线 42 题逐题一致（FP_DIFF=0）。
- **步数模式连跑两次，34 题逐题 `hard / soft / improvements / assignedTaskCount / feasible /
  stepCount / fingerprint` 完全一致（差异数 0）**。固定 seed + 步数终止下分数可复现；
  仍随墙钟变化的只有 `durationMs` 与 `timeToBestMs`，它们不进入回归门禁。
  两次都一致的实测证据见第 5.4 节；若两次不一致就不叫确定性，本次未出现。
- 步数基线一次全量 **34 题 / 73,033 ms**（含每题建模，不含 JVM 启动）：小档 2,286 ms、
  中档 69,001 ms。第二次 73,854 ms（只差墙钟开销）。
- **步数基线不覆盖 LARGE**：没有可用的步数预算（L04 8,000 步 165.9s、L05 1,500 步 544.6s、
  L07 4,000 步 >10 min），且步数不足以完成构造启发式时局部搜索会抛
  `IllegalStateException`。LARGE 仍以墙钟基线为参考。
- **S20 在步数模式不可终止**：卡死在单次计分内部（Bavet `MIN_MAKESPAN` 的 `min/max` 聚合），
  100/200/500 步均在 30s 守护外被强杀；`stepCountLimit` 无法中断计分内部循环。已排除并记录。
- 墙钟基线保留为对照：固定 seed 仍不可逐次复现（M11 hard `-380/-447/-761`），
  故回归门禁改用步数模式。
- **最大口径差异**：本入口直接构造 Timefold 求解器并使用 `BestSolutionChangedEvent`，
  不经过 `TimefoldSolverAdapter`；`SolveMetrics.scoreHistory` 仍为空，`timeToBest` 也未在
  生产 API 中提供。生产侧需 A0 补齐。

## 1. 题量分布与档位判定口径

| 档位 | 题数 | 订单数 / 任务数 | 判定口径 |
| --- | --- | --- | --- |
| SMALL | 20 | 1–3 单 / 2–15 任务 | 订单 ≤ 3 **且** 任务 ≤ 15 |
| MEDIUM | 15 | 10–50 单 / 80–300 任务 | 订单 ≤ 50 **且** 任务 ≤ 350（不含 SMALL） |
| LARGE | 7 | 60–500 单 / 360–3000 任务 | 订单 > 50 **或** 任务 > 350 |

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
    -Daps.bench.stepCounts=SMALL:1000,MEDIUM:2000 `
    -Daps.bench.caseExclude=L,S20 `
    -Daps.bench.report=target/bench/bench-report-step.json test
```

- 步数模式下**只**给 `stepCountLimit`，不再叠加 `spentLimit` 或
  `unimprovedSecondsSpentLimit`；报告 `terminationMode` 回显 `STEP_COUNT`。
- **`unimprovedSecondsSpentLimit` 与 `maximumStepCount` 不要同时给**：步数模式优先。
  墙钟模式叠加无改进上限时，无改进会先于墙钟触发。
- `-Daps.bench.caseExclude=L,S20` 按 `caseId` 前缀排除：LARGE 无可用步数预算（见 5.5），
  S20 在计分内部死循环（见 5.5）；报告里 `excludedCases` 会列出实际排除的 8 题。
- 本次全量：**34 题 / 73,033 ms**（`totalDurationMs`，含每题建模与求解，不含 JVM 启动）；
  档位小计：小 2,286 ms、中 69,001 ms。第二次连跑 73,854 ms。
- 报告落盘：`aps-solver/solver-timefold/target/bench/bench-report-step.json`，
  归档副本 `docs/perf/bench-baseline-step.json` 与 `bench-baseline-step-run2.json`。

### 3.3 墙钟基线（对照口径）

```powershell
mvn -o -B -ntp -am -pl aps-solver/solver-timefold `
    -Dtest=SolverBenchRunner -Dsurefire.failIfNoSpecifiedTests=false `
    -Daps.bench.report=target/bench/bench-report.json test
```

- 42 题全部跑，预算取每题 `case.json` 的 `timeoutSeconds` / `unimprovedSecondsSpentLimit`
  （小 3s/2s、中 5s/3s、大 8s/5s）；**本次全量 179,550 ms**。
- 该口径 **不可逐次复现**，只保留作对照（见第 6 节）。

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
- **有未提交改动**：`git status --porcelain` 238 项（墙钟基线时统计）。其中包含 A0/A1 车道的
  `TimefoldSolverAdapter`、`TimefoldModelBuilder`、`ScheduleProblem`、`SchedulingOrder`、
  `SetupMatrixEntry`、`SoftConstraintWeights` 等主代码改动，也包含 A3 新增的
  `bench/` 与 `docs/perf/bench-baseline.*`。
  跑分报告已记 `dirty=true`；**该快照不能当作“某个已提交版本的基线”复用**，
  A0/A1 合入后必须重建基线。
- 主代码只读：A3 未修改任何 `src/main` 文件；墙钟基线生成时 `test-compile` 曾被
  `SoftWeightsConcurrencyTest` 阻塞、用 `javac` 绕行；该问题已由 A0 修复，
  **步数基线已改用第 3 节的规范 Maven 入口执行**。
- 运行环境隔离：18085 进程未重启（PID 32120 只读），`aps_local` 未连接，`aps_demo` 未写入；
  题面全部内存物化，未建临时库。

## 4. 墙钟基线（对照口径，seed=0，SLA 3s/5s/8s）

机器：AMD Ryzen 7 9800X3D（8C/8T）、64 GB、Windows 11 26200、Temurin JDK 21.0.13、
`-Xmx2g`、Timefold 2.8.0 community；软约束权重使用 `SoftConstraintWeights.defaults()`
（20/15/10/10/10/10/15/10），`configHash = sha256:2062091e105c63037324bfd5773127c2fafaba5aaaf16316264434198949472a`
（仅硬约束对照为 `sha256:7edc3bb261ddf163f6a80bfcb99f3bf13e95a1179db3ef1a0a415a015e82cb27`，本次未用）。
> 该快照生成于 A0 提供 `configHash` 输出之前，文件里没有 `configHash` 字段；
> 但权重集与步数基线完全相同，故哈希同上。

| 档位 | 题数 | 总耗时 | 耗时中位数 | hard 中位数 | soft 中位数 | timeToBest 中位数 | hard=0 题数 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SMALL | 20 | 46.4 s | 2,270 ms | 0 | -10,440 | 268 ms | 14/20 |
| MEDIUM | 15 | 75.2 s | 5,012 ms | -51 | -3,960,625 | 4,882 ms | 2/15 |
| LARGE | 7 | 56.2 s | 8,027 ms | -1,295 | -14,694,240 | 1,464 ms | 0/7 |

### 逐题结果

`TTB` = timeToBest（毫秒）；`assigned` = 已赋值任务数；hard=0 才算硬可行。

| 题目 | 类别 | 任务 | assigned | hard | soft | 耗时 ms | TTB ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| S01-smoke-basic | SMOKE | 6 | 6 | 0 | -8,100 | 3,145 | 1,199 |
| S02-smoke-setup-matrix | SMOKE | 6 | 6 | 0 | -8,500 | 2,527 | 525 |
| S03-smoke-explicit-deps | SMOKE | 6 | 6 | 0 | -8,100 | 2,540 | 538 |
| S04-smoke-calendar | SMOKE | 6 | 6 | 0 | -18,900 | 2,124 | 123 |
| S05-smoke-frozen | SMOKE | 6 | 6 | 0 | -8,100 | 2,568 | 563 |
| S06-smoke-secondary | SMOKE | 6 | 6 | 0 | -9,900 | 2,587 | 586 |
| S07-smoke-priority | SMOKE | 6 | 6 | 0 | -8,060 | 2,110 | 107 |
| S08-smoke-all-features | SMOKE | 6 | 6 | -3 | -10,300 | 2,602 | 602 |
| S09-feasible-small-setup | FEASIBLE_MEDIUM | 9 | 9 | 0 | -11,520 | 2,396 | 397 |
| S10-feasible-small-priority | FEASIBLE_MEDIUM | 9 | 9 | 0 | -11,080 | 2,516 | 515 |
| S11-incremental-basic | INCREMENTAL | 6 | 6 | 0 | -8,100 | 2,187 | 186 |
| S12-incremental-setup | INCREMENTAL | 9 | 9 | 0 | -11,520 | 2,471 | 471 |
| S13-incremental-calendar | INCREMENTAL | 6 | 6 | 0 | -17,600 | 2,282 | 280 |
| S14-incremental-frozen | INCREMENTAL | 6 | 6 | 0 | -7,600 | 2,259 | 257 |
| S15-infeasible-frozen-overlap | INFEASIBLE | 6 | 6 | -2 | -10,580 | 2,007 | 7 |
| S16-infeasible-calendar-gap | INFEASIBLE | 15 | 15 | -117 | -23,690 | 2,019 | 18 |
| S17-infeasible-frozen-outside-calendar | INFEASIBLE | 6 | 6 | -1 | -39,380 | 2,008 | 7 |
| S18-infeasible-secondary-shortage | INFEASIBLE | 8 | 8 | -7 | -17,260 | 2,012 | 11 |
| S19-infeasible-horizon-overflow | INFEASIBLE | 10 | 10 | -5 | -1,622,100 | 2,012 | 11 |
| S20-infeasible-missing-routing | INFEASIBLE | 2 | 2 | 0 | -3,380 | 2,008 | 7 |
| M01-feasible-10o-8op | FEASIBLE_MEDIUM | 80 | 80 | 0 | -1,495,550 | 5,011 | 4,968 |
| M02-feasible-15o-setup | FEASIBLE_MEDIUM | 120 | 120 | -4 | -2,154,680 | 5,009 | 4,870 |
| M03-feasible-20o-calendar | FEASIBLE_MEDIUM | 120 | 120 | -51 | -2,702,060 | 5,012 | 4,640 |
| M04-feasible-25o-setup3fam | FEASIBLE_MEDIUM | 150 | 150 | -1 | -2,451,660 | 5,010 | 4,903 |
| M05-feasible-30o-priority | FEASIBLE_MEDIUM | 150 | 150 | 0 | -1,945,445 | 5,007 | 4,921 |
| M06-feasible-12o-secondary | FEASIBLE_MEDIUM | 96 | 96 | -6 | -2,790,890 | 5,005 | 4,874 |
| M07-feasible-18o-deps | FEASIBLE_MEDIUM | 126 | 126 | -2 | -2,127,120 | 5,008 | 4,992 |
| M08-feasible-22o-setup | FEASIBLE_MEDIUM | 176 | 176 | -29 | -3,960,625 | 5,022 | 4,999 |
| M09-feasible-28o-calendar | FEASIBLE_MEDIUM | 168 | 168 | -72 | -4,567,170 | 5,007 | 4,972 |
| M10-feasible-30o-tightdue | FEASIBLE_MEDIUM | 240 | 240 | -364 | -18,729,695 | 5,013 | 4,882 |
| M11-hard-30o-8op | HARD_FEASIBLE | 240 | 240 | -761 | -20,913,645 | 5,021 | 4,913 |
| M12-hard-40o-setup | HARD_FEASIBLE | 280 | 277 | -794 | -20,606,245 | 5,015 | 202 |
| M13-hard-45o-calendar | HARD_FEASIBLE | 270 | 270 | -1,931 | -20,112,575 | 5,020 | 4,664 |
| M14-hard-50o-tightdue | HARD_FEASIBLE | 300 | 285 | -1,088 | -28,554,345 | 5,014 | 217 |
| M15-hard-35o-all | HARD_FEASIBLE | 280 | 273 | -775 | -19,977,920 | 5,013 | 182 |
| L01-hard-60o | HARD_FEASIBLE | 360 | 321 | -1,080 | -23,903,630 | 8,017 | 352 |
| L02-hard-80o-calendar | HARD_FEASIBLE | 480 | 473 | -5,714 | -38,993,515 | 8,022 | 764 |
| L03-hard-100o | HARD_FEASIBLE | 600 | 314 | -1,295 | -26,693,715 | 8,025 | 1,464 |
| L04-ref-100o-600t | LARGE_REF | 600 | 314 | -1,286 | -14,694,240 | 8,027 | 1,414 |
| L05-ref-150o-900t | LARGE_REF | 900 | 238 | -1,267 | -10,727,235 | 8,027 | 4,951 |
| L06-ref-250o-1500t | LARGE_REF | 1,500 | 0 | -1,500 | 0 | 8,042 | 8,034 |
| L07-ref-500o-3000t | LARGE_REF | 3,000 | 0 | -3,000 | 0 | 8,063 | 8,052 |

## 5. 步数基线（回归主口径，确定性）

### 5.1 口径

- 终止：`maximumStepCount > 0` 时 **只** 给 `stepCountLimit`，不叠加 `spentLimit` 与
  `unimprovedSecondsSpentLimit`；报告 `terminationMode=STEP_COUNT`，`stepCount` 回显预算。
- 步数预算：**SMALL 1,000 步 / MEDIUM 2,000 步**；LARGE 不设（见 5.5）。
- seed 固定 0；软权重 `SoftConstraintWeights.defaults()`，
  `configHash = sha256:2062091e105c63037324bfd5773127c2fafaba5aaaf16316264434198949472a`
  （每档汇总里也逐档写了同一个哈希）。
- 题面指纹与墙钟基线 **逐题一致**（34/34，`FP_DIFF=0`）；本次只换了终止口径与预算。

### 5.2 汇总

| 档位 | 题数 | 步数 | configHash | 总耗时 | 耗时中位数 | hard 中位数 | soft 中位数 | TTB 中位数 | hard=0 题数 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| SMALL | 19 | 1,000 | `sha256:2062091e…472a` | 2.3 s | 93 ms | 0 | -14,520 | 55 ms | 13/19 |
| MEDIUM | 15 | 2,000 | `sha256:2062091e…472a` | 69.0 s | 2,645 ms | -69 | -4,540,690 | 2,642 ms | 0/15 |
| LARGE | 0 | — | — | — | — | — | — | — | — |

全量 **34 题 / 73,033 ms**。中档在 2,000 步内 hard 全部为负，这是步数预算下的基线事实；
墙钟 5s 的中档结果更好（2/15），两者不可互比。

### 5.3 逐题结果（run 1）

`TTB` = timeToBest（毫秒）；`assigned` = 已赋值任务数；hard=0 才算硬可行。
S20 与 LARGE 的步数结果为空，原因见 5.5；它们的墙钟结果见第 4 节。

| 题目 | 类别 | 任务 | assigned | hard | soft | 耗时 ms | TTB ms | 改进次数 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| S01-smoke-basic | SMOKE | 6 | 6 | 0 | -10,500 | 581 | 571 | 9 |
| S02-smoke-setup-matrix | SMOKE | 6 | 6 | 0 | -10,900 | 173 | 166 | 12 |
| S03-smoke-explicit-deps | SMOKE | 6 | 6 | 0 | -10,500 | 101 | 85 | 9 |
| S04-smoke-calendar | SMOKE | 6 | 6 | 0 | -28,500 | 128 | 99 | 10 |
| S05-smoke-frozen | SMOKE | 6 | 6 | 0 | -9,300 | 101 | 55 | 8 |
| S06-smoke-secondary | SMOKE | 6 | 6 | 0 | -10,500 | 170 | 160 | 10 |
| S07-smoke-priority | SMOKE | 6 | 6 | 0 | -9,860 | 139 | 76 | 14 |
| S08-smoke-all-features | SMOKE | 6 | 6 | -6 | -5,410 | 265 | 63 | 10 |
| S09-feasible-small-setup | FEASIBLE_MEDIUM | 9 | 9 | 0 | -14,520 | 93 | 80 | 19 |
| S10-feasible-small-priority | FEASIBLE_MEDIUM | 9 | 9 | 0 | -14,080 | 98 | 95 | 21 |
| S11-incremental-basic | INCREMENTAL | 6 | 6 | 0 | -35,910 | 38 | 29 | 31 |
| S12-incremental-setup | INCREMENTAL | 9 | 9 | 0 | -27,720 | 70 | 45 | 21 |
| S13-incremental-calendar | INCREMENTAL | 6 | 6 | 0 | -30,900 | 50 | 45 | 22 |
| S14-incremental-frozen | INCREMENTAL | 6 | 6 | 0 | -24,300 | 37 | 27 | 10 |
| S15-infeasible-frozen-overlap | INFEASIBLE | 6 | 6 | -2 | -10,580 | 48 | 12 | 2 |
| S16-infeasible-calendar-gap | INFEASIBLE | 15 | 15 | -117 | -23,690 | 70 | 28 | 2 |
| S17-infeasible-frozen-outside-calendar | INFEASIBLE | 6 | 6 | -1 | -39,380 | 54 | 9 | 3 |
| S18-infeasible-secondary-shortage | INFEASIBLE | 8 | 8 | -7 | -17,260 | 36 | 12 | 2 |
| S19-infeasible-horizon-overflow | INFEASIBLE | 10 | 10 | -5 | -1,622,100 | 34 | 12 | 2 |
| M01-feasible-10o-8op | FEASIBLE_MEDIUM | 80 | 80 | -3 | -2,869,545 | 756 | 753 | 374 |
| M02-feasible-15o-setup | FEASIBLE_MEDIUM | 120 | 120 | -11 | -2,746,695 | 1,620 | 1,590 | 479 |
| M03-feasible-20o-calendar | FEASIBLE_MEDIUM | 120 | 120 | -69 | -3,447,620 | 1,083 | 1,053 | 483 |
| M04-feasible-25o-setup3fam | FEASIBLE_MEDIUM | 150 | 150 | -41 | -3,416,430 | 2,207 | 2,177 | 508 |
| M05-feasible-30o-priority | FEASIBLE_MEDIUM | 150 | 150 | -37 | -2,796,105 | 1,891 | 1,888 | 529 |
| M06-feasible-12o-secondary | FEASIBLE_MEDIUM | 96 | 96 | -9 | -2,795,105 | 2,645 | 2,642 | 386 |
| M07-feasible-18o-deps | FEASIBLE_MEDIUM | 126 | 126 | -39 | -3,153,185 | 1,473 | 1,471 | 446 |
| M08-feasible-22o-setup | FEASIBLE_MEDIUM | 176 | 176 | -52 | -4,540,690 | 3,606 | 3,603 | 655 |
| M09-feasible-28o-calendar | FEASIBLE_MEDIUM | 168 | 168 | -105 | -4,801,355 | 1,721 | 1,719 | 651 |
| M10-feasible-30o-tightdue | FEASIBLE_MEDIUM | 240 | 240 | -98 | -15,348,250 | 6,997 | 6,995 | 731 |
| M11-hard-30o-8op | HARD_FEASIBLE | 240 | 240 | -109 | -15,158,700 | 7,484 | 7,480 | 717 |
| M12-hard-40o-setup | HARD_FEASIBLE | 280 | 280 | -150 | -16,406,490 | 10,077 | 10,074 | 726 |
| M13-hard-45o-calendar | HARD_FEASIBLE | 270 | 270 | -289 | -15,558,080 | 7,545 | 7,542 | 763 |
| M14-hard-50o-tightdue | HARD_FEASIBLE | 300 | 300 | -151 | -21,072,765 | 9,376 | 9,368 | 816 |
| M15-hard-35o-all | HARD_FEASIBLE | 280 | 280 | -159 | -15,830,680 | 10,520 | 10,513 | 723 |

### 5.4 两次连跑的确定性证据

同一命令、同 seed=0、同题面指纹、同 `configHash`，在 19:20:21 与 19:21:40 各跑一次：

- 比对字段：`hard`、`soft`、`improvements`、`assignedTaskCount`、`feasible`、`stepCount`、
  `fingerprint`。**34 题全部一致，`DIFF_COUNT=0`**。
- 抽查：S01 两次均 `hard=0 / soft=-10,500 / improvements=9`；
  S07 均 `0 / -9,860 / 14`；M11 均 `-109 / -15,158,700 / 717`；
  M14 均 `-151 / -21,072,765 / 816`。
- 两次仍有差异的只有墙钟量：S01 耗时 581→869 ms、`timeToBest` 571→853 ms；
  M11 7,484→7,721 ms；M14 9,376→9,655 ms。分数不受影响。
- 结论：**固定 seed + 步数模式下分数与改进次数具备确定性**；`durationMs` / `timeToBestMs`
  不是确定性量，不能用来做回归门禁。若这类连跑出现分数不一致，就应判定“不确定”，
  本次没有出现，故不适用。

### 5.5 排除项（必须与基线一起看）

1. **S20-infeasible-missing-routing**：步数模式下即使只给 100/200/500 步，也会在
   `BavetConstraintSession.calculateScore` → `AbstractGroupNode`（`MIN_MAKESPAN` 的
   `min/max` 聚合）内部死循环，30 秒守护强杀；`stepCountLimit` 只能在步之间检查，
   无法中断单次计分。墙钟模式能跑完（2,027 ms、hard=0），是因为它在进入该状态前就到达时限。
   因此步数基线用 `-Daps.bench.caseExclude=S20` 排除，报告 `excludedCases` 中有记录。
   该现象已写入 `SolverBenchRunner` 的 javadoc，交给 A1 排查约束侧。
2. **LARGE 全档**：没有可用的步数预算。实测 L04（600 任务）8,000 步 165,875 ms；
   L05（900 任务）1,500 步 544,555 ms（约 9 分钟）；L07（3,000 任务）4,000 步 >10 分钟。
   另外步数不足以完成构造启发式时，局部搜索会抛
   `IllegalStateException: Local Search phase needs to start from an initialized solution`
   （单题异常已被 runner 捕获，不会中断整批，但该题结果无意义）。
   因此 LARGE 的步数基线为空，仍以第 4 节墙钟结果为参照。
3. **早前“S07 在 10,000 步卡住”的说法**：本次逐题复核（1,000 步）显示 S01–S19 全部正常完成，
   S07 仅 139 ms；真正不可终止的是 S20。10,000 步口径未采用，也不再作为基线预算。

## 6. 口径差异与未满足项

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
   同一 seed 三次运行示例：M11 hard = -380 / -447 / -761；M10 hard = -361 / -373 / -364；
   S01 timeToBest = 1,074 / 1,199 / 2,137 ms。**回归门禁改用步数模式（第 5 节）**：
   34 题连跑两次 hard/soft/improvements 完全一致；但 `durationMs`/`timeToBest` 仍随墙钟抖动。
5. **大档构造启发式超预算**：L06（1,500 任务）与 L07（3,000 任务）在 8s 内
   `assigned=0`；额外用 60s 探测 L07 仍为 0。因此大档当前只能作“时延/容量参考”，
   不能作解质量对比。步数模式对大档也不可行（见 5.5）。
6. **日历类题存在系统性硬违约**：`TimefoldModelBuilder.buildStartTimeRange` 只按
   “候选整点不早于窗口起点”裁剪，未排除“任务时长跨过窗口终点”的候选；
   2 小时级任务在 8 小时班次末尾开始必然触发 `calendarAvailability`。
   S08/M03/M09/M13/L02 的 hard 违约中含此项，属模型侧问题（需求见第 8 节），不是题面错误。
7. **“可硬可行”标签与预算不匹配**：墙钟 5s 下中档 15 题仅 2 题 hard=0；步数 2,000 步下 0 题。
   很多题的 hard 分在预算结束时仍在改善（`timeToBest` 接近预算上限）。
   标签表示题面设计意图，不代表基线通过。
8. **两种口径不可互比**：墙钟按秒、步数按步；中档同一题在两种口径下 hard 可相差数十分。
   A/B 或回归必须锁定同一口径、同一预算、同一 `configHash`、同一 `caseExclude`。
9. **未覆盖项**：没有适配器路径基线；没有多 seed 统计（仅 seed=0 + 两次连跑）；
   首题包含 JIT 预热；未做 warm-up 弃跑；未做 GC 日志采集；未测并发多单；
   步数基线没有 LARGE，也没有 S20。

## 7. 防调参纪律

- **禁止只改 seed / 软权重让分数变好看**：任何调参必须提交 A/B，A 与 B 使用**同一批题、
  同一预算、同一机器**，且两边的逐题 `fingerprint` 必须与基线完全一致。
- A/B 必须同时给出 hard 与 soft，并至少包含：步数口径全量（`SMALL:1000,MEDIUM:2000`、
  `caseExclude=L,S20`、`configHash` 与基线一致）连跑两次且分数一致，以及墙钟 42 题的
  对照结果。
- 若必须改题面（订单/工序/日历/预算），视为**新基准**：指纹会变，必须重建基线并在
  报告里标注旧基线的废弃，不允许在同一批指纹上“调”分数。
- 只允许调整求解器参数（预算/构造启发式/移动选择）时，需注明改动点与回归命令；
  任何 PR 若无法用同一批指纹复现 A/B 表，视为无效调参。
- 基线不是通过线：hard=0 题数的变化必须与具体题号一起报告，禁止只报中位数。

## 8. 对 A0 / A1 的需求项

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
6. 步数终止语义已对齐：`maximumStepCount > 0` 时只按步数终止，`terminationMode` 回显
   `STEP_COUNT`/`WALL_CLOCK`。本跑分器已按同语义实现并验证（34 题两次一致）。

**A1（模型/约束/大档）**

1. `buildStartTimeRange` 需按“任务时长是否完整落在窗口内”裁剪候选，或在硬约束上
   对跨窗结束给出可修复路径；否则日历类题天然硬违约（S08/M03/M09/M13/L02）。
2. 大档构造启发式：1,500–3,000 任务在 8s（甚至 60s）内 0 赋值，需要分块/增量构造、
   或按资源先分桶再排任务；同时暴露构造进度指标。
3. 中档 5s 内 `resourceNoOverlap` / `operationDependency` 长期为负（M02/M07/M08 等），
   请确认是预算不足还是模型/移动选择问题；若是模型，请给出可复现的最小题面。
4. 若确认 `primaryResourceOnly`/`operationDependency` 在部分题上不可修复，请在约束层
   提供明确的可达性诊断（而非只给负分）。
5. **S20 计分死循环**：`S20-infeasible-missing-routing` 在步数模式必挂
   （`BavetConstraintSession.calculateScore` → `AbstractGroupNode`，`MIN_MAKESPAN`
   的 `min/max` 聚合；100 步即复现）。请排查 `ApsConstraintProvider` 的
   `groupBy(min(startTime), max(endTime))` 在“缺路由 + 少任务”题面下的传播，或确认
   是否为 Timefold 2.8.0 缺陷并给出规避配置。

## 附录 A：题面指纹清单

`fingerprint` 覆盖 `case.json` 全内容（问题 + 预算 + seed + 期望）。物化问题摘要
`problemDigest` 见 `bench-baseline.json`。

| 题目 | 档位 | fingerprint |
| --- | --- | --- |
| S01-smoke-basic | SMALL | 40ab4acbfb318f336e5c1ec2acb5ae55e7dcd2ca8a0074cab227ac2e89fe0832 |
| S02-smoke-setup-matrix | SMALL | 8d255a7c30f600e223cf7593cca9adea9fb303dd547d4f681e2083b278eae816 |
| S03-smoke-explicit-deps | SMALL | 484454acafb1c7a50f152f7f2ed6de385995676bd14f2072f5da5e4bf6686065 |
| S04-smoke-calendar | SMALL | 575e7989df0c2e6e6013820a1b4d50c1f8a1aaac8ba88e54153dad7011da86e7 |
| S05-smoke-frozen | SMALL | 3e1efcca5514d4dd9032de829c7770ec11ae9c1819663e775f81c9ec83926b1d |
| S06-smoke-secondary | SMALL | 13843af180a012c9ea3b180cc6755d5a980771168312addd5165e4b9804849c7 |
| S07-smoke-priority | SMALL | b4a9c00cc3c341c06d56b5a625937ef88678e168a06c76311785dd3f6cc9d370 |
| S08-smoke-all-features | SMALL | a4600d71fabf4c2a1740ebef0d8e889b23b2f2853e821a6c64fdd3c87328520f |
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
| M03-feasible-20o-calendar | MEDIUM | de2ef5ec1e74498952b981225672174c02a14c04b14c13704ca328ae10a45e4f |
| M04-feasible-25o-setup3fam | MEDIUM | a7441f0d93fb7690c4def44a88542fe67fa3e11c772f9bbc61ffb43249ade1b4 |
| M05-feasible-30o-priority | MEDIUM | fe75dbb54f48666a11bf7134a66bce3739dc24aa8881d90b36a7cf32d1a9f728 |
| M06-feasible-12o-secondary | MEDIUM | 80e858239f530efed5d694e38338c2a2f78059954bf7cbd47f02dcf38cc12783 |
| M07-feasible-18o-deps | MEDIUM | 5ad864955c9b2e9bce6f871c5c2c1a0d530931f3136a7726501c9f595a3203ba |
| M08-feasible-22o-setup | MEDIUM | d9260259c167e366da5a10958e868d654a6d37cf1976da80c82be171bcb82e05 |
| M09-feasible-28o-calendar | MEDIUM | c568ed208118658e8ee4ad230c2e111c8caf9d7a2620d8ff46ee9ea9be598da0 |
| M10-feasible-30o-tightdue | MEDIUM | 3cf1988d0c62b648d972ea220a4ba1f4abb248dd1682b57a925aeb16848b5a5a |
| M11-hard-30o-8op | MEDIUM | e563b87ce6deb0215b881c69f49699fef55e15cbc9553e0cb26a8f4ae6975d3b |
| M12-hard-40o-setup | MEDIUM | 969b2b5ac70c944f7633b48747e63f21aa74012f65d6b532a597e1c043110adf |
| M13-hard-45o-calendar | MEDIUM | e2e7cf365d49b23d2f8b40d3654cb5cb79d4bc0a1e036bd03ad497bd545b9a0c |
| M14-hard-50o-tightdue | MEDIUM | edbd9c4f6251ac5882b01a6c24dea0cfdb995920330ceed2f8a27ea573136887 |
| M15-hard-35o-all | MEDIUM | 303107c89a22a4a0b7fa1199ebd39bdb8eac876c37b63f9b61a0ae643b6f50bf |
| L01-hard-60o | LARGE | 9e859be245158aa004bc783b20af6220867b53922d074ee29e54c7a44dc07053 |
| L02-hard-80o-calendar | LARGE | 52b5412c2c9ce9457892139f4c505025f7552228fbca83f3300a5e7fd456c346 |
| L03-hard-100o | LARGE | 87c03381ef219257bb08650bf89d85cee5aa28fea58ec00ade5e82ab226b425d |
| L04-ref-100o-600t | LARGE | 20ada32909dbd7a45b8b95bf478c4c751aad4227b59bace7aef8e84c4695cab9 |
| L05-ref-150o-900t | LARGE | fddc6613b0268dfc197fe3dc92fcdedea08c84c24e6f98ff18556492dbd416da |
| L06-ref-250o-1500t | LARGE | 92db7476d51c7f19bec11bab5cd56042d61c603679606d9e18e31556f598a787 |
| L07-ref-500o-3000t | LARGE | 6e9b2482377b9906f35121fc1e9aa88f387faa6eeae4ecf07ca0f45d9fa24f7e |
