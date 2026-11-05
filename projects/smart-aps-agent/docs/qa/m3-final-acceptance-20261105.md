# M3 终版制品构建 + 18085 接管 + 修复项冒烟

**回归 Skipped=0 / 制品 181,902,151 B / 新 PID 30716 / 冒烟 8/8 通过**

- 执行日期：2026-11-05（Asia/Shanghai）
- 项目：`D:\tools\smart-aps-agent`
- 工具链：JDK 21.0.13 + Maven 3.9.16（`D:\tools\_aps-toolchain`）

---

## 1. 全量回归

命令：`APS_TEST_DB_ENABLED=true mvn -B -ntp test`（真库为既有的 `aps_test_shared`，未触碰 `aps_local`）

| 模块 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| aps-common | 19 | 0 | 0 | 0 |
| aps-infrastructure | 34 | 0 | 0 | 0 |
| solver-api | 8 | 0 | 0 | 0 |
| solver-dsl | 19 | 0 | 0 | 0 |
| solver-timefold | 96 | 0 | 0 | 0 |
| aps-service | 328 | 0 | 0 | 0 |
| aps-web | 57 | 0 | 0 | 0 |
| **合计** | **561** | **0** | **0** | **0** |

BUILD SUCCESS，Total time 03:38 min。较上一轮的 558 增加 3 条，来自本轮修复同步新增的用例（`PlanPublishScopeRealDbIntegrationTest`、`RoutingImportTemplateRealDbIntegrationTest` 等）。

> 首轮回归出现 1 个 Error（详见第 7 节），复跑复现后判定为用例自身时间戳缺陷，修正用例后上述结果成立；业务代码未因此改动。

---

## 2. 终版制品核验

标准名路径：`aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`（Spring Boot repackage 后的可运行 fat jar）

| 指标 | 上一版基线（`*-repackaged.jar`） | 本次终版 | 差异 | 差异来源 |
|---|---:|---:|---:|---|
| 字节数 | 181,892,052 | 181,902,151 | +10,099 | 5 项修复涉及的 class 重编译（发布范围过滤、日历时区、MRP 缓存、routing 模板列、齐套校验） |
| SHA256 | `1D3C583F258515A90DF5D513EDE4955607583CAE4BCB13CF6B7EC6C042AE8D96` | `7469A78BCAB8BDAF9FE20CA536B21907F9A2BF915C59F3B11131D8E4E2E033D7` | 变化 | 同上（代码变更 + 打包时间戳） |
| jar 条目数 | 264 | 264 | 0 | 依赖集合未变 |
| `BOOT-INF/lib` jar 数 | 112 | 112 | 0 | 依赖集合未变 |
| `db/migration` 迁移文件 | 9 | 9 | 0 | V1–V7、V11、V12；V8/V9/V10 不存在 |

MANIFEST 已确认 `Main-Class: org.springframework.boot.loader.launch.JarLauncher`、`Start-Class: com.hengce.aps.admin.ApsAdminApplication`。

### 备份

| 文件 | 字节 | SHA256 |
|---|---:|---|
| `aps-admin-0.1.0-SNAPSHOT.jar.prev.bak`（构建前的旧标准名瘦 jar） | 30,035 | `88B7AE480285307045C6E0BD476091A31EBFBBE1E5BDEFFAF11069E3D1D92939` |
| `aps-admin-0.1.0-SNAPSHOT.jar.thinjar.bak`（上一轮遗留，未删除） | 30,035 | `04EB2CC8315BA16AD5FD411E0E1E752A92C50E4A98B4F7725D85F9E7E2D7CFB8` |
| `aps-admin-0.1.0-SNAPSHOT-repackaged.jar`（上一版可运行 fat jar，回滚基线） | 181,892,052 | `1D3C583F258515A90DF5D513EDE4955607583CAE4BCB13CF6B7EC6C042AE8D96` |

说明：旧标准名 jar 是 30KB 瘦 jar（被覆写过的不可运行制品），因此构建前就先备份为 `.prev.bak`；构建需替换该路径文件，在 Windows 下必须先释放其文件锁，所以停止旧进程的动作提前到构建前执行。

---

## 3. JWT secret 持久化

- 生成：48 字节随机数 → Base64（64 字符，≥32 字节要求满足）
- 落地：`D:\tools\_aps-fatbuild\secrets\jwt.secret`
- 属性：UTF-8、无 BOM、无换行、仅本机文件；未写入仓库/日志/本报告正文
- 后续重启（含本轮 18085 与 18095）均复用该文件值；本轮未再次生成新 secret

---

## 4. 18085 接管

