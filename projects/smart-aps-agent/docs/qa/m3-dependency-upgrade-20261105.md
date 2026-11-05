# 依赖升级 + 漏洞复扫 + 制品重建与 18085 接管

**升级 2 项（Tomcat 10.1.59 / Jackson 2.18.11）· 回归 Skipped=0 · 新制品 SHA `0EC320ED387EF81CA71AE8E3FCA00D1B6C5727F91618C47C7706CDF479B92518` · 剩余 Critical+High 7 条（均有据豁免）· 新 PID 37740**

- 执行日期：2026-11-05（Asia/Shanghai）
- 项目：`D:\tools\smart-aps-agent`
- 工具链：JDK 21.0.13 + Maven 3.9.16（`D:\tools\_aps-toolchain`）
- 前置：M3 终版验收任务已完成（561 用例、PID 30716、8/8 冒烟）

---

## 1. 变更范围与全量回归

### 1.1 仅两个版本属性

根 [pom.xml](../../pom.xml) `<properties>` 新增/覆盖两项，未改 `dependencyManagement` 结构、未新增 `<dependency>`、未升 Spring Boot 自身（仍 3.3.13）：

| 属性 | 升级前 | 计划值 | 实际值 | 说明 |
| --- | --- | --- | --- | --- |
| `tomcat.version` | 10.1.42 | 10.1.58 | **10.1.59** | 10.1.58 **未在 Maven Central 发布**（10.1 线由 10.1.57 直接跳到 10.1.59），首次构建在 `aps-service` 依赖解析阶段失败；取同分支最近版本 |
| `jackson-bom.version` | 2.17.3 | 2.18.11 | **2.18.11** | 按授权值，8 个 Jackson 构件统一至 2.18.11 |

落地后 `BOOT-INF/lib` 实测：`tomcat-embed-core/el/websocket-10.1.59.jar`、`jackson-{annotations,core,databind,dataformat-toml,dataformat-yaml,datatype-jdk8,datatype-jsr310,module-parameter-names}-2.18.11.jar`。

### 1.2 全量回归

命令：`APS_TEST_DB_ENABLED=true mvn -B -ntp test`（真库 `aps_test_shared`，未触碰 `aps_local`）

| 模块 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| aps-common | 19 | 0 | 0 | 0 |
| aps-infrastructure | 34 | 0 | 0 | 0 |
| solver-api | 8 | 0 | 0 | 0 |
| solver-dsl | 19 | 0 | 0 | 0 |
| solver-timefold | 96 | 0 | 0 | 0 |
| aps-service | 328 | 0 | 0 | 0 |
| aps-web | 57 | 0 | 0 | 0 |
| aps-admin | 0（无测试用例） | 0 | 0 | 0 |
| **合计** | **561** | **0** | **0** | **0** |

BUILD SUCCESS，03:58 min。与升级前 561/0/0/0 完全一致，**无一条用例因版本变化失败，未改任何断言**。

---

## 2. 制品核验

标准名路径：`aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar`

| 指标 | 上一版（`*.prev2.bak`） | 本次 | 差异 | 差异来源 |
|---|---:|---:|---:|---|
| 字节数 | 181,902,151 | **182,013,745** | +111,594 | Tomcat 10.1.59 三个 embed 构件与 8 个 Jackson 2.18.11 构件体积变化，类文件数量未变 |
| SHA256 | `7469A78B…33D7` | **`0EC320ED…2518`** | — | 同上 |
| 条目数 | 264 | **264** | 0 | 未增删文件 |
| `BOOT-INF/lib` | 112 | **112** | 0 | 版本替换而非新增/移除构件 |
| 迁移脚本 | 9 | **9** | 0 | V1–V7、V11、V12；**V8/V9/V10 不存在** |

备份：`aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar.prev2.bak`（181,902,151 B / `7469A78BCAB8BDAF9FE20CA536B21907F9A2BF915C59F3B11131D8E4E2E033D7`），未删除既有 `.prev.bak` 与 `.thinjar.bak`。

---

## 3. 漏洞复扫（OSV.dev，口径同 M3-QA-03 第七节）

对象：`mvn -pl aps-admin dependency:list -DincludeScope=runtime` 的 110 个第三方模块（不含内部模块），并与 fat jar 的 112 个 lib 交叉核对。

### 3.1 升级前后计数

