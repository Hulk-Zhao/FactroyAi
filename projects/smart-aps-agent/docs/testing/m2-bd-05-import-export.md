# M2-BD-05 Excel 导入导出 —— 交付与实测证据

> 执行人：W9 / `dev-QA2`（兼构建管理员）
> 契约：C26（`docs/plan/m2-plan.md` 第 6 节）；端点 `GET /{entity}/import/template`、`POST /{entity}/import`
> 实测环境：2026-11-04，共享联调后端 `http://127.0.0.1:18085`（dev profile，PG 15.12 @ 55432/`aps_local`）

## 1. 交付清单

| 层 | 路径 | 说明 |
|---|---|---|
| 服务 | `aps-service/src/main/java/com/hengce/aps/service/importexport/` | `ImportEntity`（9 类实体 + 列定义）、`ExcelTemplateWriter`、`ExcelRowReader`、`ImportService`、`ImportIdempotencyStore`、`ImportLimits`、`ImportError`、`ImportResult`、`ImportValues` |
| 导入器 | `.../importexport/handler/` | `MaterialImportHandler`、`SupplierImportHandler`、`StrategyImportHandler`、`OrderImportHandler`、`ResourceImportHandler`、`OperationTemplateImportHandler`、`BomImportHandler`、`CalendarImportHandler`、`RoutingImportHandler`（9 类全部可执行，无 `PendingImportHandler`） |
| 导出 | `.../importexport/ExcelDataWriter.java`、`EntityDataExporter.java` | 9 类数据导出，列顺序与模板页一致；全部走各自查询服务（`PageQuery` 分页，`PAGE_SIZE=100`，上限 `MAX_ROWS=5000`） |
| Web | `aps-web/src/main/java/com/hengce/aps/web/controller/importexport/ImportExportController.java` | 模板下载、导入（preview/execute）、导出；兼容路径 `/import/{entity}` 与 `/export/{entity}` |
| 依赖 | `aps-service/pom.xml` | 仅新增 `org.apache.poi:poi-ooxml`（无版本号，由根 pom `poi.version=5.3.0` 管理）；W2 的 `solver-dsl` 依赖原样保留 |
| CI | `.gitlab-ci.yml` 第 153 行起 `backend:test:realdb` | `postgres:15` service + `APS_TEST_DB_ENABLED=true`，让 M1 遗留的 23 条真实库 IT 真正执行 |

### 9 类实体（slug）

`materials / boms / resources / calendars / operationTemplates / routings / orders / strategies / suppliers`

列数：materials 8、boms 9、resources 9、calendars 8、operationTemplates 7、routings 8、orders 7、strategies 5、suppliers 6。

> 2026-11-04 06:15 版新增列：`calendars` 增加可选 `resourceCode`（`RESOURCE`/`SHIFT` 类型必填）；`routings` 由 4 列扩到 8 列（`seqNo`、`fromSeqNo`、`operationCode`、`primaryResourceCode`），`fromSeqNo` 生成 `前驱 → 当前` 的 FS 依赖边。

## 2. 模板下载实测（18085 打包 jar）

| 实体 | HTTP | 字节 | 列数 |
|---|---|---|---|
| materials | 200 | 6633 | 8 |
| boms | 200 | 6773 | 9 |
| resources | 200 | 6757 | 9 |
| calendars | 200 | 6809 | 8 |
| operationTemplates | 200 | 6499 | 7 |
| routings | 200 | 6789 | 8 |
| orders | 200 | 6618 | 7 |
| strategies | 200 | 6368 | 5 |
| suppliers | 200 | 6485 | 6 |

（实测时刻 2026-11-04 06:13:34–06:13:35，PID 30740 实例；POI 重开均为 2 sheets：`数据` + `字段说明`。）

`Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，`Content-Disposition: attachment; filename="{slug}-import-template.xlsx"`。

## 3. 导入端到端实测

### 3.1 错误行定位（materials）

`POST /api/v1/import/materials?mode=preview`（4 行，2 行非法）：

```json
{"entity":"materials","mode":"PREVIEW","totalRows":4,"validRows":2,"invalidRows":2,"committed":false,
 "errors":[{"row":3,"field":"code","code":42200,"message":"物料编码不能为空"},
           {"row":4,"field":"type","code":42200,"message":"只允许 FINISHED/SEMI/RAW/AUX/PACK"}],
 "preview":[{"code":"MAT-W9-001","name":"W9物料A","type":"FINISHED",...},
            {"code":"MAT-W9-002","name":"W9物料B","type":"RAW",...}]}
