# M3-QA-03 依赖漏洞扫描明细

- 核查日期：2026-11-04（OSV 数据为当日在线查询）
- 扫描对象：`aps-admin` 运行期依赖树（110 个 Maven 模块）+ fat jar `aps-admin-0.1.0-SNAPSHOT-repackaged.jar`（112 个 `BOOT-INF/lib`，9 个本仓模块 + 103 个第三方构件）
- 扫描工具：**trivy 0.76.1 未能产出结果**（漏洞库 OCI artifact `mirror.gcr.io/aquasec/trivy-db:2` 下载速率过低，默认 5 分钟与 `--timeout 25m` 两次尝试均未完成——首轮超时、次轮手动终止，未使用任何估算值）
- 实际取值工具：OSV.dev REST API（`POST /v1/querybatch` 精确坐标+版本；`GET /v1/vulns/{id}`），查询日期 2026-11-04；汇总脚本 PowerShell 5.1
- 严重度口径：优先取 GHSA `database_specific.severity`（`MODERATE` → Medium）；缺失时按 CVSS v3.1 向量计算；本批 68 条均有明确等级
- 说明：**未修改 pom / 依赖版本**；未启动任何后端实例；未连接 `aps_local` / `aps_local_2` / 18085

## 计数

| Critical | High | Medium | Low | 合计 | 不同 CVE 数 | 受影响第三方构件 |
| --- | --- | --- | --- | --- | --- | --- |
| 7 | 25 | 26 | 10 | 68 | 68 | 14 |

## 扫描覆盖

| 项 | 数值 | 说明 |
| --- | --- | --- |
| 依赖树模块数 | 110 | `aps-admin/target/w5cp-filtered.txt`（Maven build-classpath 产物） |
| fat jar 内 lib | 112 | 9 个本仓模块（`aps-*` 5 + `solver-*` 4）+ 103 个第三方 |
| 依赖树中有、jar 中无 | 8 | 全部为 `spring-boot-starter-*` 空壳描述符（无类文件），已一并查询，0 条命中 |
| jar 中有、依赖树中无 | 1 | `org.springframework.boot:spring-boot-jarmode-tools:3.3.13`，单独查询 0 条 |
| 第三方构件覆盖率 | 103/103 | 均按 `groupId:artifactId` + 精确版本查询 |

## 逐条明细（Critical / High / Medium / Low）