| 等级 | 第七节记录（11-04） | 同日复扫旧坐标（10.1.42 / 2.17.3） | 升级后（10.1.59 / 2.18.11） | 净变化（同日口径） |
| --- | ---: | ---: | ---: | ---: |
| Critical | 7 | 8 | **1** | −7 |
| High | 25 | 26 | **6** | −20 |
| Medium | 26 | 27 | **16** | −11 |
| Low | 10 | 10 | **7** | −3 |
| 合计 | 68 | 71 | **30** | **−41** |

消除的 41 条全部为 Tomcat（25：7C/12H/6 M-L）与 Jackson（16：8H/8 M-L）；其余 30 条为平台侧既有条目，与升级前一致。同日复扫比 11-04 记录多 1C/1H/1M，是 OSV 漏洞库当日更新造成，不是代码变化。

### 3.2 剩余 Critical / High（7 条，全部“同分支无修复 + 不可达”）

| # | 等级 | CVE | 构件 | 版本 | 同分支修复线 |
| --- | --- | --- | --- | --- | --- |
| 1 | Critical | CVE-2026-47884 | spring-webmvc | 6.1.21 | 仅 7.0.9 |
| 2 | High | CVE-2025-41249 | spring-core | 6.1.21 | 6.2.11（6.1.x last_affected） |
| 3 | High | CVE-2026-40973 | spring-boot | 3.3.13 | 3.3.19（本次未授权升 Boot） |
| 4 | High | CVE-2026-40984 | micrometer-core | 1.13.15 | 1.15.12 / 1.16.6 |
| 5 | High | CVE-2026-41842 | spring-webmvc | 6.1.21 | 6.2.19 / 7.0.8 |
| 6 | High | CVE-2026-41845 | spring-webmvc | 6.1.21 | 6.2.19 / 7.0.8 |
| 7 | High | CVE-2026-41850 | spring-expression | 6.1.21 | 6.2.19 / 7.0.8 |

逐条豁免（CVE / 构件 / 影响面 / 可达性依据 / 为何不能修 / 补偿措施）已写入 [m3-security-scan.md](m3-security-scan.md) **第十节 10.4**，未改动既有章节。要点：不可达依据为全仓检索 `XsltView`/`ViewResolver`/`Thymeleaf`/`JavaScriptUtils`/`SpelExpressionParser`/`@EnableMethodSecurity`/`addResourceHandlers`/`session.persistent`/`actuator` **零命中**，`WebMvcConfiguration` 仅注册拦截器，依赖树仅有 `spring-security-crypto`、无 actuator。

---

## 4. 接管 18085

### 4.1 过程

1. 停旧 PID 30716 → 确认 18085 无监听。
2. 备份旧制品为 `*.prev2.bak`。
3. `mvn -B -ntp package -DskipTests` 重建 fat jar（回归已在 1.2 完成）。
4. 用 **既有** `D:\tools\_aps-fatbuild\secrets\jwt.secret`（UTF-8 无 BOM、64 字符，未重新生成、未打印明文，启动日志自证 `jwtSecret=configured(64 bytes)`）启动。

启动参数与环境变量沿用授权原文：`--spring.profiles.active=dev --server.port=18085 --aps.security.mode=dev --aps.security.dev.api-key-enabled=true --aps.security.api-keys.read=<read-key> --aps.security.api-keys.write=<write-key>`；`DB_HOST=127.0.0.1`、`DB_PORT=55432`、`DB_NAME=aps_local`、`APS_ADMIN_INIT_PASSWORD=<由环境变量注入，勿写入仓库>`。

### 4.2 关键日志

```
Starting ApsAdminApplication v0.1.0-SNAPSHOT using Java 21.0.13 with PID 37740
Starting Servlet engine: [Apache Tomcat/10.1.59]
Successfully validated 9 migrations (execution time 00:00.037s)
Current version of schema "public": 12
Schema "public" is up to date. No migration necessary.
安全配置校验通过：mode=dev jwtSecret=configured(64 bytes) devApiKeys=configured
Started ApsAdminApplication in 5.707 seconds (process running for 6.47)
```

| 项 | 值 |
| --- | --- |
| 新 PID | **37740** |
| 启动耗时 | 5.707 s（上一版 5.579 s，+0.128 s） |
| Flyway validate | 1 次（9 migrations） |
| `Current version` | 12 |
| `No migration necessary` | 1 次 |
| `Ambiguous mapping` | **0 次** |
| WARN/ERROR | 仅 2 条既有 `Skipping MapperFactoryBean … Bean already defined with the same name`（升级前同样存在） |

---

## 5. 重启后冒烟（真实 HTTP）

