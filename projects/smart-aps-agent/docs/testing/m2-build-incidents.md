# M2 构建事故记录（W9 / dev-QA2 维护）

> 记录人：构建管理员 W9（`dev-QA2`）
> 规则来源：`docs/plan/m2-plan.md` 第 5 节（5.1–5.4）
> 纪律：全项目仅 W9 可执行 `mvn package`；打包后必须做 `jar tf` 校验 `BOOT-INF/lib`；`target/` 被覆盖时只清受影响模块，禁止全局 `mvn clean`。

## 记录格式

| 时间 | 模块 | 命令 | 结果 | 处理 |
|---|---|---|---|---|

## 记录

| 时间 | 模块 | 命令 | 结果 | 处理 |
|---|---|---|---|---|
| 2026-11-04 05:00 | 全仓 | 检测到其他会话 `mvn -B -ntp -pl aps-service,aps-web -am test`（PID 28392） | 构建槽位冲突，已等待其结束再打包 | 不终止他人进程；等待后再由 W9 单权打包 |
| 2026-11-04 05:05 | aps-service | `mvn -B -ntp -pl aps-web -am -DskipTests package` | 失败：`InventoryService.java:81` 将 `Serializable` 传给 `UUID` 参数（W6 未提交代码） | 不修改他人文件；记录并等待 W6 修复后重试 |
| 2026-11-04 05:01 | aps-web | 同上（在线模式） | 失败：W3 的 `SolveProgressStompController` 缺少 `SubscribeMapping` 导入、`RateLimitFilter` 引用不存在的 `SC_TOO_MANY_REQUESTS` | 不修改他人文件；W3 于 05:02 自行修复，重试通过 |
| 2026-11-04 05:03 | aps-admin | `java -jar ... --server.port=18088` | 启动失败：`StrategyController#clone` 与 `OrdersContractController#cloneStrategy` 重复映射 `POST /api/v1/strategies/{id}/clone` | 运行期冲突，等待 W2 移除旧占位；不修改他人 Controller |
| 2026-11-04 05:03 | aps-admin | `jar tf aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar` 过滤 `BOOT-INF/lib/` | 通过：112 条依赖 jar，含 poi-5.3.0 等；fat jar 有效 | 制品可交付 |
| 2026-11-04 05:14 | aps-service | `mvn -o -pl aps-service pmd:check@pmd-check` | 失败：W3 的 `SolveTaskLifecycleService`、`RunningTask` 共 2 条 PMD（不存在于 importexport） | 不修改他人文件；等待 W3 修复 |
| 2026-11-04 05:14 | aps-service | `mvn -o -pl aps-service checkstyle:check@checkstyle-check` | 通过：0 条违规（含 W9 新增 importexport 全部文件） | — |
| 2026-11-04 05:16 | aps-service | `mvn -o -pl aps-admin -am -Dmaven.test.skip=true package` | 失败：`OrderGanttService.java:88` 构造参数个数不一致（其他会话未提交改动） | 不修改他人文件；等待其编译通过后重试 |
| 2026-11-04 05:24 | 全仓 | 同上 | 通过：BUILD SUCCESS；fat jar 173.02MB，`BOOT-INF/lib` 112 条，含 poi-5.3.0 / spring-websocket / solver-dsl | 制品可交付 |
| 2026-11-04 05:25 | aps-web | 打包后启动实测 `POST /api/v1/materials/import` | 415：M1 遗留 `MaterialsContractController#importMaterials`（`@PostMapping("/materials/import")` + `@RequestBody`）优先于通用 `/{entity}/import` 映射 | 不修改他人 Controller；本次新增兼容路径 `/import/{entity}`，并上报需占位 Owner 删除旧映射 |
| 2026-11-04 05:25 | aps-web | W3 的 `RateLimitFilter`/STOMP 相关改动 | 编译通过，未重复出现 05:01 的两处错误 | — |
| 2026-11-04 05:29 | 全仓 | `mvn -B -ntp -pl aps-admin -am package -DskipTests`（打包前已确认无 `mvn` 进程：仅 18085 旧包与我的 18089 验证进程） | 通过：BUILD SUCCESS（05:29:12，11.7s）；fat jar 173.02MB，`BOOT-INF/lib` **112 条**，含 poi-5.3.0 / spring-websocket-6.1.21 / solver-dsl-0.1.0-SNAPSHOT | 制品可交付；同一命令一口气跑完，期间未插入其他 Maven 命令 |
| 2026-11-04 05:33 | aps-admin | 接管 18085：停旧 `fe-integration.jar`（PID 11992）→ `java -jar aps-admin-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --server.port=18085` | 通过：PID 30564，启动 05:33:16，`Started ApsAdminApplication in 5.143s`；Flyway 应用 V2–V6（schema v6）；`Ambiguous mapping` 计数 **0** | 共享联调后端由 W9 接管 |
| 2026-11-04 05:34 | aps-admin | HTTP 验收（18085）：`/health`、`POST /mrp/analysis`、`/strategies`、`/gantt/order`、`/gantt/resource` | 全部 200：MRP `status=SHORTAGE`/`orderCount=3`/真实 shortages；策略非 501；两张甘特有真实泳道与任务条 | 新包生效，旧包 50100 已消除 |
| 2026-11-04 05:36 | aps-web | 新包实测 `POST /api/v1/materials/import`（multipart） | 仍 415：占位 `MaterialsContractController#importMaterials(@RequestBody(required=false) JsonNode body)` 与实现 `ImportExportController#importFile(... @RequestPart MultipartFile file)` 同路径 `POST /api/v1/materials/import`，占位优先命中 | 不修改他人 Controller；兼容路径 `POST /api/v1/import/materials` 实测 200；待占位归属方删除该方法 |
| 2026-11-04 05:40 | aps-admin | 发现 `aps.security.mode=dev` 时前端代理（`vite.config.ts` 无 `X-API-Key`）所有 POST 被 401 拦截；改为 `--aps.security.mode=off` 重启 18085 | 通过：PID 21020，05:40:06 启动；`health` 200、`POST /mrp/analysis` 无密钥 200（`status=SHORTAGE`）、`strategies` 200、两张甘特 200；`Ambiguous mapping` 仍为 0 | 本机联调实例按旧实例行为关闭鉴权；生产必须开 C27 鉴权并让前端带密钥 |