```

行号为 Excel 物理行号（表头为第 1 行），`field` 为列键。

### 3.2 幂等令牌（suppliers）

| 步骤 | 请求 | 结果 |
|---|---|---|
| preview（3 行，2 非法） | `POST /api/v1/suppliers/import?mode=preview` | `validRows=1 invalidRows=2`；`row=3 field=name`、`row=4 field=blacklisted` |
| execute 令牌 T1 | `X-Import-Token: sup-w9-token-0001` | `inserted=2 updated=0 committed=true replayed=false` |
| 同令牌重放 | 同一 T1 | `replayed=true`，未产生重复数据 |
| 新令牌 T2 重导 | `sup-w9-token-0002` | `inserted=0 updated=2 committed=true` |

DB 校验：

```
 code        | name     | lead_time_days | moq     | quality_grade | blacklisted
 SUP-W9-001  | 衡策精工 | 7              | 1.0000  | A             | f
 SUP-W9-002  | 江南五金 | 5              | 2.0000  | B             | t
```

### 3.3 materials 执行与幂等

- `POST /api/v1/import/materials?mode=execute`（令牌 `mat-w9-token-0003`，2 行）→ `updated=2 inserted=0 committed=true`
- 同令牌重放 → `replayed=true`
- DB：`select count(*) from material where code like 'MAT-W9-%'` → **2**

### 3.4 校验失败不落库

非法文件走 `mode=execute` 时返回 `committed=false inserted=0`，全部行校验通过才开启写入事务。

### 3.5 boms 执行导入（多行合并为一条 BOM）

| 步骤 | 请求 | 结果 |
|---|---|---|
| preview（2 行，同父件同版本） | `POST /api/v1/boms/import?mode=preview` | `totalRows=2 validRows=2 invalidRows=0` |
| execute 令牌 `T-BOM-1-w9` | `mode=execute` | `inserted=1 committed=true`（2 行合并为 1 条 BOM + 2 条子件） |
| 同令牌重放 | 同上 | `replayed=true`，无重复数据 |
| 新令牌 `T-BOM-2-w9` | 同上 | `skipped=1`（实体级幂等：父件+版本已存在） |
| 非法子件 | preview | `errors=[{"row":2,"field":"componentMaterialCode","code":42200,"message":"物料不存在: NO-SUCH"}]` |

写库复用 `BomService.create`（层级/物料存在性/损耗率 0–100 等校验在服务层）；导入层只做列格式与分组。

**已知缺口（BD-03 归属）**：`BomService.expand` 有循环依赖检测（读路径），但 `create` 写路径**没有**。实测 `MAT-W9-001 V9 → MAT-W9-002` 与 `MAT-W9-002 V9 → MAT-W9-001` 反向导入可成功落库（`inserted=1`），随后用服务层删除因“互为子件引用”被阻断。按纪律未在导入层另写一套循环规则，测试数据已清理，缺口已上报。

### 3.6 calendars 执行导入（班次/例外日 JSON）

| 步骤 | 请求 | 结果 |
|---|---|---|
| preview（FACTORY + 班次 + 1 个 LEGAL 例外日） | `POST /api/v1/calendars/import?mode=preview` | `validRows=1` |
| execute 令牌 `T-CAL-1-w9` | `mode=execute` | `inserted=1 committed=true` |
| 同令牌重放 / 新令牌 `T-CAL-2-w9` | — | `replayed=true` / `skipped=1` |
| `shifts` 非 JSON | preview | `row=2 field=shifts code=42200` |
| `type=RESOURCE` 但 `resourceCode` 为空 | preview | `row=2 field=resourceCode code=42200` |

写库复用 `CalendarService.create`（时区/班次/例外日类型校验在服务层）。

### 3.7 routings 执行导入（节点 + FS 边）

| 步骤 | 请求 | 结果 |
|---|---|---|
| preview（2 行，`seqNo=10`、`seqNo=20 fromSeqNo=10`） | `POST /api/v1/routings/import?mode=preview` | `validRows=2`，预览含 `fromSeqNo` |
| execute 令牌 `T-RT-1-w9` | `mode=execute` | `inserted=1 committed=true`（1 条路线 + 2 节点 + 1 条边） |
| 同令牌重放 / 新令牌 `T-RT-2-w9` | — | `replayed=true` / `skipped=1` |
| 产品不存在 | preview | `row=2 field=productCode code=42200 "物料不存在: NO-SUCH"` |
| 同路线 `seqNo=10` 重复 | execute | **422 `42200`** "同一路线内节点序号重复: 10"（原先泄漏为 500，06:15 版已修） |
| `10→20` 与 `20→10` 成环 | execute | **422 `42200`** "工艺路线存在环: n10 -> n20 -> n10"（复用 `RoutingService` 图校验） |
| 只有节点无边（孤立节点） | execute | **422 `42200`** "存在孤立节点（无任何依赖边）: [n10, n20]"（复用服务校验，导入层不重写） |

### 3.8 数据导出（xlsx，可回填再导入）

端点：`GET /api/v1/export/{entity}`（兼容别名）/ `GET /api/v1/{entity}/export`（见第 4 节路由冲突）。

实测 2026-11-04 06:15:52（PID 27176，库 `aps_local` v6）：

| 实体 | HTTP | 字节 | 数据行数（当时） |
|---|---|---|---|
| materials | 200 | 4964 | 34 |
| boms | 200 | 4341 | 15 |
| resources | 200 | 4526 | 14 |
| calendars | 200 | 4285 | 10 |
| operationTemplates | 200 | 4027 | 6 |
| routings | 200 | 4173 | 12 |
| orders | 200 | 4322 | 13 |
| strategies | 200 | 3665 | 1 |
| suppliers | 200 | 3786 | 2 |

- 列顺序与模板页 **9/9 完全一致**（POI 逐列比对 `headers orderMatch=true`）。
- 导出走查询服务 `list(...)`（分页 100/页，上限 5000 行），未直连 Mapper；`suppliers` 无服务，走 `JdbcTemplate` 只读查询并在文档标注。
- `calendars.resourceCode`、`operationTemplates.primaryResourceCode` 已按 `resource_id` 反查编码回填（例：`FEINT-R-CALREF3`、`DEMO-RES-CNC-01`）。
- `routings` 按节点展开（每个 `seqNo` 一行，`fromSeqNo` 由边反推），空白草稿保留一行无节点行。
- **回填闭环**：`materials` 导出文件直接 `POST /materials/import?mode=preview` → `totalRows=34 validRows=34 errors=[]`；`routings` 导出文件 → `totalRows=12 validRows=12 errors=[]`。

## 4. 已知问题（路由被他人占位/歧义，W9 未改他人代码）

### 4.1 导入：`POST /api/v1/materials/import` → 415

`POST /api/v1/materials/import` 仍返回 **415**：

```
{"code":41500,"message":"不支持的 Content-Type","data":null,...}
```

- 占位方法：`MaterialsContractController#importMaterials(@RequestBody(required=false) JsonNode body)` → `POST /api/v1/materials/import`
- 实现方法：`ImportExportController#importFile(... @RequestPart("file") MultipartFile file)` → `POST /{entity}/import`（`consumes=multipart/form-data`）
- 同一路径下占位先命中，multipart 请求被判非法 Content-Type。启动期无 `Ambiguous mapping`（两者 consumes 条件不同，Spring 不做歧义判定）。
- 临时可用路径：`GET /api/v1/import/{entity}/template`、`POST /api/v1/import/{entity}`（已实测 200）。W1 已接管删除该占位并做全仓“静默抢路由”扫描。