| 项 | 值 |
|---|---|
| 旧进程 | PID 29264（旧制品；日志中已出现 `NoClassDefFoundError`，实例实际处于损坏状态） |
| 停止耗时 | 3.5 s，端口 18085 立即无 LISTEN |
| 新进程 | **PID 30716** |
| 制品 | 第 2 节 fat jar（标准名路径） |
| 启动参数 | 完全沿用原参数：`--spring.profiles.active=dev --server.port=18085 --aps.security.mode=dev --aps.security.dev.api-key-enabled=true --aps.security.api-keys.read=... --aps.security.api-keys.write=...` |
| 环境变量 | `DB_HOST=127.0.0.1`、`DB_PORT=55432`、`DB_NAME=aps_local`、`APS_SECURITY_JWT_SECRET=<jwt.secret 文件值>`、`APS_ADMIN_INIT_PASSWORD=<环境变量注入，勿写入仓库>` |
| 启动耗时 | Spring Boot 自报 **5.579 s**（process running 6.183 s） |
| 健康检查 | 200 |

### 接管日志关键行（原文）

```
2026-11-05T01:31:31.487+08:00 INFO ... DbValidate - Successfully validated 9 migrations (execution time 00:00.035s)
2026-11-05T01:31:31.594+08:00 INFO ... DbMigrate - Current version of schema "public": 12
2026-11-05T01:31:31.598+08:00 INFO ... DbMigrate - Schema "public" is up to date. No migration necessary.
2026-11-05T01:31:33.012+08:00 INFO ... TomcatWebServer - Tomcat started on port 18085 (http) with context path '/'
2026-11-05T01:31:33.024+08:00 INFO ... ApsAdminApplication - Started ApsAdminApplication in 5.579 seconds (process running for 6.183)
```

`Ambiguous mapping` 次数：**0**。

---

## 5. 冒烟结果（8/8）

所有 HTTP 均为真实请求：`curl.exe --data-binary @<UTF-8 无 BOM 临时文件>`。

| # | 项目 | 请求 | HTTP | code | traceId | 结论 |
|---|---|---|---|---:|---|---|
| a | 健康检查 | `GET /api/v1/health` | 200 | 0 | `408cba63dafb4d1fb21263ee1ab76904` | status=UP |
| b | 登录 | `POST /api/v1/auth/login` | 200 | 0 | `ad97eb25740a4556baddfa215b217d33` | Bearer，expiresIn=28800 |
| c1 | 订单 | `GET /api/v1/orders?page=1&size=5` | 200 | 0 | `29e3876484674c6ca143f599df02ec90` | total=17 |
| c2 | 计划 | `GET /api/v1/plans?sandboxId=a2a2a2a2-...-0001` | 200 | 0 | `863a57e7d7ac4e8ca413e6e781c47cba` | total=4（QA2 场景） |
| c3 | 物料导出 | `GET /api/v1/materials/export` | 200 | — | — | xlsx，5,037 B，魔数 `50-4B-03-04` |
| d | 修复项1 发布范围 | 两 sandbox 求解/增量/发布 | 200 | 0 | P1 `7001278b...`、P2 `1d54d25c...`、B `3d17e587...` | 见落库原文 |
| e | 修复项2 日历时区 | 求解后查任务开始时间 | 200 | 0 | — | 本地 **08:00:00** |
| f | 修复项3 MRP 缓存 | `GET /mrp/suggestions?...&horizonDays=45` 连续两次 | 200 | 0 | `2123224d1c6d4bada94cebc1928e0158` | 冷 435 ms → 热 **5.16 ms** |
| g | 修复项4 模板列 | `GET /api/v1/routings/import/template` | 200 | — | — | 10 列，含「工序模版编码」「标准工时(秒)」 |
| h | 修复项5 齐套拦截 | `POST /solve-tasks`（零库存场景） | **422** | **42200** | `2a042c43e3ef4018bb963b333decfb46` | 拒绝出计划 |

### 修复项 1：发布 B 不改动 A（落库原文）

同沙箱 S1（`f1000000-...-0001`）两批增量发布后的 `task.plan_version_id` 分布：

```
sandbox_id                            order_no   plan_version.version_no   status      tasks
f1000000-0000-4000-8000-000000000001  M3-A1      v1                        PUBLISHED   3
f1000000-0000-4000-8000-000000000001  M3-A2      v2                        PUBLISHED   3
```

发布 P2（scope=`M3-A2`）后，`M3-A1` 的 3 条任务仍指向 v1，未被回填改写；`affectedTaskCount=3`。
跨场景 S2（`f2000000-...-0002`）发布后：

