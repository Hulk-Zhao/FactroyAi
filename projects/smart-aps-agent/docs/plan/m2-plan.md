# M2 并发开发切分方案与开工硬规则

> 状态：Draft（关键默认决定待用户最终确认）
> 作者：M2 规划会话（只读产出）
> 日期：2026-11-04
> 适用范围：M2 共 21 条任务（M2-SE-01..06、M2-BD-01..05、M2-FE-01..07、M2-QA-01..03）
> 纪律：本文件为唯一交付；未执行构建、未执行 `mvn package`、未 commit/push、未认领或修改看板。

---

## 0. 结论

**计划可直接开工**：W1–W9 的目录隔离、依赖前置、迁移号段、Maven 并发规则、D0 契约占位已闭合；D0 冻结后可按批次发放派工单。

**尚差 7 处用户拍板**（详见第 10 节）：

1. 前端 STOMP 依赖如何补齐（受“禁止 npm install”约束）。
2. 库存表在 M2 落库及 `PUT /inventory` 的回滚验收口径。
3. DSL 单数 `constraint:` 兼容别名保留到何时。
4. M2-BD-05 的“9 类实体”具体名单。
5. JWT/API Key 在 M2 的强制范围（RBAC 属 M3）。
6. 订单“释放/开工”端点是否纳入 M2-BD-02。
7. 真实换型矩阵是否进 M2（否则继续静态矩阵 + `setupMatrixFallback`）。

**关键路径**：`M2-SE-03 → M2-SE-04 → M2-FE-03 → M2-FE-02 → M2-QA-02`，约 20 个工作日；FE-03 必须从 D9 与 SE-04 尾部重叠，否则 M2 延期。

**三条红线**：

- 一个文件/目录只能有一个写者；跨流共享文件只读。
- 只有构建管理员可执行 `mvn package`；其余流只跑模块级 `mvn -pl <模块> -am test`。
- D0 未冻结的契约项，对应流不得开工。

---

## 1. M2 目标与验收边界

**一句话闭环**：在 M1「订单→工艺→求解→甘特只读」闭环上，把排产变成可运营链路——策略可配（8 条软约束 + 受控 DSL）→ 求解可异步可观测 → 冻结/增量调整可执行 → 物料可齐套校验 → 结果可发布成版本并可回滚，同时补齐甘特编辑、REST 规范、Excel 与 Docker。

| 能力 | 属 M2 | 明确不属 M2 |
|---|---|---|
| 策略 | FR-SS-001/002 的 8 条核心规则（PRD 1551） | 12/14 条完整规则、策略 A/B 对比 → M3/v0.5 |
| DSL | 受控 DSL：白名单规则 + 参数 + 权重，编译为约束规格 | 任意 `stream` 代码、热插拔 Java 类 → v0.5 |
| 异步求解 | FR-SE-004：提交/进度/取消/终止/降级，单实例队列 | 重试编排、分布式求解 → M3 |
| 增量重排 | 以冻结任务为起点，生成新 solve_task / 新版本 | 全量多策略自动择优 → M3 |
| MRP | FR-MRP-001 基础齐套：BOM 多级、库存/在途、缺料清单、可配置阻塞 | 采购建议优化、SMT 飞达/储能行业特例 → M3/v0.5 |
| 甘特 | 拖拽/锁定/冻结/撤销重做 + 500 订单级验证 | 5000 任务、虚拟滚动、ATP 2 秒 → M3-QA-01/04 |
| 计划版本 | 发布/回滚/自动冻结/快照（FR-GT-005 基础） | MES 下发、邮件/短信/飞书通知 → M3 |
| REST | JWT/API Key、分页、错误码、幂等、限流骨架 | RBAC 角色权限、全量审计 → M3-BE-01/03 |
| Excel | 9 类实体导入导出 | 全量字段模板、异步大文件 → M3 |
| 部署 | Docker 一键 ≤15 分钟 | 生产加固、监控告警 → M3-QA-05 |

**验收口径**：M2 不整体套用 MVP 6.4（PRD 1601-1606）。M2 只取其中 **500 订单 <60s、Docker ≤15min** 两项。

> **2026-11-04 用户裁定**：上述 `500 订单 <60s` 保留为**原始目标**（未达成，原文不改）；该项**移出 M2 门禁**，转后续性能专项。M2 按下方「演示口径」验收。实测 500 单在 60s 与 300s 均未完整收敛，瓶颈在构解约 168s，社区版无多线程。

**M2 演示口径（2026-11-04 用户拍板：先按 demo 展示推进，多线程后续再议）**：

| # | 场景 | 通过条件 |
|---|---|---|
| D1 | M1 演示集 8 单 / 48 任务，不带 `parameters` | 3/3 次 `SOLVED`；`0hard`；`expected/assigned/persisted = 48/48/48`；库内 48 行 |
| D2 | 50 单 / `timeout=60s` | 完整收敛、`0hard`、落库 300 行（实测 elapsed ≈61.3s，含受理与装配开销） |
| D3 | 50 单 / `timeout=5s` | 必须 `TIMEOUT`、`persisted=0`、`engineFallbackDetail=SOLVER_TIMEOUT`，不返回部分解 |

> **范围说明**：`moveThreadCount` / Timefold 企业版多线程**不在当前 M2/M3 范围**。契约语义为「缺省或 `0` = 未指定、走引擎默认」；仅非 0 值尝试应用，并按能力探测在 `metrics` 标注（探测失败按默认处理，不报错）。

**M1 门槛**：看板 `GET /api/tasks` 显示 M1-SE-04、M1-SE-05 为「进行中」，M1-SE-06、M1-QA-03 为「待开始」。D2 18:00 前必须关闭 M1-SE-04/05；D15 前关闭 M1-SE-06/M1-QA-03，否则 M2-QA-02 不得启动。

---

## 2. 工作流清单 W1–W9

### W1 契约与平台（`dev-CONTRACT`）

- **目标**：M2 契约唯一出口 + REST/错误码/幂等/限流骨架。
- **任务**：M2-BD-04；D0 冻结 C19–C29；迁移号段发放。
- **交付物**：`contract-freeze.md` M2 附录（C19–C29）；`docs/api/openapi/*.yaml` 的 M2 增量；`Result/PageQuery/PageResult`、`ErrorCode`、幂等/限流过滤器；根 `pom.xml` 依赖管理（POI、WebSocket、Redis）。
- **写入范围（独占）**：
  - `aps-common/src/main/java/com/hengce/aps/common/{api,exception,idempotency,constant}/**`
  - `aps-web/src/main/java/com/hengce/aps/web/{advice,filter,idempotency}/**`
  - `aps-web/src/main/java/com/hengce/aps/web/config/{OpenApiConfiguration,WebMvcConfiguration}.java`
  - `aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/config/**`
  - `docs/api/**`、`docs/db/schema.md`、根 `pom.xml`