### 4.2 导出：`GET /api/v1/{entity}/export` 被其它 Controller 的 `/{entity}/{id}` 抢占

实测（06:05，PID 28224）：

| 路径 | 实际命中 | 结果 |
|---|---|---|
| `GET /materials/export` | `MaterialsContractController` 的 `GET /materials/{code}` | 404 `物料不存在: export` |
| `GET /resources/export` | `GET /resources/{code}` | 404 `资源不存在: export` |
| `GET /calendars/export` | `GET /calendars/{id}` | 400（UUID 解析失败） |
| `GET /routings/export`、`GET /strategies/export` | `/{id}` | 400（UUID 解析失败） |
| `GET /orders/export` | 两条映射**真歧义** | 500 `Ambiguous handler methods mapped for '/api/v1/orders/export'`：`OrderController.detail(String)` vs `ImportExportController.export(String)` |

- `boms/export`、`operationTemplates/export`、`suppliers/export` 未被抢占，200。
- 通用可用路径：`GET /api/v1/export/{entity}`（9/9 实测 200）。**建议 W1 的抢路由扫描把这条列为待处理项**：要么他人 `GET /{entity}/{id}` 加约束（如 UUID 正则），要么前端统一改用 `/export/{entity}`。
- `/orders/export` 的歧义会直接 500，属实现级冲突，不是优先级问题。