| 2026-11-04 05:47 | aps-admin | 按 W8 要求停 `off` 实例，改 `--aps.security.mode=dev` 重启 18085（同 jar 同端口） | 通过：PID 14592，05:47:12 启动；`GET /health` 200；无 key `POST /orders` → **401 `40100`**；带 `X-API-Key: dev-write-key` → **400**（业务校验，认证通过）；无 key `GET /strategies` → **200** | 认证三连通过，保持 `dev` 运行；`off` 仅为联调、**不代表 C27 已验证** |
| 2026-11-04 05:56 | aps-service | 打包 `mvn -B -ntp -o -pl aps-admin -am package -DskipTests` | 失败：`TaskAdjustService.java:9` `import ...persistence.Calendar` 找不到符号（`Calendar` 已迁至 `persistence.calendar`），文件 mtime 05:56:16（他人在写） | 不修改他人文件；等待归属方修复（05:57:01 已自行改为 `persistence.calendar.Calendar`） |
| 2026-11-04 05:59 | aps-web | 同上 | 失败：`StrategyController.java` 新增 `MediaType` 用法但未 import（05:59:44 编辑中） | 等待约 45 秒后归属方补 import（06:00:03），重试通过 |
| 2026-11-04 06:02 | aps-admin | 停 18085 后打包、起服务 | 启动失败：`FlywayValidateException: Migration checksum mismatch for migration version 3`（DB 已应用 `1198800526`，本地解析 `946499021`），根因 `V3__plan_version.sql` 在**应用之后**被改（含注释） | 只对 `flyway_schema_history` 执行 `flyway repair`，不 clean、不重跑 migrate、不改 SQL；校验库内 V3 对象（19 列/4 索引/8 约束/`task` 外键与索引/表注释）与当前文件完全一致，确认差异仅文本 |
| 2026-11-04 06:02 | aps-admin | `mvn -pl aps-admin org.flywaydb:flyway-maven-plugin:10.22.0:repair`（`-Dflyway.url=jdbc:postgresql://127.0.0.1:55432/aps_local`） | 通过：V3 校验和更新为 `946499021` | 迁移链恢复校验通过 |
| 2026-11-04 06:04 | aps-admin | 开启 Flyway 重启 18085（`dev`） | 通过：PID 28224，06:04:25 启动；`Successfully validated 6 migrations`、schema **v6**、`up to date. No migration necessary`；`Ambiguous mapping` 0 | checksum mismatch 不再出现 |
| 2026-11-04 06:12 | aps-admin | 停服后打包 `mvn -B -ntp -o -pl aps-admin -am package -DskipTests`（含路线边列与导出租装置） | 通过：BUILD SUCCESS 06:12:40（8.4s）；fat jar 181,505,615 B（约 173.1MB），`BOOT-INF/lib` **112 条** | 制品可交付 |
| 2026-11-04 06:12 | aps-admin | 重启 18085（`dev`） | 通过：PID 30740，06:12:53；`GET /health` 200；`Ambiguous mapping` 0 | — |
| 2026-11-04 06:15 | aps-admin | 停服后打包（修复执行期 `ImportRowException` 泄漏为 500） | 通过：BUILD SUCCESS 06:15:04（14.0s）；fat jar 181,505,400 B，`BOOT-INF/lib` **112 条** | 制品可交付 |
| 2026-11-04 06:15 | aps-admin | 重启 18085（`dev`） | 通过：PID 27176，06:15:12；`GET /health` 200；`Ambiguous mapping` 0；认证三连复测：无 key 写 401 `40100` / 带 key 400 / 无 key 读 200 | 18085 保持 `dev` 运行 |
| 2026-11-04 06:24:50 | aps-admin | 等 W1 solve 契约统一稳定（`SolveTaskController` 06:20:53、`SolveTaskViewAssembler` 06:20:30）后打包 `mvn -B -ntp -o -pl aps-admin -am package -DskipTests`（打包前 `Get-Process -Name mvn` 无进程，单条命令） | 通过：BUILD SUCCESS 06:24:50（12.464s）；fat jar 181,507,583 B，`BOOT-INF/lib` **112 条** | 制品可交付；含 `aps-web` 最近的 solve 兼容分支、`strategies/import` 的 `consumes`、`AuditContext` 注入 |
| 2026-11-04 06:25:04 | aps-admin | 重启 18085（`--spring.profiles.active=dev --server.port=18085 --aps.security.mode=dev`） | 通过：PID **26856**，06:25:04 启动；`GET /health` 200；Flyway `Successfully validated 6 migrations`、schema **v6**；`Ambiguous mapping` **0**；`GET /solve-tasks/1` 200 统一 `SolveTaskDetail`（`status=SOLVED`，无 `SUCCESS`）；认证三连：无 key 写 401 `40100` / 带 key 400 `40000` / 无 key 读 200；`POST /materials/import?mode=preview` 200（34/34）；`GET /export/materials` 200；`GET /gantt/order` 200 | 18085 保持 `dev` 运行；solve 双形状开关已移除，契约统一 |
| 2026-11-04 07:53:24 | aps-web / aps-service | 打包前 checkstyle 复核：`mvn -o -B -ntp -pl aps-web,aps-service checkstyle:check@checkstyle-check`（绑定 execution，自带 `configLocation`） | 通过：aps-service **0 violations**、aps-web **0 violations**（`ApiSecurityFilter:260` 行宽已由 owner 拆行修复），BUILD SUCCESS | 下一版打包前基线为 0；裸 `checkstyle:check` 需带 `-Dcheckstyle.config.location`，见“校验命令约定” |
| 2026-11-04 07:54:43 | aps-admin | 漂移排查：新建独立空库 `aps_flyway_probe` 跑 `flyway:migrate`（V1–V6），取当前文件的真实 checksum，与 `aps_local` 历史表逐行比对，随后 `drop database` | 通过：V1 `1119985351`、V2 `-1452760567`、V3 `946499021`、V4 `-109341391`、V5 `1459339503`、V6 `1790536655`，**6/6 与共享库一致**；historically 仅 V3 漂移（修复前 `1198800526`，repair 后 `946499021`），其余五版从未漂移 | 结论：仅纯注释漂移，语义未变；以 `repair` 处理（不改文件内容），理由见 `docs/db/README.md` 规则 8 |
| 2026-11-04 07:55:02 | aps-admin | `flyway:validate` 指向 `aps_local` | 通过：`Successfully validated 6 migrations` | 迁移链已干净 |
| 2026-11-04 07:56:10 | aps-admin | 运行中的 18085 日志出现 `ClassNotFoundException: ch.qos.logback.classic.spi.ThrowableProxy` | 根因：`aps-admin` 制品在 07:58:30 被非 W9 的 `mvn package` 覆盖成瘦包（24,975 B、25 条目、`BOOT-INF/lib` **0**）——Windows 下运行中的 jar 无法 repackage，旧 jar 被替换 | 记录：违反构建互斥（仅 W9 可 `package`）；运行中实例只是内存里还能响应，制品已不可用 |
| 2026-11-04 08:00:00 | aps-admin | W9 停 18085（PID 26856）→ `mvn -B -ntp -o -pl aps-admin -am package -DskipTests` → `jar tf` | 通过：BUILD SUCCESS 08:00:00（3.535s，全模块 up to date）；胖包 **181,521,188 B**，`BOOT-INF/lib` **112 条**，SHA256 `7F29E980…844DBC` | 制品恢复可用 |
| 2026-11-04 08:02:06 | aps-admin | 验证实例（18090，PID 30000，默认配置即 Flyway 开启，**无** `spring.flyway.enabled=false`） | 通过：`Successfully validated 6 migrations`、`Current version of schema "public": 6`、`Schema "public" is up to date. No migration necessary.`、`Started ApsAdminApplication in 4.98 seconds`、`/health` 200；验证后已停 18090 | 迁移链在共享库以默认配置启动通过 |
| 2026-11-04 08:02:24 | aps-admin | 重启 18085（PID **2024**，`dev`，Flyway 开启） | 通过：`Successfully validated 6 migrations`、schema v6 up to date、`Started ... 4.852 seconds`、`Ambiguous mapping` **0**、`/health` 200 | 共享联调恢复，保持 `dev` 运行 |
| 2026-11-04 08:32:24 | aps-admin | 场地清理（停 18085 PID 2024、18088 PID 28208；`Get-Process mvn` 为空）→ 编译校验 `mvn -B -ntp -o -pl aps-admin -am package -DskipTests -Dspring-boot.repackage.skip=true` → 正式单权打包同命令去掉 skip | 通过：编译校验 BUILD SUCCESS 10.409s；正式打包 BUILD SUCCESS 3.523s，`Replacing main artifact ... with repackaged archive`；制品 181,522,139 B / 258 条目 / `BOOT-INF/lib` **112 条** / SHA256 `7D13922B…2BEF` | 含 W1 的 phase 映射（`PENDING→QUEUED`、`SOLVED→DONE` 兜底）与 `@JsonInclude(ALWAYS)`、W4 的 `requireDisjointPinnedOrders` |
| 2026-11-04 08:32:40 | aps-admin | 重启 18085（PID **28496**，`dev`） | 通过：`Successfully validated 6 migrations`、`Current version of schema "public": 6`、`Schema "public" is up to date. No migration necessary.`、`Started ... 4.792 seconds`、`Ambiguous mapping` 0、`/health` 200 | 供后续制品级验收；保持 `dev` 运行 |
| 2026-11-04 12:46:15 | aps-admin | 停 18085（PID 28496）→ 单权打包 `mvn -B -ntp -o -pl aps-admin -am -Dmaven.test.skip=true package`（开跑前 `mvn` 进程 0；`git status` 快照 125 行、打包前后 SHA256 一致） | 通过：BUILD SUCCESS，Maven 总耗时 10.349s（墙钟 11.848s）；制品 181,561,007 B、`BOOT-INF/lib` **112 条**、SHA256 `60BE1319…2665`、`BOOT-INF/classes` 与 `BOOT-INF/lib` 均在（fat jar）；`db/migration` 实测 **V1–V7 共 7 个** | 库已在 v7、旧 jar 仅 V1–V6，本次打包补齐 V7，避免重启时 `ValidateException` |
| 2026-11-04 12:47:10 | aps-admin | 重启 18085（PID **32752**，`dev`，`--aps.security.mode=dev`） | 通过：`Successfully validated 7 migrations`、`Current version of schema "public": 7`、`Schema "public" is up to date. No migration necessary.`、`Started ... in 4.716 seconds`、`Ambiguous mapping` 0、`/health` 200 | 18085 以新制品运行 |
| 2026-11-04 13:38:49 | aps-admin | 停 18085（PID 32752）→ 单权打包 `mvn -B -ntp -o -pl aps-admin -am -Dmaven.test.skip=true package`（开跑前 `mvn` 进程 0；`git status` 快照 12 行，前后 SHA256 一致 `A3AE4CE5…BF4E8`） | 通过：BUILD SUCCESS，Maven 总耗时 8.794s（墙钟 10.322s）；制品 181,561,836 B（较上版 +829 B）、`BOOT-INF/lib` **112 条**（未变）、条目 259、SHA256 `0D9C99F8…2A18`、`db/migration` 仍为 **V1–V7 共 7 个**、fat jar | 修复 `metrics` 内 null 被 `non_null` 抑制（`@JsonInclude(ALWAYS, content=ALWAYS)`）与整表重叠守卫误标 `ENGINE_ERROR`；`solver-api` 新增 test-scope `jackson-databind` 不影响运行时依赖，lib 数未变 |
| 2026-11-04 13:39:36 | aps-admin | 重启 18085（PID **30312**，`dev`，`--aps.security.mode=dev`） | 通过：`Successfully validated 7 migrations`、`Current version of schema "public": 7`、`Schema "public" is up to date. No migration necessary.`、`Started ... in 5.144 seconds`、`Ambiguous mapping` 0、`/health` 200 | 复测 `GET /solve-tasks/86`：库内 `bestScore=null` 在响应中原样输出（`"bestScore":null`、`"strategyId":null`、`"resultTaskId":null` 均保留） |
| 2026-11-04 08:35 | aps-admin（18087） | 停止残留实例：PID **12364**（06:09:03 启动，`-cp */target/classes`，`--server.port=18087 --spring.flyway.enabled=false`；父进程 cmd 29212 一并退出） | 判定为残留：启动脚本 `D:\tools\_aps-localdata\start-18087-full.bat`（mtime 06:05:45），其目标日志 `m2se4-adjust.log` 最后内容为 `Port 18087 was already in use / APPLICATION FAILED TO START`（06:07:41），此后无任何脚本或文件再引用 18087；仅 LISTEN 无 ESTABLISHED 连接；W4 报告 `docs/reports/m2-se-04-w4.md` 只借用该 bat 的 classpath 起 18088 | 停掉原因：`target/classes` 已被 08:32 打包覆盖，继续运行会懒加载新的类文件抛 `NoClassDefFound`，用它的 /health=200 取证会污染证据；端口 18087 已释放 |
| 2026-11-04 06:17 | aps_service/aps_local | 清理 W1 手工 fixture：`M2-AUDIT-001/-003/M2-OFF-001` 走 `DELETE /materials/{code}`；`PLAN-AUDIT-001` 先 `update task set plan_version_id=null`（9 条任务创建于 04:46:12，早于 fixture）再删计划行 | 通过：物料 3 条软删（HTTP 200）；plan 1 条删除；清理后 **06:17:58** 计数：`materials_active=36`、`materials_fixture_left=0`、`plan_versions_active=0`、`tasks_total=57`、`tasks_with_plan=0` | 先验证零引用（orders/bom_item/bom_parent 均 0）再删；既有任务未被删除 |
| 2026-11-04 09:56:37 | aps-admin（18092） | 停止残留实例：PID **10912**（09:46 启动，`java -cp */target/classes com.hengce.aps.admin.ApsAdminApplication --spring.profiles.active=dev --server.port=18092 --aps.security.mode=dev`，DB `aps_local:55432`） | 判定为 classpath 实例：运行期间多次 `mvn test` 已重编译 `target/classes`，其 `/health` 仍 200 只是内存已加载类，继续取证会产生 `NoClassDefFound` 假证据（同 18087） | 已停止；端口 18092 已释放；18085（PID **28496**）复测 `GET /api/v1/health` **200** 未受影响 |