- **禁止进入**：`aps-domain/**` 的持久化实体（枚举/BaseEntity 变更只由本流登记，不得新增 MyBatis 实体）、`aps-solver/**`、`aps-service/**`、`aps-frontend/**`、`db/migration/**`（只发号不写文件）、`deploy/**`、`scripts/**`。
- **依赖前置**：无；D0 当天只做冻结与审计。
- **验收方式**：契约 diff 评审通过；`curl` 验证 401/403/409/429；Swagger 可打开；模块 `mvn -pl aps-web -am test` 通过（仅测试窗口，见第 5 节）。

### W2 策略与 DSL（`dev-SE1`）

- **目标**：策略从“能读”变为“可配、可校验、可回滚”。
- **任务**：M2-SE-01 → M2-SE-02。
- **交付物**：策略 CRUD/克隆/导入导出；8 条软约束与权重；DSL 校验/激活/回滚；`constraint_dsl` 表（V4）。
- **写入范围（独占）**：
  - `aps-solver/solver-dsl/**`（含 `pom.xml`）
  - `aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/constraint/**`
  - `aps-solver/solver-timefold/src/main/java/com/hengce/aps/solver/timefold/model/TaskPlan.java`（唯一写者）
  - `aps-service/src/main/java/com/hengce/aps/service/strategy/**`
  - `aps-web/src/main/java/com/hengce/aps/web/controller/strategy/**`
  - `aps-infrastructure/src/main/java/com/hengce/aps/infrastructure/persistence/strategy/**`
  - `db/migration/V4__constraint_dsl.sql`
- **禁止进入**：`solver-api/**`、`timefold/{setup,calendar,duration,dependency}/**`、`TimefoldModelBuilder.java`、`TimefoldSolverAdapter.java`、`service/schedule/**`、`aps-frontend/**`、其他迁移。
- **依赖前置**：C19 冻结；D3 前 W4 在 `TimefoldModelBuilder` 完成 dueDate/priority/productFamily 映射。
- **验收方式**：8 条规则单测（每条至少 1 正 1 反例）；策略 CRUD 闭环；非法 DSL 返回 `422xx`；激活失败自动回滚；`mvn -pl aps-solver/solver-dsl -am test` 通过。

### W3 求解异步与可观测（`dev-SE3`）

- **目标**：同步求解改为可提交、可观测、可取消、可归因。
- **任务**：M2-SE-03 → M2-SE-05 → M2-SE-06。
- **交付物**：`solve_task` 生命周期；STOMP 进度；取消/终止；幂等；`constraint_justification` 落库；引擎路由与降级标记。
- **写入范围（独占）**：
  - `solver-api` 的**新增文件**（`SolveTaskDetail/SolveProgress/SolveTaskStatus`）
  - `aps-service/src/main/java/com/hengce/aps/service/schedule/ScheduleService.java`
  - 新建 `SolveTaskLifecycleService.java`、`SolveProgressPublisher.java`
  - `aps-infrastructure/.../persistence/schedule/SolveTaskRepository.java`、`ConstraintJustificationRepository.java`（新文件）
  - `aps-web/.../controller/SolveTaskController.java`；新建 `aps-web/.../controller/solve/**`
  - 新建 `aps-web/.../config/SolveProgressWebSocketConfiguration.java`
  - `aps-admin/src/main/resources/application.yml`、`aps-web/pom.xml`
  - `db/migration/V6__solve_task_idempotency.sql`
- **禁止进入**：`solver-timefold/**`（只读消费）、`ScheduleProblemAssembler.java`、`ScheduleResultWriter.java`、`ScheduleRepository.java`、`aps-web/.../controller/gantt/**`、`aps-frontend/**`、V2/V3/V4/V5。
- **依赖前置**：M1-SE-04 于 D2 关闭；C20/C28 冻结；Redis（P0-06）可用。
- **验收方式**：`POST /solve-tasks` 返回 200 且进入 PENDING/RUNNING；取消后 CANCELLED；STOMP 收到 `progress/phase/bestScore`；`constraint_justification` 有非空 `task_ids`；异常/超时返回降级标记。

### W4 增量重排与甘特后端（`dev-SE4`）

- **目标**：让甘特上的调整真正影响下一次求解。
- **任务**：M2-SE-04 + GAP-1（人工调整端点）+ GAP-2（订单甘特读接口）+ GAP-3（MRP 阻塞钩子接入）。
- **交付物**：`/gantt/order`；`/tasks/{id}/adjust`（dryRun/409/undo/redo）；增量重排写新 solve_task/新版本；`task_lock` 列（V5）；冻结/锁定生效。
- **写入范围（独占）**：
  - `solver-api` **既有文件**（`TaskAssignment/ScheduleProblem/SchedulingOrder/SchedulingOperation/ScheduleSolution/SolverAdapter` 等）
  - `solver-timefold/{setup,calendar,duration,dependency}/**`、`model/**`（除 `TaskPlan.java`）
  - `TimefoldModelBuilder.java`、`TimefoldSolverAdapter.java`
  - `aps-service/.../service/schedule/{ScheduleProblemAssembler,ScheduleResultWriter}.java`
  - 新建 `IncrementalScheduleService.java`；`ScheduleRepository.java`
  - `aps-web/.../controller/gantt/**`
  - `db/migration/V5__task_lock.sql`
- **禁止进入**：`solver-dsl/**`、`timefold/constraint/**`、`TaskPlan.java`、`ScheduleService.java`、`service/order/**`、`aps-frontend/**`、V2/V3/V4/V6。
- **依赖前置**：C21/C22/C29 冻结；M2-BD-02 D3；M2-SE-03 D5；M1-SE-04 D2。
- **验收方式**：`/gantt/order` 返回订单泳道/交期/晚交；adjust 的 `version` 过期返回 40901；`dryRun=true` 不落库；增量不覆盖已发布任务；lock 任务在重排中保持时间/资源。

### W5 订单冻结与计划版本（`dev-BD5`）

- **目标**：冻结语义可执行，计划结果可发布、可回滚。
- **任务**：M2-BD-02 → M2-BD-03。
- **交付物**：四态冻结 + 优先级 + 释放/开工自动冻结 + 最小审计；`plan_version`（V3）；发布/回滚。
- **写入范围（独占）**：
  - `aps-service/.../service/masterdata/order/**`
  - `aps-service/.../service/order/**`
  - 新建 `aps-service/.../service/plan/**`
  - `aps-web/.../controller/OrderController.java`
  - 新建 `aps-web/.../controller/plan/**`
  - `aps-infrastructure/.../persistence/order/**`、新建 `persistence/plan/**`、`persistence/audit/**`
  - `db/migration/V3__plan_version.sql`