## 5. 未完成 / 边界

| 项 | 状态 | 说明 |
|---|---|---|
| boms / calendars / routings 执行导入 | 已完成 | 走 `BomService`/`CalendarService`/`RoutingService.create` 真落库，复用服务层校验；证据见 3.5–3.7 |
| 数据导出 | 已完成 | 9 类全部 `GET /export/{entity}` 实测 200，列顺序与模板 9/9 一致；证据见 3.8 |
| BOM 写路径循环依赖校验 | **缺口（BD-03 归属）** | `BomService.create` 无环检测，仅 `expand` 读路径有；导入层按纪律未另写规则 |
| `/{entity}/export` 规范路径 | 被他人 `/{entity}/{id}` 抢占 | 用 `/export/{entity}` 别名；`/orders/export` 为真歧义 500，见 4.2 |
| 幂等存储 | 进程内 `ConcurrentHashMap` | 多实例部署需换 Redis/DB（C27 演进项） |
| 导入行数/大小上限 | 10MB、5000 行、200 条错误、预览 20 行 | `ImportLimits` 常量 |

## 6. 共享联调后端（18085，W9 接管）

- 启动命令（**必须以 `dev` 运行**）：
  `java -jar aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --server.port=18085 --aps.security.mode=dev`
- 必需环境变量：`DB_HOST=127.0.0.1 DB_PORT=55432 DB_NAME=aps_local DB_USER=aps DB_PASSWORD=aps_dev_pwd`（缺失会连 5432 失败，见事故台账 06:02）。
- 实例：**PID 2024（2026-11-04 08:02:24）**，制品 08:00:00 重打包（181,521,188 B，`BOOT-INF/lib` 112 条）；Flyway **默认开启**，启动日志 `Successfully validated 6 migrations` + `Current version of schema "public": 6` + `Schema "public" is up to date. No migration necessary.`，`Ambiguous mapping` 0。
  该制品含 `aps-web` 的 solve 契约统一（`GET /solve-tasks/1` 返回 `SolveTaskDetail`，`status=SOLVED`，无 `SUCCESS`）、`/strategies/import` 的 `consumes`、`AuditContext` 注入；`POST /materials/import?mode=preview` 已 200（占位已由 W1 删除）。
  > 07:58:30 该 jar 曾被非 W9 的 `package` 覆盖成瘦包（24,975 B / 0 lib），08:00:00 已由 W9 停服重打包修复；详见事故台账。
- 数据库：`aps_local` **schema v6**；`Flyway` 已在共享库验证通过（`Successfully validated 6 migrations`；迁移链 V1–V6 已应用，V3 校验和经 `flyway repair` 修正为 `946499021`）。
- 认证实测（`aps.security.mode=dev`）：无 key `POST /orders` → **401 `40100`**；带 `X-API-Key: dev-write-key` → **400**（业务校验，认证通过）；无 key `GET /strategies` → **200**。
- `mode=off` 仅为联调临时方案，**不代表 C27 已验证**；C27 证据一律以 `dev` 模式为准。
- **共享库并发写入**：MRP 订单数在 05:34 为 3、05:39 为 11；所有“数量”结论都带采集时刻（本文档 3.8 为 06:15:52 当时计数）。