| 严重度 | CVE | 构件 | 当前版本 | 修复版本 | OSV ID |
| --- | --- | --- | --- | --- | --- |
| Critical | CVE-2026-41293 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.55 | GHSA-r29c-68gh-xp6x |
| Critical | CVE-2026-43512 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.55 | GHSA-h6fc-48rj-7qqh |
| Critical | CVE-2026-43515 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.55 | GHSA-5m62-pw8w-7w9f |
| Critical | CVE-2026-47884 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-pc63-qcmh-9cmg |
| Critical | CVE-2026-65182 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.58 | GHSA-gcx9-497g-6cp6 |
| Critical | CVE-2026-65905 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.58 | GHSA-9xv2-5v5q-p794 |
| Critical | CVE-2026-68525 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.58 | GHSA-h3x4-894j-xpx5 |
| High | CVE-2025-41249 | org.springframework:spring-core | 6.1.21 | 未给出 | GHSA-jmp9-x22r-554x |
| High | CVE-2025-48989 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.44 | GHSA-gqp3-2cvr-x8m3 |
| High | CVE-2025-52520 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.43 | GHSA-wr62-c79q-cv37 |
| High | CVE-2025-53506 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.43 | GHSA-25xr-qj8w-c4vf |
| High | CVE-2025-55752 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.45 | GHSA-wmwf-9ccg-fff5 |
| High | CVE-2026-24734 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.52 | GHSA-mgp5-rv84-w37q |
| High | CVE-2026-24880 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.52 | GHSA-563x-q5rq-57qp |
| High | CVE-2026-34483 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.54 | GHSA-rv64-5gf8-9qq8 |
| High | CVE-2026-34487 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.54 | GHSA-x4m4-345f-5h5g |
| High | CVE-2026-40973 | org.springframework.boot:spring-boot | 3.3.13 | 未给出 | GHSA-wwpq-f5c3-7hvx |
| High | CVE-2026-40984 | io.micrometer:micrometer-core | 1.13.15 | 未给出 | GHSA-g3pr-3p32-fp23 |
| High | CVE-2026-41284 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.55 | GHSA-gx5v-xp9w-j4cg |
| High | CVE-2026-41842 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-x23c-287f-qqv5 |
| High | CVE-2026-41845 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-3chg-m5w7-qfv5 |
| High | CVE-2026-41850 | org.springframework:spring-expression | 6.1.21 | 未给出 | GHSA-r5w3-xv2f-j59q |
| High | CVE-2026-42498 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.55 | GHSA-fv25-8xcx-gqjc |
| High | CVE-2026-43513 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.55 | GHSA-5mp6-jrq3-r938 |
| High | CVE-2026-54512 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.8 | GHSA-j3rv-43j4-c7qm |
| High | CVE-2026-54513 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.8 | GHSA-rmj7-2vxq-3g9f |
| High | CVE-2026-68494 | com.fasterxml.jackson.core:jackson-core | 2.17.3 | 2.18.8 | GHSA-r7wm-3cxj-wff9 |
| High | CVE-2026-68497 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.10 | GHSA-q4xh-88c3-wmh7 |
| High | CVE-2026-89407 | com.fasterxml.jackson.core:jackson-core | 2.17.3 | 2.18.11 | GHSA-p6pp-m3f8-5c89 |
| High | CVE-2026-89425 | com.fasterxml.jackson.core:jackson-core | 2.17.3 | 2.18.11 | GHSA-7hhh-6rmp-j9qf |
| High | CVE-2026-91776 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.11 | GHSA-wv8q-qhhj-9h54 |
| High | CVE-2026-91777 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.11 | GHSA-cxp5-3px4-pw24 |
| Medium | CVE-2025-11226 | ch.qos.logback:logback-core | 1.5.18 | 1.5.19 | GHSA-25qh-j22f-pwp8 |
| Medium | CVE-2025-31672 | org.apache.poi:poi-ooxml | 5.3.0 | 5.4.0 | GHSA-gmg8-593g-7mv3 |
| Medium | CVE-2025-41242 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-r936-gwx5-v52f |
| Medium | CVE-2025-41254 | org.springframework:spring-websocket | 6.1.21 | 未给出 | GHSA-7fch-4f2f-jcgm |
| Medium | CVE-2025-48924 | org.apache.commons:commons-lang3 | 3.14.0 | 3.18.0 | GHSA-j288-q9x7-2f5v |
| Medium | CVE-2025-66614 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.50 | GHSA-fpj8-gq4v-p354 |
| Medium | CVE-2026-18401 | com.fasterxml.jackson.core:jackson-core | 2.17.3 | 2.18.6 | GHSA-72hv-8253-57qq |
| Medium | CVE-2026-19032 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.10 | GHSA-wjgm-6hv5-3cvf |
| Medium | CVE-2026-22737 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-4773-3jfm-qmx3 |
| Medium | CVE-2026-22745 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-6p4f-wcwh-5vvm |
| Medium | CVE-2026-25854 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.53 | GHSA-9m3c-qcxr-9x87 |
| Medium | CVE-2026-41001 | org.springframework.boot:spring-boot-autoconfigure | 3.3.13 | 未给出 | GHSA-ggg2-9786-hwc8 |
| Medium | CVE-2026-41838 | org.springframework:spring-websocket | 6.1.21 | 未给出 | GHSA-q723-847q-5g8g |
| Medium | CVE-2026-41841 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-mq64-j8f9-9gcj |
| Medium | CVE-2026-41843 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-72pg-x5f8-j25j |
| Medium | CVE-2026-41844 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-h3qp-gqrc-q736 |
| Medium | CVE-2026-41846 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-957g-f97v-vppc |
| Medium | CVE-2026-41851 | org.springframework:spring-expression | 6.1.21 | 未给出 | GHSA-wxpp-56q6-5pcg |
| Medium | CVE-2026-41853 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-cjpg-rgq5-fr37 |
| Medium | CVE-2026-49844 | org.apache.logging.log4j:log4j-api | 2.23.1 | 2.25.5 | GHSA-qv9r-c865-cp47 |
| Medium | CVE-2026-54514 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.8 | GHSA-hgj6-7826-r7m5 |
| Medium | CVE-2026-54515 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.9 | GHSA-5jmj-h7xm-6q6v |
| Medium | CVE-2026-59888 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.8 | GHSA-3pjw-73gf-8qr5 |
| Medium | CVE-2026-59889 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.9 | GHSA-5gvw-p9qm-jgwh |
| Medium | CVE-2026-77310 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.9 | GHSA-vvgp-rfg2-7rr6 |
| Medium | CVE-2026-83557 | com.fasterxml.jackson.core:jackson-databind | 2.17.3 | 2.18.10 | GHSA-gx83-3vf8-gh7j |
| Low | CVE-2025-55754 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.45 | GHSA-vfww-5hm6-hx2j |
| Low | CVE-2025-61795 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.47 | GHSA-hgrr-935x-pq79 |
| Low | CVE-2026-10532 | ch.qos.logback:logback-core | 1.5.18 | 1.5.34 | GHSA-jhq6-gfmj-v8fx |
| Low | CVE-2026-1225 | ch.qos.logback:logback-core | 1.5.18 | 1.5.25 | GHSA-qqpg-mvqg-649v |
| Low | CVE-2026-22735 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-6hcq-hmm3-jj3c |
| Low | CVE-2026-22741 | org.springframework:spring-webmvc | 6.1.21 | 未给出 | GHSA-wg35-8jpf-2xv3 |
| Low | CVE-2026-41848 | org.springframework:spring-core | 6.1.21 | 未给出 | GHSA-659m-px2c-25wj |
| Low | CVE-2026-41852 | org.springframework:spring-expression | 6.1.21 | 未给出 | GHSA-9f52-rjqv-25qv |
| Low | CVE-2026-43514 | org.apache.tomcat.embed:tomcat-embed-core | 10.1.42 | 10.1.55 | GHSA-9m89-8frq-c98c |
| Low | CVE-2026-9828 | ch.qos.logback:logback-core | 1.5.18 | 1.5.33 | GHSA-p47f-322f-whfh |