- **禁止进入**：`service/schedule/**`、`aps-web/.../controller/gantt/**`、`aps-frontend/**`、其他迁移、根 `pom.xml`。
- **依赖前置**：M1-SE-05 D2；C23/C24 冻结；V3 于 D3 应用。
- **验收方式**：四态冻结可查可改；`RELEASED/IN_PROGRESS` 自动冻结任务；`audit_log` 有变更记录；发布生成不可变快照；回滚生成新版本；已执行任务拒绝回滚（40903）。

### W6 MRP 齐套（`dev-MRP`）

- **目标**：回答“这批订单现在缺什么料、能不能排”。
- **任务**：M2-BD-01 + M2-FE-06。
- **交付物**：BOM 多级展开；库存/在途齐套；缺料报告；`inventory + inventory_transit`（V2）；`PUT /inventory`；MRP 页面。
- **写入范围（独占）**：
  - 新建 `aps-service/.../service/mrp/**`
  - 新建 `aps-web/.../controller/mrp/**`
  - 新建 `aps-infrastructure/.../persistence/mrp/**`
  - `db/migration/V2__inventory.sql`
  - `aps-frontend/src/views/mrp/**`、`api/mrp.ts`、`types/mrp.ts`
- **禁止进入**：`router/modules/index.ts`、`api/index.ts`、`mocks/{handlers,data}.ts`、其他前端目录、既有主数据 Mapper（只读复用）、`service/schedule/**`。
- **依赖前置**：C25 冻结；M1-BD-02/03 已完成；M1-SE-05 订单可读；V2 于 D3 应用。
- **验收方式**：多级 BOM 数量与损耗正确；库存扣减与在途抵扣正确；`FULL/PARTIAL/SHORTAGE` 正确；`blocking=true` 时排产被拦；页面可查缺料明细。

### W7 甘特前端（`dev-FE7`）

- **目标**：甘特从只读展示变为可编辑、可验证。
- **任务**：M2-FE-01 → M2-FE-03 → M2-FE-02。
- **交付物**：资源甘特（暂停/依赖/热力/冲突/冻结）；拖拽调整 + 撤销重做；订单甘特。
- **写入范围（独占）**：
  - `aps-frontend/src/components/gantt/**`
  - `aps-frontend/src/views/gantt/**`
  - `aps-frontend/src/api/gantt.ts`、`types/gantt.ts`
  - `aps-frontend/src/mocks/gantt-data.ts`
- **禁止进入**：`src/router/**`、`src/api/index.ts`、其他 `src/views/**`、`src/mocks/handlers.ts`、任何后端目录、`package.json`。
- **依赖前置**：C4 与 M1-FE-06；C22 冻结；W4 调整 API D5；W4 SE-04 D10。
- **验收方式**：拖拽触发 adjust；冲突显示且 422 原因可读；撤销/重做 50 步；500 订单不卡死；冻结任务不可拖。
- **端口**：5181 开发服务器唯一 owner，其他流不得另起。

### W8 前端页面与集成（`dev-FE8`）

- **目标**：策略/进度/计划三页可用，并统一路由/API 出口。
- **任务**：M2-FE-04 → M2-FE-05 → M2-FE-07。
- **交付物**：策略配置页；STOMP 进度面板；计划版本页；`router/modules`、`api/index.ts`、`constants`、`mocks` 的统一合并。
- **写入范围（独占）**：
  - `aps-frontend/src/views/strategies/**`
  - `aps-frontend/src/views/SolveTaskView.vue`
  - `aps-frontend/src/views/plans/**`
  - `aps-frontend/src/api/{orders,solve}.ts`（策略/进度函数）
  - `aps-frontend/src/types/{orders,solve}.ts`
  - `aps-frontend/src/router/modules/**`、`src/api/index.ts`
  - `aps-frontend/src/constants/{enums,error-codes}.ts`
  - `aps-frontend/src/mocks/{handlers,data}.ts`
- **禁止进入**：`components/gantt/**`、`views/gantt/**`、`api/gantt.ts`、`types/gantt.ts`、`views/mrp/**`、`api/mrp.ts`、后端、`router/index.ts`、`package.json`。
- **依赖前置**：C19/C20/C24 冻结；W2 策略 API D6；W3 进度 API D5；W5 计划 API D7。
- **验收方式**：策略规则可增删改、权重和=100；进度条/阶段/最佳分实时；发布/回滚可操作；路由无 404。

### W9 QA / Excel / 部署（`dev-QA2`，兼构建管理员）

- **目标**：M2 交付可导入、可压测、可一键部署。
- **任务**：M2-BD-05 → M2-QA-03 → M2-QA-01 → M2-QA-02。
- **交付物**：9 类实体 Excel；Docker ≤15min；500 订单性能；联调缺陷清单。
- **写入范围（独占）**：
  - 新建 `aps-service/.../service/importexport/**`
  - 新建 `aps-web/.../controller/importexport/**`
  - `scripts/perf/**`、`docs/testing/**`、`deploy/**`、`.gitlab-ci.yml`、`scripts/ci-*`
  - `aps-service/pom.xml`（Excel 依赖）
  - `db/migration/V2-V6` 的**应用**（不是编写）
- **禁止进入**：除 importexport 外任何业务 Controller/Service、`db/migration/**`（只读校验）、`aps-frontend/**`、根 `pom.xml`（依赖向 W1 申请）。
- **依赖前置**：C26 冻结；W1 POI 依赖 D1；V2-V6 D3 应用；W3 D5。
- **验收方式**：9 类模板可下载/可导入/错误行可定位；500 订单 <60s（**原始目标，2026-11-04 已移出 M2 门禁**，转性能专项；M2 改按 §1「演示口径」D1–D3 验收）；`docker compose up` ≤15min；QA-02 输出 P0/P1 缺陷清单。

---

## 3. 写入范围隔离表

### 3.1 共享文件/目录唯一写者

