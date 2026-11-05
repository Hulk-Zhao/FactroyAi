# M2 任务台账（m2-todo）

> 共享台账：各流只维护自己的小节，不删改他人条目。证据一律带时间戳。
> 采集时刻见每条备注；共享库有并发写入，数量类结论必须带当时计数。

## W9 / dev-QA2（导入导出 + 构建管理员 + 18085 联调）

| # | 任务 | 状态 | 证据 |
|---|---|---|---|
| 1 | M2-BD-05 模板下载（9 类） | 已完成 | 06:13:34–06:13:35 下载 9/9，HTTP 200；列数 8/9/9/8/7/8/7/5/6 |
| 2 | M2-BD-05 导入 preview/execute（9 类可执行） | 已完成 | 06:07/06:08/06:13 实测；materials/suppliers/strategies/orders/resources/operationTemplates 早前闭环，boms/calendars/routings 06:13 真落库 |
| 3 | 三实体复用服务校验 | 已完成 | 日历 `shifts` 非 JSON → 42200 row2；`RESOURCE` 缺 resourceCode → 42200；路线重复 `seqNo` → 422 `42200`；路线成环 → 422 `42200`；孤立节点 → 422 `42200` |
| 4 | 数据导出（9 类 `GET /export/{entity}`） | 已完成 | 06:15:52 全 200；列顺序与模板 9/9 一致；数据行数 materials 34 / boms 15 / resources 14 / calendars 10 / opTemplates 6 / routings 12 / orders 13 / strategies 1 / suppliers 2 |
| 5 | 导出→回填→再预览闭环 | 已完成 | materials 34/34 通过；routings 12/12 通过 |
| 6 | 幂等令牌 | 已完成 | 同令牌 replay 返回 `replayed=true`；新令牌 `skipped=1`（实体级） |
| 7 | 构建管理员机制 | 已完成 | `docs/testing/m2-build-incidents.md` 建立；打包前 `Get-Process mvn` 互斥；打包后 `jar tf`：06:15:04 包 181,505,400 B，`BOOT-INF/lib` 112 条 |
| 8 | Flyway V3 校验和修复 | 已完成 | 06:02 `flyway repair`；此后 `Successfully validated 6 migrations`，schema v6；历史 checksum `946499021` |
| 9 | 18085 接管（`dev` 模式） | 已完成 | 06:15:12 PID 27176；`/health` 200；认证三连 401 `40100` / 400 / 200；`Ambiguous mapping` 0 |
| 10 | 共享库测试残留清理 | 已完成 | 06:17:58：`M2-AUDIT-001/-003/M2-OFF-001` 软删；`PLAN-AUDIT-001` 删除（9 条任务回置 null）；清理后 materials_active=36、fixture_left=0、plan_versions_active=0、tasks_total=57 |
| 11 | 18085 重建（含 solve 契约统一后） | 已完成 | W1 稳定（SolveTaskController 06:20:53、ViewAssembler 06:20:30）后 **06:24:50** `mvn -B -ntp -o -pl aps-admin -am package -DskipTests` BUILD SUCCESS（12.464s），jar 181,507,583 B，`BOOT-INF/lib` **112 条**；**06:25:04** 重启 PID **26856**（`--aps.security.mode=dev`）；`/health` 200、`Ambiguous mapping` 0、Flyway `Successfully validated 6 migrations` / schema v6；`GET /solve-tasks/1` 200 统一 `SolveTaskDetail`（`status=SOLVED`，无 `SUCCESS`）；`POST /materials/import?mode=preview` 200（34/34）、`GET /export/materials` 200、`GET /gantt/order` 200；认证三连 401 `40100` / 400 `40000` / 200 |
| 12 | checkstyle/PMD 绑定执行（importexport 新增文件） | 已完成 | 07:53:24 `mvn -o -B -ntp -pl aps-web,aps-service checkstyle:check@checkstyle-check`（走根 pom 绑定配置）BUILD SUCCESS：aps-service **0 violations**、aps-web **0 violations**（`ApiSecurityFilter:260` 行宽已由 owner 修复） |
| 16 | Flyway 漂移逐版比对（不只假设 V3） | 已完成 | 07:54:43 独立空库 `aps_flyway_probe` 跑 `migrate` 复算：V1 `1119985351` / V2 `-1452760567` / V3 `946499021` / V4 `-109341391` / V5 `1459339503` / V6 `1790536655`，与 `aps_local` 历史表 **6/6 一致**；仅 V3 曾漂移（`1198800526`→`946499021`），其余五版从未漂移；07:55:02 `flyway:validate` 通过；空库已 drop |
| 17 | 默认配置（Flyway 开启）启动验证 | 已完成 | 08:02:06 独立实例 18090（PID 30000，无 `spring.flyway.enabled=false`）：`Successfully validated 6 migrations`、`Current version of schema "public": 6`、`Schema "public" is up to date. No migration necessary.`、`Started ... 4.98 seconds`、`/health` 200；验证后已停 |
| 18 | 18085 制品损坏修复 | 已完成 | 07:58:30 制品被非 W9 的 `package` 覆盖成瘦包（24,975 B / 25 条目 / `BOOT-INF/lib` 0，运行中实例出现 `ThrowableProxy` CNFE）；08:00:00 W9 停服重打包 181,521,188 B / **112 条**（SHA256 `7F29E980…844DBC`）；08:02:24 18085 重启 PID **2024**（dev，Flyway 开启，v6 up to date，`Ambiguous mapping` 0，`/health` 200） |
| 19 | 制品级重建（当前源码：solve phase 映射 + `@JsonInclude(ALWAYS)` + `requireDisjointPinnedOrders`） | 已完成 | 08:32:24 打包：181,522,139 B、258 条目、`BOOT-INF/lib` **112 条**、SHA256 `7D13922B…2BEF`；08:32:40 18085 重启 PID **28496**（dev）：`Successfully validated 6 migrations`、`Current version of schema "public": 6`、`Schema "public" is up to date`、`Ambiguous mapping` 0、`/health` 200 |
| 13 | M2-QA-03 Docker ≤15min | 未开始 | — |
| 14 | M2-QA-01 500 订单性能 | 未开始 | — |
| 15 | M2-QA-02 联调缺陷清单 | 未开始 | 已有素材：`/materials/import` 415、`/{entity}/export` 抢路由、`/orders/export` 真歧义 500、BOM 写路径无环检测 |

### W9 已知缺口 / 需归属方处理

- `BomService.create` 无循环依赖校验（仅 `expand` 读路径有），BD-03 归属。
- `GET /{entity}/export` 被 `GET /{entity}/{id}` 抢占；`/orders/export` 为 Spring 真歧义（500）。可用别名 `/export/{entity}`。