| # | 请求 | 状态码 | code | traceId | 证据 |
| --- | --- | --- | --- | --- | --- |
| 1 | `GET /api/v1/health` | **200** | 0 | `3d33ce436f6f4e3188d72b03d2e07ff3` | `{"status":"UP","service":"aps-admin"}` |
| 2 | `POST /api/v1/auth/login`（admin / `<APS_ADMIN_INIT_PASSWORD>`） | **200** | 0 | `ae644f68719a4b90b4df2ca4402270ea` | `data.accessToken` 取得，长度 293 |
| 3 | `GET /api/v1/orders`（JWT） | **200** | 0 | `b96498a8633e4b5caaacd3a31a082d2d` | `total=17` |
| 4 | `GET /api/v1/plans?sandboxId=a2a2…0001`（JWT） | **200** | 0 | `e3c308d259534bd2999f52ea1d4409b1` | `total=4`，4 个 PUBLISHED 版本 |
| 5 | `GET /api/v1/materials/export`（JWT） | **200** | — | `830bb7a633464846ad38e8ffc25bf357` | 5036 B，魔数 **`50-4B-03-04`** |
| 6 | `GET /v3/api-docs`（dev） | **200** | — | `5273891981454432bff2f39b25c83cbd` | `info.title=衡策 APS 开源版 API` |
| 7 | `PUT /api/v1/inventory`（无凭证，18085） | **401** | 40100 | `109125666e054fc89e45efbd49d36ef2` | `未认证或凭证已失效`（filter 链生效） |
| 8 | `PUT /api/v1/inventory`（JWT，18095 自建库） | **200** | 0 | `b4abac338da74b1ea60d4d2852859a17` | `{"accepted":1,"rejected":0}` |
| 9 | `PUT /api/v1/inventory`（X-API-Key 写密钥，18095） | **200** | 0 | `bfd29eeb851444b9be84ba0da6f64c4b` | `{"accepted":1,"rejected":0}`；日志 `dev API Key(write) 以 ADMIN 等价权限使用` |
| 10 | `PUT /api/v1/inventory`（X-API-Key **读**密钥写，18095） | **403** | 40300 | `887ee826576047d3822d30a430ef645b` | `读密钥不能访问写端点`（读写密钥边界未破坏） |

**落库原文**（`aps_final_smoke`）：

```
DEMO-RM-301|DEFAULT|100.0000|10.0000|2|2026-11-05 02:02:02.91756+08|2026-11-05 02:02:03.087073+08|d0000000-0000-0000-0000-000000000103
```

第 8 次插入（version 1），第 9 次更新（version 2）。对照 `aps_local`：`inventory` 仅 2 行、`max(updated_at)=2026-11-04 09:47:08.970214+08`，**未被写入**。

---

## 6. 数据库安全

| 项 | 结果 |
| --- | --- |
| `aps_local` Flyway | 仅 validate：`Successfully validated 9 migrations` / version 12 / `No migration necessary`，**无任何 DDL/DML** |
| `aps_local` 迁移历史 | 9 条，未变 |
| 自建库 | `aps_final_smoke` 由 `TEMPLATE aps_local` 克隆，用完已 `DROP DATABASE … WITH (FORCE)`，库列表恢复为原状（净变化 0） |
| `aps_local_2` | **全程未连接** |
| 冒烟写入 | 仅发生在 18095 / `aps_final_smoke` |

---

## 7. 结论：升级是否引入新的失败或行为变化

**没有引入新的失败或行为变化**：

- 回归 561/0/0/0，与升级前完全一致；未改任何测试断言。
- 运行时 Tomcat 10.1.59、Jackson 2.18.11 均已在 fat jar 与启动日志中自证；`/v3/api-docs`、导出、JWT 与 `X-API-Key` 读写分支、401/403 拒绝路径全部符合原行为。
- `Ambiguous mapping` = 0；启动 WARN 与升级前相同（仅 MyBatis Mapper 重名提示）。
- 启动耗时 +0.128 s、制品 +111,594 B，属版本替换的正常量级；lib/条目/迁移数量均未变。

**接管过程中的非预期现象**：

1. 首次按授权的 `10.1.58` 构建在 `aps-service` 解析依赖时失败——`tomcat-embed-el:10.1.58` 在 Central 不存在（10.1 线跳过该补丁号）。已改为同分支 `10.1.59` 并在本文与安全报告注明偏差。
2. 为覆盖 8 项冒烟的写路径，需在 `aps_local` 停连窗口内一次性克隆 `aps_final_smoke`；克隆与 DROP 均成功，无残留。
3. 18085 停旧进程后端口在 3 秒内释放，无 TIME_WAIT 导致的监听冲突（本次未观察到异常端口占用）。