| 共享点 | 唯一写者 | 其他流权限 | 违规后果 |
|---|---|---|---|
| `aps-common/**`（Result/ErrorCode/幂等/常量） | W1 | 只读，提交申请 | 全模块编译冲突 |
| `aps-domain/**`（枚举/BaseEntity） | W1 | 只读 | 分层破坏、双写 |
| `aps-web/.../config/OpenApiConfiguration.java`、`WebMvcConfiguration.java` | W1 | 只读 | Swagger/拦截器互相覆盖 |
| `aps-web/.../config/SolveProgressWebSocketConfiguration.java` | W3 | 只读 | WS 配置被覆盖 |
| `aps-admin/src/main/resources/application.yml` | W3 | 只读，提交配置项 | 启动配置冲突 |
| 根 `pom.xml` | W1 | 只读 | 依赖版本漂移 |
| `aps-service/pom.xml` | W9 | 只读 | Excel 依赖冲突 |
| `aps-web/pom.xml` | W3 | 只读 | WS/Redis 依赖冲突 |
| `solver-dsl/**`、`timefold/constraint/**`、`model/TaskPlan.java` | W2 | 只读 | 软约束/模型双写 |
| `solver-api` 既有 DTO | W4 | 只读；W3 只加新文件 | 增量/异步字段冲突 |
| `solver-api` 新增生命周期 DTO | W3 | 只读 | 进度契约冲突 |
| `ScheduleService.java` | W3 | 只读 | 同步/异步入口冲突 |
| `ScheduleProblemAssembler.java`、`ScheduleResultWriter.java`、`ScheduleRepository.java` | W4 | 只读 | 落库/装配双写 |
| `service/masterdata/order/**`、`service/order/**` | W5 | 只读 | 订单状态机冲突 |
| `service/plan/**`、`persistence/plan/**` | W5 | 只读 | 版本快照冲突 |
| `service/strategy/**`、`controller/strategy/**` | W2 | 只读 | 策略 CRUD 冲突 |
| `service/mrp/**`、`controller/mrp/**` | W6 | 只读 | MRP 逻辑冲突 |
| `service/importexport/**`、`controller/importexport/**` | W9 | 只读 | 导入导出冲突 |
| `controller/gantt/**` | W4 | 只读 | 调整/读接口冲突 |
| `docs/api/**`、`contract-freeze.md`、`docs/db/schema.md` | W1 | 只读 | 契约失真 |
| `db/migration/V2-V6` | 见第 4 节 | 只读 | 迁移号段抢号 |
| `aps-frontend/src/router/modules/**`、`api/index.ts` | W8 | 只读；W6/W7 提交 diff | 路由/导出互相覆盖 |
| `aps-frontend/src/mocks/handlers.ts`、`mocks/data.ts` | W8 | 只读 | Mock 冲突 |
| `aps-frontend/src/mocks/gantt-data.ts` | W7 | 只读 | 甘特数据冲突 |
| `aps-frontend/src/constants/{enums,error-codes}.ts` | W8 | 只读 | 枚举口径冲突 |
| `deploy/**`、`scripts/**`、`.gitlab-ci.yml` | W9 | 只读 | 部署脚本冲突 |
| `target/**` | 构建管理员 W9 | 任何流不得并发构建 | thin jar/构建失败 |

### 3.2 跨流禁入红线

1. **禁止跨流改共享文件**：需要变更时，在每日 10:00/16:00 窗口提交给唯一写者，写者合并后再通知。
2. **禁止双写任务**：开工前检查看板任务 `status/owner`；若已「进行中」且 owner 不是自己，立即停止并上报。
3. **禁止在 `aps-domain` 新增 MyBatis 实体**：持久化实体只放 `aps-infrastructure`；`TaskLockType` 放 `solver-api`，不进 domain。
4. **禁止修改 V1**：V1 已冻结；所有 M2 DDL 只进 V2-V6。
5. **禁止改他人前端文件**：`views/gantt` 与 `views/mrp` 互不进入；路由/导出只由 W8 合并。

---

## 4. 迁移号段预分配（V2–V6）

> 原则：**D2 18:00 前全部迁移文件交齐，W1 于 D3 按 V2→V6 顺序应用**。每条流只写自己的文件，禁止跨段抢号。M1 实际零迁移，contract-freeze §2 的旧预留由 S0 在本文件发布时重发。

| 版本 | 归属流 | 表/字段 | 关键约束 | 回滚点 |
|---|---|---|---|---|
| V2 | W6 MRP | `inventory`（id/tenant/material_id/warehouse_code/on_hand_qty/safety_stock/snapshot_at/version/审计字段）；`inventory_transit`（id/tenant/material_id/qty/expected_date/source_type/status/审计字段） | `(tenant_id, material_id, warehouse_code)` 唯一（`deleted=false`）；`on_hand_qty >= 0`；在途按 `(tenant_id, material_id, expected_date)` 索引 | R2：先关 `aps.mrp.inventory-enabled=false`；数据层用 D2 前 `pg_dump` 快照恢复；新增表可 `DROP`，不改既有表 |
| V3 | W5 计划版本 | `plan_version`（id/tenant/sandbox_id/plan_no/version_no/status/snapshot JSONB/published_at/published_by/rollback_of/reason/task_count/version/审计字段）；`task.plan_version_id`（可空） | `status IN (DRAFT,REVIEW,PUBLISHED,ARCHIVED)`；`(tenant_id, plan_no)` 唯一；`task(plan_version_id)` 索引；不改 `task` 既有列语义 | R3：发布入口开关 `aps.plan.publish-enabled=false`；`task.plan_version_id` 可空，旧数据不受影响；必要时置空后 `DROP plan_version` |
| V4 | W2 策略/DSL | `constraint_dsl`（id/tenant/code/version_no/yaml_text/compiled_spec JSONB/status/checksum/rollback_of/effective_from/created_by/审计字段） | `(tenant_id, code, version_no)` 唯一；同 code 仅一条 ACTIVE 的部分唯一索引；`status IN (DRAFT,ACTIVE,ROLLBACK)` | R4：DSL 解析降级为“仅内置规则”；`constraint_dsl` 可整表 `DROP`；策略表 `strategy_rule` 不动 |
| V5 | W4 增量/甘特 | `task` 新增 `lock_type VARCHAR(16) DEFAULT 'NONE'`、`locked_from`、`locked_to`、`locked_resource_ids JSONB DEFAULT '[]'` | `lock_type IN (NONE,TIME_WINDOW,RESOURCE,FROZEN)`；`locked_to > locked_from`；`(sandbox_id, lock_type)` 部分索引 | R5：`lock_type` 默认 NONE，旧任务行为不变；关闭 `aps.schedule.lock-enabled` 后退化为 `frozen` 布尔 |
| V6 | W3 异步求解 | `solve_task` 幂等唯一索引：`(tenant_id, sandbox_id, idempotency_key) WHERE idempotency_key IS NOT NULL` | 不新增列；索引失败可回退为 Redis 幂等键 | R6：删除索引 + 关闭 `aps.solve.idempotency-db`，改用 Redis |