```
f1000000-...-0001  M3-A1  v1  3      （保持不变）
f1000000-...-0001  M3-A2  v2  3      （保持不变）
f2000000-...-0002  M3-A1  v1  3      （S2 自己的版本）
```

### 修复项 2：班次展开本地时间（落库原文）

```
task.id=1797  order=M3-A1  operation_index=10  start_time=2026-11-05 08:00:00+08
```

同一批任务首工序均为本地 08:00（资源日历 08:00–16:00，绑定了 `calendar_id`），不是旧缺陷的 16:00。

### 修复项 5：零库存全量求解被拦截（原文）

```
HTTP 422  code=42200
message=MRP 齐套未通过（SHORTAGE），缺料物料: DEMO-RM-301, DEMO-RM-302, DEMO-SA-200
```

拦截后落库核验：该 sandbox 的 `task` 数 = **0**、`plan_version` 数 = **0**，未产出计划。

### 修复项 4：模板表头（10 列）

```
产品物料编码 * | 路线版本 * | 生效日期 * | 失效日期 | 节点序号 | 前驱节点序号 | 工序编码 | 工序模版编码 | 标准工时(秒) | 主资源编码
```

0 基列数为 10（A1–J1），文件 7,000 B，xlsx 魔数 `50-4B-03-04`。

---

## 6. 基线库与自建库

- `aps_local`：仅只读查询 + Flyway validate。启动日志明确 `No migration necessary`；`flyway_schema_history` 仍为 9 行（installed_rank 1–9，installed_on 均为 2026-11-04，success=t），无新增记录、无 DDL/DML。
- `aps_local_2`：全程未连接。
- 自建写库 `aps_final_smoke`：由 `aps_local` 模板克隆（只读源库），用于修复项 1/2/5 的写入类冒烟（端口 18095，同一 fat jar）。
- 清理：停 18095 → `DROP DATABASE aps_final_smoke WITH (FORCE)` 成功；库清单恢复，净变化 **0**。

---

## 7. 这次接管过程中的非预期现象

1. **首轮全量回归 1 个 Error**：`MrpSuggestionRealDbIntegrationTest.inventoryWriteInvalidatesSameParameterCache` 用 `now.plusSeconds(1)`（`now` 为 `@BeforeEach` 起始时刻）写库存，而 setup 末尾已用当前时刻写入快照，用例耗时 >1s 时新快照反而更旧，触发库存快照防倒灌断言。单测重跑稳定复现，判定为用例时间戳缺陷；改为 `OffsetDateTime.now(UTC).plusSeconds(1)`（并移除已不使用的 `now` 字段）后通过。业务代码未改。
2. **旧 18085 实例本身已损坏**：旧日志存在 `NoClassDefFoundError`（运行中的 JVM 底下标准名 jar 被 30KB 瘦 jar 覆写，类加载失败），接管同时修复了该问题。
3. **新实例首次启动 PID 28788 在启动 shell 退出后被系统回收**：健康检查已 200、启动耗时 8.1s，但进程随父 shell 结束而退出；改用常驻后台方式重启，得到最终 PID 30716（同一制品、同一 secret）。
4. **`mvn package` 阶段（未设 `APS_TEST_DB_ENABLED`）跳过了 70 个 env-gated 真库用例**：这是构建阶段的正常 Assumption skip，不是回归口径；正式回归（第 1 节）为 Skipped=0。
5. **18095 冒烟数据准备**：克隆库中 QA2 路线的 n10/n30 未绑定主资源、三个资源未绑定资源日历，导致首次求解失败/排到非班次时刻。属自建库数据配置缺失，补绑后正常（不涉及产品代码）。
6. **求解终态值为 `SOLVED`（非 `COMPLETED`）**：轮询脚本按 `COMPLETED` 等待导致多等 90s；改为 `SOLVED` 终态后正常。
7. **接管后 18085 存在 5 条 `TIME_WAIT` 连接**：旧实例停止后的正常 TCP 残留，不影响新实例 LISTEN 与健康检查。

---

## 8. 结论

- 终版 fat jar 已按标准名落地，指标与基线一致（条目/lib/迁移数不变），字节与 SHA 差异可由 5 项修复解释。
- 18085 已由新制品接管，Flyway 仅校验 9 条迁移并判定无需迁移，零 `Ambiguous mapping`。
- 8 项冒烟全部通过，修复项 1–5 均有真实 HTTP + 落库/文件证据。
- `aps_local` 无 DDL/DML，`aps_local_2` 未触碰，自建库已清理，净变化 0。
