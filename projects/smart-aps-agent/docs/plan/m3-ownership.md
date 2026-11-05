# M3 负责人与交付留痕台账

> 更新：2026-11-04 · 看板：`https://hyr3gqe9.qwenwork.host/`（M3 分类，共 13 张卡）
> 本文件是 owner 与证据的**事实来源**；看板 `owner` 与本文不一致时，以本文为准并回头修看板。
> 仅登记 `status` / `owner` 两个字段的同步结果；未改契约正文、未动代码与迁移。

## 1. 卡与负责人

| task_no | 标题 | status | owner | 交付流 | 证据（可逐条追溯） |
|---|---|---|---|---|---|
| M3-BE-01 | RBAC 用户/角色/权限（FR-SYS-001） | 已完成 | `dev-W1` | W1 | `AuthServiceTest` 7、`UserServiceTest` 1、`JwtTokenServiceTest` 5、`PermissionInterceptorTest` 7，均在整仓门禁 **370/0/0/0**（`Skipped=0`）中执行 |
| M3-BE-02 | Sandbox 场景管理（FR-SYS-003） | 已完成 | `dev-W5` | W5 | `SandboxLifecycleRealDbIntegrationTest` **3/0/0/0**；`SandboxComparisonCalculatorTest` 9；`SandboxServiceTimeZoneConfigTest` 2 |
| M3-BE-03 | 审计日志（FR-SYS-004） | 已完成 | `dev-W1` | W1 | `AuditLogServiceTest` **5/0/0/0** |
| M3-BE-04 | 多租户字段与过滤预留 | 待开始 | （空） | **待确认** | 未开工，无代码/测试证据。**注意**：看板卡标题与 `docs/plan/m3-plan.md §1.5` 的 `M3-BE-04`（`constraint:` 别名清理）不一致，需 D0 复核后再派 owner |
| M3-FE-01 | 登录/用户/角色管理页面 | 进行中 | `dev-W8` | W8 | `type-check` / `lint` / `build` 全 `EXIT=0`；产物 **37 文件**；浏览器走查 **5/5 PASS** |
| M3-FE-02 | Sandbox 对比视图 | 进行中 | `dev-W8` | W8 | 同上：`type-check` / `lint` / `build` 全 `EXIT=0`；产物 **37 文件**；浏览器走查 **5/5 PASS** |
| M3-FE-03 | 审计日志查询页面 | 进行中 | `dev-W8` | W8 | 同上：`type-check` / `lint` / `build` 全 `EXIT=0`；产物 **37 文件**；浏览器走查 **5/5 PASS** |
| M3-FE-04 | 甘特图性能优化与 UI 打磨 | 已完成 | `dev-W7` | W7 | 5000 任务 P95 **1506.2ms**、滚动 **58.5 FPS**、DOM **414 节点**（W7 实测） |
| M3-QA-01 | 集成/E2E 测试 | 进行中 | `qa-GATE` | W9 | 整仓真库门禁 **370/0/0/0**；`aps-service` 常规 `mvn test` **311/0/0/0** |
| M3-QA-02 | 非功能验收测试 | 进行中 | `qa-PERF` | W9 | `docs/perf/m2-qa-01-perf.md §12` 容量基线（≤50 单/300 任务；500 单 60s 时 `assignedTaskCount=0`） |
| M3-QA-03 | 安全扫描与加固 | 进行中 | `qa-SEC` | W9 | 进行中：派工范围＝依赖清单/密钥扫描/接口鉴权复核；尚无扫描报告或测试实数 |
| M3-QA-04 | 种子客户真实数据验证 | 进行中 | `qa-SEED` | W9 | 进行中：等待种子客户真实数据导入与反馈；尚无执行记录 |
| M3-QA-05 | 文档输出 | 进行中 | `qa-DOCS` | W9 | 进行中：用户/开发/部署手册尚未产出；仅契约与计划文档有增量 |

## 2. 留痕规则

1. 每个流的汇报必须包含三件事：**改了什么文件**（路径级别）、**证据是什么**（测试实数 `x/y/z`、HTTP 原文、构建哈希或产物清单）、**归属哪个 owner**（上表 `owner` 值）。
2. 证据只接受可复核形式：测试类名 + 通过/失败/跳过计数、命令原文与其输出、HTTP 状态与响应 body、构建产物文件数与哈希；**不接受**「已完成」「已联调」这类结论词单独成证。
3. 未产生可复核证据的卡，`status` 不得置为 `已完成`；最多置 `进行中` 并在此表写明缺什么证据。
4. 看板 `owner` 与本表不一致时，以本表为准，并在同一次汇报里把看板改回（仅 `status`/`owner` 两个字段可写）。
5. 新增卡或改派 owner，先更新本表再改看板；不得只改看板。