**不占 M2 号段**：

- `strategy_rule` 已在 V1（`db/migration/V1__init.sql` 677-736），无需迁移。
- `setup_matrix_entry` 不在 V1；M2 继续静态矩阵 + `setupMatrixFallback=true`，**不建表**。若产品要求真实矩阵，需用户拍板后追加 V7，并重排后续。
- `agent_event/agent_decision` 属 M3，不占 M2。

**禁止**：任何流不得修改已应用的 V2-V6；不得新增 V2-V6 之外的文件；V7+ 由 S0 分配。

---

## 5. Maven 并发硬规则

### 5.1 为什么只有构建管理员能跑 `mvn package`

`mvn package` 会写 `<module>/target/classes`、`<module>/target/*.jar`，并在 `aps-admin` 执行 Spring Boot `repackage` 生成 fat jar。多个会话并发构建时：

- 上游模块 `target/classes` 会被交替覆盖，`aps-admin` 可能读到半成品；
- 两个 `repackage` 同时写 `aps-admin/target/aps-admin-*.jar`，后写者可能覆盖成**thin jar**（缺 `BOOT-INF/lib`）；
- 构建失败会留下半成品 `target`，让后续会话误判“已构建成功”。

M1 已实际发生“并发 `mvn` 导致 target 被覆盖成 thin jar”。因此：

- **只有 W9（构建管理员）** 可执行 `mvn package`、`mvn -pl aps-admin -am package`；
- **只有 W1** 可在 D5/D10/D15/D20 18:00 窗口执行全量 `mvn -B -ntp verify`；
- 其他流只执行 `mvn -B -ntp -pl <自己的模块> -am test`，且必须在第 5.3 节的槽位内。

### 5.2 构建槽位

| 时间 | 允许流 | 命令 |
|---|---|---|
| 09:30–10:00 | W2 | `mvn -B -ntp -pl aps-solver/solver-dsl,aps-solver/solver-timefold -am test` |
| 10:30–11:00 | W4 | `mvn -B -ntp -pl aps-service,aps-web -am test` |
| 14:00–14:30 | W3/W5 | `mvn -B -ntp -pl aps-service,aps-web -am test` |
| 14:30–15:00 | W6 | `mvn -B -ntp -pl aps-service,aps-web -am test` |
| 17:30–18:00 | W9 | 构建管理员打包/部署校验 |
| 18:00–19:00 | W1 | 全量 `mvn -B -ntp verify`（仅指定日） |

**禁止**：全局 `mvn clean`；并发 `mvn install`；无 `-am` 的 `mvn -pl aps-admin package`；两人同时跑 `aps-service -am`。

### 5.3 打包后强制校验 fat jar

```powershell
$jar = Get-ChildItem .\aps-admin\target\aps-admin-*.jar | Select-Object -First 1
jar tf $jar.FullName | Select-String 'BOOT-INF/lib/'
```

- 必须存在多个 `BOOT-INF/lib/*.jar`；否则判定 **thin jar**，不得交付。
- 检查 `META-INF/MANIFEST.MF` 含 `Main-Class: org.springframework.boot.loader...`。
- 记录 jar 大小；异常偏小（例如 <10MB）直接判定失败。

### 5.4 `target/` 被覆盖时的止血

1. **立即停手**：通知所有会话停止 Maven/IDE 构建；`Get-Process java` 确认无残留编译进程。
2. **只清理受影响模块**：确认无进程后，`Remove-Item -Recurse -Force <module>\target`；**不要**全仓 `mvn clean`。
3. **单一重建**：由 W9 执行一次 `mvn -B -ntp -pl aps-admin -am package -DskipTests`。
4. **校验**：按 5.3 检查 `BOOT-INF/lib`；失败则再次单权重建，仍失败则保留现场并上报，不得用 thin jar 交付。
5. **记录**：在 `docs/testing/m2-build-incidents.md` 记录时间、模块、命令、结果（W9 维护）。

### 5.5 前端与数据库并发规则

- **禁止 `npm ci` / `npm install`**：`node_modules` 已存在且可能被其他会话占用；缺依赖只报告，不重装。
- **5181 只允许一个 dev server**（W7 owner）；其他流不得另起端口或占用 5181。
- 只有 W8 可在 D18 执行 `npm run build`。
- 每个流使用独立数据库 `aps_m2_<flow>`；共享库只由 W1 应用 V2-V6。

---

## 6. D0 冻结契约项（C19–C29 占位）

> 写入人：W1（`dev-CONTRACT`）。本文件只给占位，不直接修改 `contract-freeze.md`；W1 在 D0 将其写成正式 C19–C29 条目。

