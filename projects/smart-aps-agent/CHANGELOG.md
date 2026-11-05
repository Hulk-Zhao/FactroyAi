# 变更记录

本项目当前**尚未创建版本 tag**。下面按里程碑记录功能范围，版本号与发布日期待项目负责人确认后补充。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 计划
- 发布首个正式版本并创建 Git tag。
- 确认 LICENSE 类型后补入仓库根目录。
- 补齐约束 DSL 与求解器的接线、换型矩阵与维护计划接入（见「已知限制」）。

## [0.1.0] - 未发布

### Added

- **工程基座**：Java 21 + Spring Boot 3.3.13 Maven 多模块（`aps-common` / `aps-domain` / `aps-infrastructure` / `aps-solver` / `aps-service` / `aps-web` / `aps-admin`），前端 `aps-frontend`（Vue 3 + Vite + Element Plus + Pinia）。
- **主数据**：客户、供应商、物料分类/物料、BOM、资源、日历、工序模板、工艺路线（节点/依赖边）、订单、策略的查询与维护接口；物料/BOM/工艺/资源/日历等支持 Excel 导入导出（`/{entity}/import`、`/{entity}/export`、`/{entity}/import/template`）。
- **订单状态机**：`DRAFT → CONFIRMED → SCHEDULED → RELEASED → IN_PROGRESS → COMPLETED / CANCELLED`，支持确认、释放、开工、冻结/解冻、优先级调整；释放/开工自动把订单下 `PLANNED` 任务置为 `FROZEN`。
- **排产求解**：Timefold Solver 2.8.0 适配；9 条硬约束（未排位任务、资源不重叠、日历可用、工序依赖、冻结资源/开始时间、主资源限定、C21 时间窗锁/资源锁）；`FS/SS/FF` + `lagMinutes`；三种产能模式（`UNIT_PER_HOUR` / `SECONDS_PER_UNIT` / `BATCH_SECONDS`）。
- **软约束**：8 条（`MIN_TARDINESS`、`EDD`、`SPT`、`SAME_PRODUCT_CONTINUOUS`、`MIN_SETUP_TIME`、`BALANCE_LOAD`、`PRIORITY_RESPECT`、`MIN_MAKESPAN`），权重合计 100 时生效，否则仅硬约束。
- **约束 DSL**：`POST /api/v1/constraint-dsls/validate` 校验；创建生成 SHA-256 checksum 与 `compiled_spec`；`activate / rollback` 生命周期；错误码 `42201`–`42207`。
- **异步求解任务**：`POST /api/v1/solve-tasks` 单工作线程 + 有界队列（默认 32）；状态 `PENDING/RUNNING/SOLVED/FAILED/TIMEOUT/CANCELLED`；STOMP 进度推送；结果写入 `task`、`constraint_justification` 与 `plan_version`。
- **平台能力**：JWT（HS256，`APS_USER` 角色）与 dev API Key 双模式；`tenantId` claim 租户隔离；RBAC 权限注解；审计；固定窗口限流（默认 100 次 / 60 秒）；全局 `Idempotency-Key` 幂等（8–128 字符，24 小时）；`X-Trace-Id` 贯穿日志。
- **前端**：登录、主数据管理、订单、排产任务、Gantt、约束/策略配置等页面。
- **文档与脚本**：用户手册、部署运维、架构、API、约束 DSL、行业模板指南；`scripts/seed-m1-demo.ps1` 储能演示种子、`scripts/seed-m2-perf.ps1` 性能种子、`scripts/seed-m3-customers.ps1` 客户种子、本地数据库与一键栈脚本。

### Changed

- 安全默认值收紧：`aps.security.mode` 默认 `prod`，JWT secret 默认为空，dev API Key 默认不启用。
- 求解异步开关 `aps.solve.async-enabled` 代码默认 `true`。

### Known limitations

- 排产不展开 BOM，不发料、不校验齐套/库存。
- 一个工艺路线节点对应一个任务，不支持订单拆分、合批、联产品/副产物。
- 约束 DSL 的 `compiled_spec` 只存储与激活，未驱动求解；软权重实际来自策略规则或请求 `parameters.softWeights`。
- 换型矩阵 `SetupMatrixResolver` 已实现，但装配层未加载矩阵；`resource.maintenance_plan` 与 `material.industry_attrs` 不参与求解。
- OR-Tools 适配器为占位实现，`ortools` 引擎需显式开启，否则回退或返回 `50010`。
- 引擎异常只标记指标（`engineFallback` / `degraded`），不会自动换引擎重跑。