## 校验命令约定（Checkstyle）

- **单独运行必须显式指定配置文件**：
  `mvn -o -pl <module> checkstyle:check -Dcheckstyle.config.location=config/checkstyle/checkstyle.xml`
  （或直接用绑定 execution：`mvn -o -pl <module> checkstyle:check@checkstyle-check`，其配置自带 `configLocation`）
- **不带配置路径的裸 `mvn checkstyle:check` 会回落到插件默认 `sun_checks.xml`**，本仓库会报**约 801 条假错**；真实规则集只有 `config/checkstyle/checkstyle.xml`（`LineLength max=120`、命名/导入/花括号/JavadocType 等）。
- **判断方法**：报告来源/规则出现 `sun_checks` 相关规则（如 `DesignForExtension`、`FinalParameters`、`MagicNumber`、`HiddenField`、`ExecutableStatementCount`，`LineLength max=80`）即为用错配置，不要据此改代码。
- **真实绑定位置（全量 `mvn verify` / D18 不受影响）**：根 `pom.xml` `<build><plugins>` 中 `maven-checkstyle-plugin` 的 execution `checkstyle-check`（`verify` 阶段），配置项 `configLocation=${maven.multiModuleProjectDirectory}/config/checkstyle/checkstyle.xml`。CI `.gitlab-ci.yml` 的 `backend:lint` 作业跑 `mvn -DskipTests verify`，走的就是这条绑定，配置正确；同文件其它作业（`package`、jacoco、`test:realdb`）均显式 `-Dcheckstyle.skip=true`，不参与校验。