| 编号 | 能力 | 必须冻结的端点/字段/枚举/事件 | 提案 | 未冻结后果 |
|---|---|---|---|---|
| **C19** | 策略规则与 DSL | `StrategyRule` 数组、`PUT /strategies/{id}`、导入导出、权重和=100、规则编码枚举；DSL `constraints[]`（单数 `constraint:` 兼容别名）、白名单 rule_code、校验报告、版本/激活/回滚 | W2 | SE-01/02 + FE-04 返工，规则语义双写 |
| **C20** | 异步求解与 STOMP 进度 | `POST /solve-tasks` 返回 200 + `SolveTaskDetail`；状态 `PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT`；`POST /solve-tasks/{id}/cancel`；STOMP `/ws/solve-tasks/{id}`、订阅 `/topic/solve-tasks/{id}`、消息 `{taskId,status,progress,phase,bestScore,elapsedMs,resultTaskId,errorCode}`；与轮询 `GET /solve-tasks/{id}` 的主从关系 | W3 | FE-05、QA-01 阻塞；前后端各写一套 |
| **C21** | 增量重排与冻结/锁定 | `IncrementalSolveRequest{baseSolveTaskId,affectedTaskIds}`；写目标=新 solve_task/新 plan_version，禁止覆盖 PUBLISHED；`TaskAssignment.lockType/pinnedWindow`；`frozen` 与 `lockType` 优先级 | W4 | SE-04/FE-03 返工，可能覆盖已发布计划 |
| **C22** | 甘特人工调整 | `TaskAdjustRequest{operation(MOVE/LOCK/FREEZE/UNFREEZE/SPLIT/MERGE),resourceCode,startTime,lockFrom,lockTo,lockedResourceCodes,reason,version,dryRun}`；40901 版本冲突、422xx 不可行；`POST /tasks/{id}/adjust`、`POST /tasks/adjustments/{id}/undo`、`POST /tasks/adjustments/{id}/redo` | W4 | FE-03 无法开工 |
| **C23** | 订单冻结/优先级/释放 | `PUT /orders/{orderNo}/freeze` 四态 payload（含锁窗/锁资源）；`PUT /orders/{orderNo}/priority`；`PUT /orders/{orderNo}/release`；`RELEASED/IN_PROGRESS` 自动冻结范围；最小审计字段 | W5 | BD-02 只能存状态，自动冻结无触发源 |
| **C24** | 计划版本发布/回滚 | `POST /plans/{id}/publish`（reason/approver）；`POST /plans/{id}/rollback`（targetVersion/reason）；版本号 `PLAN-YYYYMMDD-NNN`；`planId=plan_version.id`；已执行任务回滚 40903；MES 仅占位 | W5 | BD-03/FE-07 返工 |
| **C25** | MRP 与库存 | `PUT /inventory`（批量 upsert）；`POST /mrp/analysis` 输入 `sandboxId/orderNos/includeSuggestions/blocking`；输出 `FULL/PARTIAL/SHORTAGE`、按订单/物料缺料、`earliestRequiredAt`；`availableQty = onHand + transit - safetyStock`；阻塞开关透传 | W6 | BD-01/FE-06 返工，MRP 无数据来源 |
| **C26** | Excel 导入导出 | 9 类实体清单；`GET /{entity}/import/template`；`POST /{entity}/import`（预览/执行）；错误行 `{row,field,code,message}`；大小/行数上限；幂等令牌 | W9 | BD-05 范围争议 |
| **C27** | REST/认证/限流/幂等/错误码 | API Key 与 JWT 并存的校验顺序；40100/40300/42900；`Idempotency-Key`；分页 `page/size/sort` 上限；新增错误码 40901/40903/422xx/50010；M3 RBAC 边界 | W1 | BD-04 与 M3-BE-01 冲突 |
| **C28** | 引擎路由与降级 | Timefold 默认；异常/超时 → 降级；DSL 校验失败 = 422 不降级；降级标记字段；`SolveTaskDetail.metrics` 键名 | W3 | SE-06 语义反复 |
| **C29** | 订单甘特读接口 | `GET /gantt/order?from&to&orderNos`；订单泳道、任务条、交期线、晚交标识、任务跳转字段 | W4 | FE-02 无法开工 |

**编号规则**：

- C19–C29 为新号，不改 C1–C17 的既有编号与结论。
- 若 C19 与 C12 的 `ConstraintSpec` 快照描述冲突，**不改 C12 编号**，只在 C12 顶部加一行“DSL 语法以 C19 为准”；C12 仍保留冻结/换型语义。
- 若 C20 与 `solve.yaml` 现有 WebSocket 描述冲突，以 C20 为准并同步 `solve.yaml`；`solve.yaml` 原声明见第 8.3 节。

---

## 7. 开工批次

> 契约层面：D0 已冻结 C19–C29，**没有流需要等另一条流“冻结契约”**；等待的是“接口实现交付”。

| 批次 | 时间 | 参与流 | 首批任务 |
|---|---|---|---|
| B0 | D0 | W1 | 冻结 C19–C29、审 V2–V6 DDL、发号 |
| B1 | D1 | W1/W2/W3/W4/W5/W6/W7/W8/W9 | W1 BD-04；W2 SE-01；W3 SE-03；W4 GAP-2 + 装配器；W5 BD-02；W6 BD-01；W7 FE-01 只读；W8 FE-04 骨架；W9 BD-05 |
| B2 | D3 | W4/W5/W7 | BD-02 完成后：W4 开 GAP-1；W5 开 BD-03；W7 冻结可视化 |
| B3 | D5 | W4/W8/W9 | SE-03 完成后：W4 SE-04；W8 FE-05；W9 QA-01 |
| B4 | D6 | W8 | SE-01 完成：FE-04 切真实 API |
| B5 | D7 | W8 | BD-03 完成：FE-07 |
| B6 | D8 | W6 | BD-01 完成：FE-06 |
| B7 | D10 | W7 | SE-04 完成：FE-03 增量模式 |
| B8 | D12 | W9 | V2-V6 均已应用、SE-02 完成：QA-03 Docker 重跑 |
| B9 | D17 | W9 | 全链路冻结：QA-02 |
| B10 | D20 | 全员 | 交付/缺陷清零 |

**批次顺序图**：

```text
D0  W1(冻结)
     |
D1  W1  W2  W3  W4(前置)  W5  W6  W7  W8  W9
     |   |   |      |       |   |   |   |   |
D3  |   |   |      +-------W5  |   |   |   |
     |   |   |      W4(调整)  W5(计划) W7(冻结)
     |   |   |          |             |
D5  |   |   +----------+             |
     |   |   W4(SE-04) W8(FE-05) W9(QA-01)
D6  |   +---W8(FE-04 真实)
D7  |       W8(FE-07)
D8  +-------W6(FE-06)
D10         W7(FE-03)
D12         W9(QA-03)
D17         W9(QA-02)
D20         交付
```

**硬门槛**：

- M1-SE-04/05 未关闭 → W3/W5 的 D2 任务只能做骨架，不得宣称完成。
- V2-V6 未应用 → W2/W4/W5/W6 不得进入真实库联调。
- C19–C29 未冻结 → 对应流 D1 不发放。

---

## 8. 已定默认决定（按此执行，标注“待用户最终确认”）

### 8.1 库存落表：V2 + `inventory` + `inventory_transit` + `PUT /inventory`

**决定**：

- V2 新增 `inventory`、`inventory_transit`；`PUT /api/v1/inventory` 批量 upsert；MRP 读取时按 `availableQty = onHand + transit - safetyStock` 计算。
- `PUT /inventory` 请求体：`{items:[{materialCode,warehouseCode,onHandQty,safetyStock,snapshotAt}]}`；响应 `Result`；错误码 40400（物料不存在）、42200（数量为负）、40901（版本冲突）。
- 不在 M2 做库存维护页面；W6 的 `PUT` 供 W9 Excel/脚本灌数。

**兼容**：只新增表与端点，不改既有 `material` 表；旧代码不读新表，行为不变。

**回滚点 R2**：

1. 应用开关 `aps.mrp.inventory-enabled=false`，MRP 退回“请求体快照/静态样例”模式；
2. 数据层用 D2 前的 `pg_dump` 快照恢复；新增表可直接 `DROP`；
3. 不删除 V2 迁移文件、不改 Flyway 历史；共享库回滚只由 W1 执行。

**待用户最终确认**：是否接受“库存只落表、不做维护页”的 M2 范围。

### 8.2 策略 DSL：`constraints[]` 为规范，`constraint:` 为兼容别名

**决定**：