## 按构件汇总

| 构件 | 版本 | Critical | High | Medium | Low | 合计 |
| --- | --- | --- | --- | --- | --- | --- |
| `ch.qos.logback:logback-core` | 1.5.18 | 0 | 0 | 1 | 3 | 4 |
| `com.fasterxml.jackson.core:jackson-core` | 2.17.3 | 0 | 3 | 1 | 0 | 4 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.3 | 0 | 5 | 7 | 0 | 12 |
| `io.micrometer:micrometer-core` | 1.13.15 | 0 | 1 | 0 | 0 | 1 |
| `org.apache.commons:commons-lang3` | 3.14.0 | 0 | 0 | 1 | 0 | 1 |
| `org.apache.logging.log4j:log4j-api` | 2.23.1 | 0 | 0 | 1 | 0 | 1 |
| `org.apache.poi:poi-ooxml` | 5.3.0 | 0 | 0 | 1 | 0 | 1 |
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.42 | 6 | 11 | 2 | 3 | 22 |
| `org.springframework.boot:spring-boot` | 3.3.13 | 0 | 1 | 0 | 0 | 1 |
| `org.springframework.boot:spring-boot-autoconfigure` | 3.3.13 | 0 | 0 | 1 | 0 | 1 |
| `org.springframework:spring-core` | 6.1.21 | 0 | 1 | 0 | 1 | 2 |
| `org.springframework:spring-expression` | 6.1.21 | 0 | 1 | 1 | 1 | 3 |
| `org.springframework:spring-webmvc` | 6.1.21 | 1 | 2 | 8 | 2 | 13 |
| `org.springframework:spring-websocket` | 6.1.21 | 0 | 0 | 2 | 0 | 2 |