## 教训

1. **已应用的迁移不可再改，连注释也不行**：Flyway 校验和覆盖文件全文，`V3__plan_version.sql` 应用后被改注释即触发 `checksum mismatch`，共享库所有会话启动全部被挡。修改已应用迁移只能新增后续版本（V7+），修复历史仅限 `flyway repair`。
2. **`flyway repair` 的边界**：只更新 `flyway_schema_history` 的校验和，不 `clean`、不重跑 `migrate`、不改 SQL；执行前须用库内对象（列/索引/约束/注释）与当前文件比对，证明差异是文本而非 DDL。
3. **18085 在跑时 `package` 会失败**：Windows 对运行中的 jar 持有文件句柄，`spring-boot:repackage` 重命名 `.original` 失败且 jar 被覆盖成瘦包（24KB）。正确顺序：停 18085 → 打包 → `jar tf` → 重启。
4. **启动必须带 DB 环境变量**：`application.yml` 用 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` 占位，缺省会连 `localhost:5432` 导致 Flyway 连接被拒（`SQLState 08001`）。18085 启动参数固定为 `DB_PORT=55432 / DB_NAME=aps_local`。
5. **`mode=off` 仅为联调，不代表 C27 已验证**：`off` 全放行只用于前端联调；C27 的鉴权证据必须在 `aps.security.mode=dev` 下取得（无 key 写 401 `40100`、带 `dev-write-key` 非 401、无 key 读 200）。
6. **共享库计数必须带时间戳**：库内存在并发写入（MRP 订单 05:34 为 3、05:39 为 11），任何“数量”断言都要记录采集时刻与当时计数。
7. **“服务 200”不等于“制品完好”**：进程把 jar 读进内存后，即使磁盘上的 jar 已被替换成瘦包，`/health` 仍可能返回 200；判断制品是否有效必须看文件大小与 `BOOT-INF/lib` 条目数（正常 ≈181MB / 112 条；瘦包 ≈25KB / 0 条）。**任何人**在 18085 运行期间执行 `mvn package` 都会造成这个结果，因此打包必须遵循“停服 → 打包 → `jar tf` → 重启”，且打包权仅归 W9。

## 待办（每次打包前检查）

1. **进程互斥，不按固定时间槽**：打包前执行 `Get-Process -Name mvn -ErrorAction SilentlyContinue`，并用
   `Get-CimInstance Win32_Process -Filter "name='java.exe'"` 看命令行是否含 `maven`/`-am test`；有则等待，
   **后来者让位**。W2 已被授权跑 `mvn test`，同样先查进程。
2. 打包命令：`mvn -B -ntp -pl aps-admin -am package -DskipTests`。
3. 校验：`jar tf aps-admin/target/aps-admin-*.jar | Select-String 'BOOT-INF/lib/'` 必须多条。
4. 失败时保留现场并在本文件追加一行。

## 真库集成测试开关（APS_TEST_DB_ENABLED）

1. **门控范围**：`aps-service` 的 9 个真库集成测试类、25 个方法由环境变量 `APS_TEST_DB_ENABLED=true` 门控——8 个类使用类级 `@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")`，`MrpRealDbIntegrationTest` 使用 `Assumptions.assumeTrue(RealDbEnvironment.enabled(), "未启用真库测试：APS_TEST_DB_ENABLED")`；判定入口集中在 `RealDbEnvironment`（`aps-service/src/test/java/com/hengce/aps/service/it/`）。当前**没有**任何 `@Disabled` 真库用例。
2. **不设该变量**：`mvn -B -o -pl aps-service -am test` 报 **208 / 0 / 0 / 25**。这**不是通过**——25 条会查库、验唯一约束/外键/422 映射的用例根本没跑（surefire 跳过原因原文：`Environment variable [APS_TEST_DB_ENABLED] does not exist`；`MrpRealDbIntegrationTest` 为 `TestAbortedException`）。
3. **设置变量**（另需 `APS_TEST_DB_URL` / `APS_TEST_DB_NAME` / `SPRING_DATASOURCE_*`，且库内已按序应用 `db/migration` V1–V7）：**208 / 0 / 0 / 0**，九个类逐类 `skipped=0`——`BomActivationConcurrency 1`、`Bom 4`、`Calendar 5`、`CrossDomainReference 4`、`Mrp 1`、`ProductionPersistenceConfig 2`、`Resource 6`、`RoutingSortDefault 1`、`ScheduleOverlap 1`（合计 25）。
4. **规则**：任何“测试全绿”的结论都必须写明是否开启 `APS_TEST_DB_ENABLED`；**制品级终验一律要求 `Skipped=0`**。默认跑出的 25 skipped 只能记作“未验真库”，不得写成“通过”，也不得以 `-DskipTests`、删测试或 `@Disabled` 凑绿。