- C19 规定 `constraints` 为数组；解析器同时接受单数 `constraint:`（对象），内部转为单元素数组；序列化永远输出 `constraints[]`。
- 若 PRD 9.2 文本与 FR-SE-005 示例存在单/复数差异，以 C19 数组为准；PRD 不改。
- 既有 `contract-freeze.md` 没有 DSL 语法冻结条目；C12 仅约定 `List<ConstraintSpec>` 透传（见 C12 §17），**不冲突、不重编号**。C19 新增后，在 C12 顶部加一行“DSL 语法以 C19 为准”。

**兼容**：`constraint:` 别名至少保留到 M2 结束；M3 若清理，需产品确认并保留一个版本。

**回滚点 R4**：解析器开关 `aps.dsl.legacy-singular=true`；DSL 解析失败自动回退“仅内置规则”，`constraint_dsl` 可整表回滚/停用。

**待用户最终确认**：别名保留期限（建议到 M2 结束）。

### 8.3 进度推送：STOMP（沿用 `solve.yaml` 声明），轮询为兜底

**决定**：

- 端点：`/ws/solve-tasks/{id}`（STOMP over WebSocket；`{id}` 为 `solve_task` BIGSERIAL id）。
- 订阅地址：`/topic/solve-tasks/{id}`；连接后服务端立即推送一条当前快照。
- 消息体（C20）：

```json
{
  "taskId": 123,
  "status": "RUNNING",
  "progress": 0.42,
  "phase": "LOCAL_SEARCH",
  "bestScore": "-12hard/-345soft",
  "elapsedMs": 1830,
  "resultTaskId": null,
  "errorCode": null,
  "message": "正在优化"
}
```

- 与轮询关系：`GET /solve-tasks/{id}` 是事实源；STOMP 仅推送，不替代查询。WS 断线后前端回退 2 秒轮询；重连后以快照为准，不重放历史消息。
- 后端依赖：`spring-boot-starter-websocket`、`spring-boot-starter-messaging`（W3 写 `aps-web/pom.xml`）。
- 前端依赖：`@stomp/stompjs`。**当前 `package.json` 未声明，且项目纪律禁止 `npm install`**——这是开工前必须拍板的点。若不允许安装，前端用原生 WebSocket + `src/utils/stomp.ts` 手写 STOMP 帧，不改 `package.json`。

**兼容**：原有同步 `POST /solve-tasks` 行为不变；轮询客户端无需改动。

**回滚点 R20**：`aps.solve.progress-push=stomp|poll`；设为 `poll` 时关闭 WS 端点，前端自动轮询；错误码 `50010` 仅用于 WS 通道故障，不影响查询。

**待用户最终确认**：是否允许在前端专用窗口安装 `@stomp/stompjs`，或接受手写 STOMP 帧。

### 8.4 M2-SE-06 收紧：只做“引擎异常/超时降级 + 降级标记”

**决定**：

- **不新建规则引擎**。降级触发：Timefold 抛异常、超时（终止后无可行解）、solver 不可用。
- 降级结果：优先返回已有可行解；没有则返回 `FAILED`，但必须带降级标记。
- 降级标记落在 `solve_task.metrics`（JSONB），键名：
  - `engineFallback: true`
  - `engineFallbackReason: "TIMEOUT|ENGINE_ERROR|SOLVER_UNAVAILABLE"`
  - `fallbackMode: "HARD_ONLY"`（M2 只有硬约束可行解）
  - `fallbackAt`（UTC OffsetDateTime）
- 接口透传：`GET /solve-tasks/{id}` 的 `SolveTaskDetail.metrics` 原样返回；列表接口可返回摘要 `degraded=true`。
- 前端展示：进度/结果页显示黄色“降级”徽标，tooltip 展示原因；不得显示为绿色成功；导出/发布页在计划版本上保留 `degraded` 字段。
- DSL 校验失败不降级，返回 `422xx`（与 PRD BR-SE-005-04 一致）。

**兼容**：只增加 `metrics` 键，不改表结构；旧客户端忽略未知键。

**回滚点 R28**：开关 `aps.solve.fallback-enabled=false` → 异常/超时直接 `FAILED`，不产生降级标记；已落库的 `metrics` 键保留，前端按 `engineFallback` 渲染。

**已裁定（2026-11-04）**：不允许以“部分订单未排”收口为 `SOLVED`——见 `docs/api/contract-freeze.md` C20「终态判定：任务覆盖率与『`SOLVED` 即已落库』」：覆盖不完整必须落 `FAILED`，降级解不得包装成 `SOLVED`。M2 的降级模式只产出 `HARD_ONLY`，`PARTIAL` 仍留 M3。

---

## 9. 风险与红线

| 风险 | 事实/依据 | 应对 |
|---|---|---|
| Timefold 版本口径 | PRD 要求 1.35+（PRD 165），工程实际 2.8.0（`pom.xml` 38） | M2 锁定 2.8.0，不升级；D1 做 `SolverManager` 兼容冒烟 |
| 社区版商业特性 | `SolutionManager.analyze()` 不可用（`ConstraintJustifications.java` 14-18） | SE-05 复用“重放约束归因”，不承诺 `ConstraintMatchTotal` |
| 异步线程/内存 | 同步 `Solver.solve` 无生命周期 | 每 sandbox 仅 1 个 RUNNING；有界队列；取消用 `terminateEarly`；OOM 标 FAILED |
| 甘特 5000 任务 | M1 自研 DOM 渲染 | M2 只验 500 订单；按时间窗/资源分页；5000 留 M3-FE-04 |
| DSL 解析安全 | 任意 `stream` 等同远程代码 | 白名单 rule_code + 参数；YAML safe load；大小限制；禁止类加载 |
| `target` 并发 / thin jar | M1 已发生 | 构建管理员 + 时间槽 + `jar tf` 校验；thin jar 不交付 |
| 双写 | M1 工序模版、日历两次双写 | 任务 owner 声明 + 目录硬隔离 + 开工前看板检查 |
| 迁移乱序 | 多流并发写 Flyway | D2 全量交齐、W1 统一应用；V2-V6 连续无缺口 |
| 契约缺口 | 调整/订单甘特/库存/策略 PUT/计划字段不足 | C19-C29 冻结；GAP-1..6 明确 owner |
| RBAC 与调整权限 | FR-GT-004 要求 `SCHEDULE_ADJUST`，RBAC 属 M3-BE-01 | M2 只做权限占位/开发默认放行；M3 接真实 RBAC |
| 规则兜底不存在 | OR-Tools 仅骨架（`OrtoolsSolverAdapter` 15-18） | SE-06 收紧为异常/超时降级，不新建规则引擎 |
| M1 欠账 | M1-SE-04/05 进行中、SE-06/QA-03 待开始 | D2 18:00 关闭 SE-04/05；D15 关闭 SE-06/QA-03 |