## 无修复版本的条目（23 条）

OSV 记录未给出 `fixed` 事件（部分为分支 `last_affected` 收口、修复只存在于更高大版本）。**“未给出”≠“不可修复”**，需人工核对上游安全公告后决定升级路线：

CVE-2025-41242, CVE-2025-41249, CVE-2025-41254, CVE-2026-22735, CVE-2026-22737, CVE-2026-22741, CVE-2026-22745, CVE-2026-40973, CVE-2026-40984, CVE-2026-41001, CVE-2026-41838, CVE-2026-41841, CVE-2026-41842, CVE-2026-41843, CVE-2026-41844, CVE-2026-41845, CVE-2026-41846, CVE-2026-41848, CVE-2026-41850, CVE-2026-41851, CVE-2026-41852, CVE-2026-41853, CVE-2026-47884

其中 Critical/High 共 7 条：

| CVE | 构件 | 版本 | 等级 |
| --- | --- | --- | --- |
| CVE-2025-41249 | org.springframework:spring-core | 6.1.21 | High |
| CVE-2026-40973 | org.springframework.boot:spring-boot | 3.3.13 | High |
| CVE-2026-40984 | io.micrometer:micrometer-core | 1.13.15 | High |
| CVE-2026-41842 | org.springframework:spring-webmvc | 6.1.21 | High |
| CVE-2026-41845 | org.springframework:spring-webmvc | 6.1.21 | High |
| CVE-2026-41850 | org.springframework:spring-expression | 6.1.21 | High |
| CVE-2026-47884 | org.springframework:spring-webmvc | 6.1.21 | Critical |

特别说明 `CVE-2026-47884`（Spring Framework `XsltView` 路径限制不当）：OSV 记录显示 6.1.x/6.2.x 以 `last_affected` 收口（6.2.19），`fixed` 仅出现在 7.0.9；即 **当前 6.1.21 无同分支修复版本**。是否可利用取决于应用是否使用 `XsltView`（本次**未做可达性验证**）。按纪律未改 pom。

## 复现方式（只读）

示例请求（每个依赖一条，`queries` 数组可批量）：

```json
POST https://api.osv.dev/v1/querybatch
{"queries":[{"package":{"name":"org.apache.tomcat.embed:tomcat-embed-core","ecosystem":"Maven"},"version":"10.1.42"}]}
```

拿到 `id` 后 `GET https://api.osv.dev/v1/vulns/{id}`，读取 `database_specific.severity`、`aliases`（CVE）、`affected[].ranges[].events[].fixed`。

## 已知限制

- **trivy 未产出结果**：本机无 `java`/`mvn`/`trivy` 可执行文件；Docker 内 trivy 0.76.1 可运行，但漏洞库下载速率约几十 KB/s，两次尝试（默认 5 分钟与 `--timeout 25m`，含本地代理）均未能在可接受时间内完成（首轮 5 分钟超时、次轮手动终止）。因此未给出 trivy 口径的计数。
- **未扫容器 OS 层**：本次只扫 JAR 依赖，未对 `aps-backend` 镜像的 JDK/系统包做 CVE 扫描（需可用的 trivy DB）。
- **静态版本匹配**：结论基于“坐标+版本命中 OSV 影响范围”，**未做类加载/调用可达性验证**（例如 `XsltView`、`poi-ooxml` 是否实际被业务使用）。
- **修复版本判断**：`fixed` 仅取自 OSV；对“未给出”的 23 条需对照上游公告确认。
- **未验证运行时防护**：未启动实例，未做动态验证。