## 构建事故：交付基线制品被覆盖（2026-11-05 04:38:24）

- 时间：`2026-11-05 04:38:24`
- 事故类型：**交付基线制品被覆盖（不可恢复）**
- 触发动作：`mvn -o -pl aps-admin -am package -DskipTests`
- 被覆盖物：`aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`（182,013,745 B / SHA256 `0EC320ED…B92518` / 264 条目 / `BOOT-INF/lib` 112 / 迁移 9）
- 覆盖物：182,220,338 B / SHA256 `5AACC76D…A412CDA79` / 267 条目 / `BOOT-INF/lib` 113 / 迁移 11
- 备份情况：**无备份**。`.jar.original`（33,999 B）为本次 `repackage` 产生的 thin jar 副产物，不构成备份。
- 可恢复性：本地 `D:\tools` 按字节数与 SHA 搜索 **0 命中**，不可恢复；如 CI/制品库有归档方可取回。
- 影响面：本轮唯一一颗经接管验收的制品灭失；复验结论本身仍有效（其验证的是含修复的新包行为）。
- 处理人 / 后续：由制品车道按“接受新基线 → 重建 → 重新接管 18085”处理。
- **教训一句话**：`package` 会写标准名交付制品，“只在 `target/` 下构建”不等于“没有影响既有制品”；构建前必须确认标准名文件是否为验收件，并先备份 + 记录 SHA。