---

## 10. 开工前必须由用户拍板的点

1. **前端 STOMP 依赖**：允许安装 `@stomp/stompjs`（专用窗口，且不能与 M1 全量 `mvn verify` 并行），还是接受原生 WebSocket 手写 STOMP 帧（不改 `package.json`）。
2. **库存 M2 范围**：确认 V2 落表 + `PUT /inventory`，且不做库存维护页；否则 MRP 只能请求体快照。
3. **DSL 单数别名**：`constraint:` 保留到 M2 结束即可，还是需要长期兼容。
4. **9 类 Excel 实体**：按现有 M1 能力建议 `materials/boms/resources/calendars/operationTemplates/routings/orders/strategies` + 1 类待定（customers 或 suppliers）；需点名。
5. **JWT/API Key 强制范围**：M2 是否全模块强制；若强制，前端登录页属 M3-FE-02，QA-02 只能用 API Key/测试令牌。
6. **订单释放/开工端点**：`PUT /orders/{orderNo}/release` 是否纳入 M2-BD-02；若不纳入，自动冻结无法演示。
7. **真实换型矩阵**：M2 继续静态矩阵 + `setupMatrixFallback`，还是追加 V7 `setup_matrix_entry` 并接受工期/号段变更。

---

## 11. 引用

- 看板：`GET https://hyr3gqe9.qwenwork.host/api/tasks`（79 条；M2 21 条；M1-SE-04/05 进行中、M1-SE-06/QA-03 待开始）。
- PRD：`mfg-aps-platform-PRD.md`（M2 里程碑 1619、MVP 验收 1601-1606、8 条规则 1551、FR-SE-004 930-942、FR-GT-005 1204-1218、FR-MRP-001 1224-1240）。
- 契约：`docs/api/contract-freeze.md`（迁移号段 71-89、C12 746-751、C17 1019-1058、待产品确认 1062-1066）。
- Schema：`docs/db/schema.md`（已知缺口 491-500）。
- 迁移：`db/migration/V1__init.sql`（orders 561-598、sandbox 603-627、task 632-672、strategy 677-736、solve_task 741-775、justification 781-802、adjustment 807-831、bom 254-315）。
- OpenAPI：`docs/api/openapi/solve.yaml`（STOMP 8/14-18、SolveProgress 373-386）；`gantt.yaml`（adjust/plan）；`orders.yaml`（策略 229-285）；`materials.yaml`（MRP 1147-1175、2131-2174）。
- 代码：`aps-solver/solver-timefold/.../ConstraintJustifications.java`（14-18）；`aps-solver/solver-ortools/.../OrtoolsSolverAdapter.java`（15-18）；`aps-domain/.../FreezeStatus.java`（四态）；`pom.xml`（38，Timefold 2.8.0）。

---

## 附录 A：21 条任务依赖与关键路径

> 说明：以下为“真实前置”，不是照抄看板 deps。D1 = 首个开发日；窗口按 20 个工作日估算；浮时以关键路径为基准。

| 任务 | 看板 deps | 真实前置 | 人天 | 窗口 | 浮时 |
|---|---|---|---|---|---|
| M2-SE-01 | M1-SE-03 | 策略/规则表 V1；C19；`TaskPlan` 字段由 W4 D3 映射 | 6 | D1-D6 | 4 |
| M2-SE-02 | M2-SE-01 | C19；V4；DSL 解析/校验 | 6 | D7-D12 | 4 |
| M2-SE-03 | M1-SE-02、P0-06 | M1-SE-04；V6 幂等；C20；Redis/WS | 5 | D1-D5 | 0 |
| M2-SE-04 | M2-SE-03、M2-BD-02 | C21；V5；调整 API；装配器 | 5 | D6-D10 | 0 |
| M2-SE-05 | M2-SE-03 | C28；`constraint_justification`；归因重放 | 2 | D6-D7 | 10 |
| M2-SE-06 | M2-SE-03 | C28；异常/超时降级（不建规则引擎） | 3 | D8-D10 | 8 |
| M2-BD-01 | M1-BD-03 | M1-BD-02、M1-SE-05；V2；C25；阻塞钩子 | 5 | D1-D5 | 4 |
| M2-BD-02 | M1-SE-05 | 四态冻结表；C23；释放/开工端点；最小审计 | 3 | D1-D3 | 2 |
| M2-BD-03 | M2-SE-04 | 实际只依赖 M1-SE-04 + BD-02 + V3；看板依赖过强 | 4 | D4-D7 | 6 |
| M2-BD-04 | P0-08 | C27；M3 RBAC 边界；ErrorCode | 3 | D1-D3 | 13 |
| M2-BD-05 | M1-BD-02 | C26；9 类清单；Excel 依赖 | 4 | D1-D4 | 13 |
| M2-FE-01 | M1-FE-06、SE-04 | C4；M1-SE-04；BD-02；C22；SE-04 可选 | 8 | D1-D8 | 0 |
| M2-FE-02 | M2-FE-01 | C29 `/gantt/order` | 4 | D14-D17 | 0 |
| M2-FE-03 | M2-FE-01、BD-02 | C22；W4 调整 API D5；SE-04 D10 | 5 | D9-D13 | 0 |
| M2-FE-04 | M2-SE-01 | C19 策略规则数组/PUT/导入导出 | 4 | D1-D4（D6 切真实 API） | 6 |
| M2-FE-05 | M2-SE-03 | C20 STOMP 进度；轮询兜底 | 3 | D5-D7 | 8 |
| M2-FE-06 | M2-BD-01 | C25；V2 库存来源 | 3 | D6-D8 | 8 |
| M2-FE-07 | M2-BD-03 | C24 版本/发布/回滚 | 3 | D8-D10 | 6 |
| M2-QA-01 | M2-SE-03 | 500 订单数据；C28 降级；指标 | 4 | D8-D11 | 6 |
| M2-QA-02 | M2-FE-01、BD-03 | FE-02/03/04/05/06/07 + M1-SE-06/M1-QA-03 | 4 | D17-D20 | 0 |
| M2-QA-03 | M1-QA-02 | V2-V6；Redis/WS；Docker ≤15min | 3 | D12-D14 | 4 |

**关键路径**：`SE-03(5) → SE-04(5) → FE-03(5) → FE-02(4) → QA-02(4)`；FE-03 与 SE-04 尾部重叠，整体 20 个工作日。若 FE-03 等 SE-04 完全结束才启动，路径变为 23 人天，M2 必延期。
