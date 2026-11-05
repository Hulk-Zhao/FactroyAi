# M1 测试计划与真库集成测试报告（M1-QA-01）

- 任务卡：M1-QA-01 单元测试与集成测试框架
- Owner：dev-QA1
- 执行日期：2026-11-04
- 环境：Windows + JDK 21.0.13 + Maven 3.9.16；便携 PostgreSQL 15（`127.0.0.1:55432/aps_local`）；本机无 Docker
- 结论：新增 17 个真库集成测试、1 套测试基座、1 个本地复跑脚本；全量后端 141 个测试全部通过（0 失败 / 0 跳过）。发现 1 个契约偏差（D-01）与 1 个并发风险（D-02），均已记录未越界修改业务代码。

## 1. 交付物清单

### 1.1 测试基座（`aps-service/src/test/java/com/hengce/aps/service/it/`）

| 文件 | 作用 |
| --- | --- |
| `RealDbEnvironment.java` | 真库连接参数全部来自环境变量：`APS_TEST_DB_ENABLED/URL/HOST/PORT/NAME/USER/PASSWORD`，默认值对齐 `scripts/local-db-up.ps1`；不新增任何会被提交的配置文件 |
| `RealDbTestApplication.java` | 集成测试的 Spring 装配入口，扫描 `com.hengce.aps`，装配与生产相同的 `MybatisPlusConfiguration`、`MybatisTypeHandlerConfiguration`；不装配 Web 层与求解器实现 |
| `RealDbTestDataSourceConfiguration.java` | 显式 PostgreSQL `DataSource`，避免缺少 `application.yml` 时回落到嵌入式库 |
| `AbstractRealDbIntegrationTest.java` | 统一基座：租户/审计上下文绑定、唯一运行前缀 `QA1-<hex>-`、按前缀硬删除清理（不依赖业务事务）、常用建数据辅助方法、证据记录入口 |
| `Evidence.java` | 断言证据同时输出到控制台与 `aps-service/target/qa-evidence/m1-qa-01-evidence.log`（UTF-8） |

数据隔离说明：`CalendarService`/`ResourceService`/`MaterialService` 未声明事务，外层回滚无法覆盖写入，因此采用“运行前缀 + 用例后按前缀删除”。清理顺序为：先断开 `resource`/`resource_group → calendar` 反向引用，再删除 `bom_item → bom → calendar → resource → material`。已验证用例结束后前缀残留为 0 行。

### 1.2 集成测试类（共 17 个用例）

| 测试类 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `BomRealDbIntegrationTest` | 4 | 同父件双 ACTIVE、软删除后版本复用、环检测、四级树 |
| `CalendarRealDbIntegrationTest` | 3 | 历史只读 403、班次重叠 422、`calendar_version` 递增 |
| `ResourceRealDbIntegrationTest` | 4 | `code` 唯一、被日历引用删除 422、三种 `capacity_mode` 落库、缺省产能 |
| `CrossDomainReferenceRealDbIntegrationTest` | 4 | 物料被 BOM 父/子件引用、BOM 被其它 BOM 引用、日历被资源引用 |
| `ProductionPersistenceConfigRealDbTest` | 2 | 生产 TypeHandler 注册、CalendarMapper.xml UUID 参数 |

### 1.3 辅助脚本

`scripts/test-realdb.ps1`：端口未监听时调用 `local-db-up.ps1` 启动便携 PostgreSQL；库为空时自动执行 `db/migration/V1__init.sql`；设置 `APS_TEST_DB_*` 环境变量后执行 `mvn -B -pl aps-service -am test`，不触碰 `aps-admin`。脚本文件使用 UTF-8 BOM，兼容 Windows PowerShell 5.1。

## 2. 覆盖矩阵（任务卡 → 测试 → 关键断言）

| # | 任务卡要求 | 测试类#方法 | 关键断言（不是只断言异常类型） |
| --- | --- | --- | --- |
| 1 | BOM 同父件双 ACTIVE 被部分唯一索引拒绝 | `BomRealDbIntegrationTest#partialUniqueIndexRejectsSecondActiveBom` | 直接 SQL 激活第二个 BOM，异常信息包含 `uq_bom_one_active_per_parent`；随后 `SELECT` 该父件 ACTIVE 行数 = 1，被拒绝行回滚为 `DRAFT` |
| 2 | BOM 软删除后标题/版本可复用 | `BomRealDbIntegrationTest#softDeletedBomVersionCanBeReused` | 删除 V1 后重建同父件 V1 成功；`SELECT bom_version,deleted` 返回两行（1 行 `deleted=true`、1 行 `deleted=false`） |
| 3 | BOM 环检测 | `BomRealDbIntegrationTest#treeDetectsCycleWithPath` | 抛出 42200，消息包含“循环引用”及完整路径 `A -> B -> A` |
| 4 | BOM 多级树 | `BomRealDbIntegrationTest#multiLevelTreeExpandsToFourLevels` | 树节点层级为 L1/L2/L3/L4，叶子 `bomId=null`、`children=[]` |
| 5 | 日历历史只读 403 | `CalendarRealDbIntegrationTest#historicalCalendarUpdateRejectedWithForbidden` | 抛出 `FORBIDDEN(40300)`；`SELECT name,version` 与更新前完全一致 |
| 6 | 日历班次重叠拒绝 | `CalendarRealDbIntegrationTest#overlappingShiftsRejectedWithoutPersistingRow` | 抛出 42200，消息“班次时段重叠”；`SELECT count(*)` = 0（未落库） |
| 7 | `calendar_version` 递增 | `CalendarRealDbIntegrationTest#calendarVersionIncrementsPerName` | 同名依次创建 v1/v2；`SELECT calendar_version ORDER BY` 返回 `[1, 2]` |
| 8 | 资源 `code` 唯一 | `ResourceRealDbIntegrationTest#duplicateResourceCodeRejectedByDatabase` | 抛出 40901；`SELECT count(*) ... deleted=false` = 1 |
| 9 | 资源被日历引用时删除 422 | `ResourceRealDbIntegrationTest#deleteResourceReferencedByCalendarRejectedWith422` | 抛出 42200；`SELECT deleted` 仍为 `false`（未被软删） |
| 10 | `capacity_mode` 三种取值落库 | `ResourceRealDbIntegrationTest#allCapacityModesPersisted` | 查询落库列：`UNIT_PER_HOUR/10.0000`、`SECONDS_PER_UNIT/30.0000`、`BATCH_SECONDS/100.0000/batch_capacity=5` |
| 11 | 资源产能缺省值 | `ResourceRealDbIntegrationTest#capacityValueDefaultsToZeroWhenOmitted` | 未传 `capacityValue` 时落库值 = 0 |
| 12 | 物料被 BOM 父件引用删除拦截 | `CrossDomainReferenceRealDbIntegrationTest#materialReferencedAsBomParentRejected` | 抛出 42200；`SELECT deleted` = `false` |
| 13 | 物料被 BOM 子件引用删除拦截 | `CrossDomainReferenceRealDbIntegrationTest#materialReferencedAsBomChildRejected` | 抛出 42200；`SELECT deleted` = `false` |
| 14 | BOM 父件被其它 BOM 作为子件引用 | `CrossDomainReferenceRealDbIntegrationTest#bomReferencedByOtherBomRejected` | 抛出 42200；`SELECT deleted` = `false` |
| 15 | 日历被资源引用删除拦截 | `CrossDomainReferenceRealDbIntegrationTest#calendarReferencedByResourceReturnsConflictInsteadOf422` | 断言实际 40901（见缺陷 D-01）；`SELECT deleted` = `false` |
| 16 | 生产持久层配置（UUID TypeHandler） | `ProductionPersistenceConfigRealDbTest#productionTypeHandlerRegistryRegistersUuidAndSerializable` | `SqlSessionFactory` 注册表中 `UUID -> UuidTypeHandler`、`Serializable -> ObjectTypeHandler` |
| 17 | CalendarMapper.xml 解析 UUID 参数 | `ProductionPersistenceConfigRealDbTest#calendarMapperXmlResolvesUuidParameters` | `calendarService.get(UUID)` 返回实体 |

## 3. 真库实测结果（2026-11-04）

### 3.1 集成测试

命令：`.\scripts\test-realdb.ps1`

```
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

关键证据（摘自 `aps-service/target/qa-evidence/m1-qa-01-evidence.log`）：

```
SQL=UPDATE bom SET status='ACTIVE' WHERE id='7155...' -> DuplicateKeyException: ... 
    错误: 重复键违反唯一约束"uq_bom_one_active_per_parent"
SQL=SELECT bom_version,status,deleted FROM bom WHERE parent_material_id='ce65...'
     -> [{bom_version=V1, status=ACTIVE, deleted=false}, {bom_version=V2, status=DRAFT, deleted=false}]
SQL=SELECT bom_version,deleted FROM bom WHERE parent_material_id='8a48...'
     -> [{bom_version=V1, deleted=true}, {bom_version=V1, deleted=false}]
BOM 树 -> A(L1)[B(L2)[C(L3)[D(L4)]]]
BOM 树环检测 -> BOM 存在循环引用: MAT-CYCLE-A -> MAT-CYCLE-B -> MAT-CYCLE-A
CREATE 重叠班次(08:00-12:00, 11:00-13:00) -> 422: 班次时段重叠：08:00-12:00 与 11:00-13:00
SQL=SELECT count(*) FROM calendar WHERE name='...CAL-OVERLAP' -> 0
UPDATE 历史日历(effectiveTo=2025-12-31) -> FORBIDDEN: 历史日历只读：effectiveTo 早于今天
SQL=SELECT name,version,effective_to ... -> {name=...CAL-HISTORY, version=1, effective_to=2025-12-31}
SQL=SELECT calendar_version ... ORDER BY calendar_version -> [1, 2]
DELETE 被日历引用的资源 code='...RES-REF' -> 422: 资源已被 日历 引用，无法停用
SQL=SELECT deleted FROM resource WHERE code='...RES-REF' -> {deleted=false}
SQL=SELECT code,capacity_mode,capacity_value,batch_capacity ...
     -> [RES-BATCH/BATCH_SECONDS/100.0000/5, RES-SPU/SECONDS_PER_UNIT/30.0000/null,
         RES-UPH/UNIT_PER_HOUR/10.0000/null]
SqlSessionFactory TypeHandlerRegistry: UUID -> UuidTypeHandler, Serializable -> ObjectTypeHandler
CalendarMapper.findById(UUID=9035...) -> ...CAL-UUID 查询成功
```

用例结束后按前缀核对残留：`material/resource/calendar` 中 `QA1-%` 均为 0 行。

### 3.2 全量后端测试（单测 + 集成）

命令：`mvn -B -pl aps-service -am test`（`APS_TEST_DB_ENABLED=true`）

| 模块 | 用例数 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| aps-common | 19 | 0 | 0 | 0 |
| aps-infrastructure | 25 | 0 | 0 | 0 |
| solver-api | 7 | 0 | 0 | 0 |
| aps-service（含 17 个真库集成） | 90 | 0 | 0 | 0 |
| **合计** | **141** | **0** | **0** | **0** |

未设置 `APS_TEST_DB_ENABLED` 时，5 个集成测试类被 JUnit 条件跳过（实测 `Skipped: 17`，构建成功），因此无数据库的开发机/CI 不会误报。

### 3.3 既有 BD-01 回归 harness 在生产配置上复跑

`LocalDbRegressionHarness` 未注册任何测试侧 TypeHandler，启动的是生产 `ApsAdminApplication` 配置。本次用当前 reactor `target/classes`（排除 `.m2` 中并发构建产生的过期 `aps-*` jar）运行：

```
RESULT passed=13 failed=0
```

说明：harness 通过；由于并发会话正在构建，`aps-admin/target` 中的 fat jar 当前是 18 KB 的 thin jar，未使用 `package/repackage` 重新打包，改以 `target/classes` 直接运行，避免抢 `target`。

## 4. 测试门禁

- `mvn verify` 会执行 `test` 阶段：根 `pom.xml` 使用 Surefire 3.2.5，未配置 `skipTests` 或排除规则；任一测试失败即 `BUILD FAILURE`。
- CI 现状：`.github/workflows/ci.yml` 的 `backend-test` job 已执行 `mvn ... test`，测试步骤原本就不缺；`static-analysis` 使用 `-DskipTests verify` 仅跑 Checkstyle/PMD。
- 为让新增真库集成测试在 CI 真正执行，仅对 `backend-test` 做了最小补丁（新增 postgres service、建库步骤、`APS_TEST_DB_*` 环境变量），未改动其它 job。

### CI 补丁 diff

```diff
   backend-test:
     name: 单元测试 + 覆盖率
     runs-on: ubuntu-latest
     timeout-minutes: 30
+    # 真库集成测试（M1-QA-01）需要 PostgreSQL；用 service 容器提供，避免 Testcontainers
+    services:
+      postgres:
+        image: postgres:15
+        env:
+          POSTGRES_DB: aps_local
+          POSTGRES_USER: aps
+          POSTGRES_PASSWORD: aps_dev_pwd
+        ports:
+          - 5432:5432
+        options: >-
+          --health-cmd "pg_isready -U aps -d aps_local"
+          --health-interval 10s
+          --health-timeout 5s
+          --health-retries 10
+    env:
+      APS_TEST_DB_ENABLED: 'true'
+      APS_TEST_DB_HOST: 127.0.0.1
+      APS_TEST_DB_PORT: '5432'
+      APS_TEST_DB_NAME: aps_local
+      APS_TEST_DB_USER: aps
+      APS_TEST_DB_PASSWORD: aps_dev_pwd
     steps:
       - name: 检出代码
         uses: actions/checkout@v4
 
       - name: 安装 JDK 21 + Maven 缓存
         ...
 
+      - name: 初始化测试库
+        # 复用 postgres:15 镜像内的 psql；host 网络访问 service 容器映射端口
+        run: |
+          docker run --rm -i --network host -e PGPASSWORD=aps_dev_pwd postgres:15 \
+            psql -h 127.0.0.1 -p 5432 -U aps -d aps_local -v ON_ERROR_STOP=1 \
+            -f - < db/migration/V1__init.sql
+
       - name: 执行单测并生成 JaCoCo 报告
```

## 5. 发现的业务缺陷（未修改业务代码）

### D-01 日历被资源引用删除返回 409，任务卡要求 422（确认）

- 位置：`aps-service/src/main/java/com/hengce/aps/service/masterdata/calendar/CalendarService.java:187-188`
- 现状：`hasResourceReference` 命中时抛出 `ErrorCode.STATE_CONFLICT(40901)`，经 `RestExceptionHandler` 映射为 HTTP 409。
- 期望：M1-QA-01 任务卡要求“删除被引用记录必须 422”（`ErrorCode.UNPROCESSABLE_ENTITY=42200`，HTTP 422）。
- 复现：`.\scripts\test-realdb.ps1`，查看证据第 12 行；或
  `mvn -B -pl aps-service -am test -Dtest=CrossDomainReferenceRealDbIntegrationTest`
- 实际证据：`DELETE 被资源 ... 引用的日历 -> 实际 STATE_CONFLICT(日历已被资源引用，禁止删除)，任务卡要求 42200`
- 归属：M1-BD-05 工作日历接口，owner `dev-BD3`。本套件按实际行为断言并注释偏差，保持门禁为绿色，待 owner 确认后改为 42200（测试断言同步改为 42200）。

### D-02 BOM 并发激活可能以原始 DuplicateKeyException 暴露为 500（风险，待确认）

- 位置：`aps-service/src/main/java/com/hengce/aps/service/masterdata/bom/BomService.java:201`
- 现状：`activate` 依赖“先查后改”，并发下两个请求都可能通过业务校验；第二个请求触发 `uq_bom_one_active_per_parent`，当前未捕获 `DataIntegrityViolationException`，会以非业务异常上抛。
- 证据：测试 1 通过直接 SQL 激活第二个 ACTIVE，实际抛出 `DuplicateKeyException`（含索引名）。
- 说明：单线程用例无法稳定复现真实并发，登记为风险而非确认缺陷；建议 owner 评估是否捕获并映射为 40901。
- 归属：M1-BD-03 BOM 管理接口，owner `dev-BD3`。

### 低优先级文案观察（不计入缺陷）

- `ResourceService.java:183` 删除路径提示“无法停用”，语义应为“无法删除”。
- `MaterialService.java:153` 文案“BOM 子件 引用”存在多余空格。

## 6. 已知未覆盖项

1. Web 层 HTTP 状态码/响应体映射：本套件在服务层断言 `ErrorCode`，未启动 MVC 验证 403/409/422 的 HTTP 映射（`RestExceptionHandler` 由现有单测覆盖）。
2. 并发竞争：同时激活 BOM、同时创建同编码资源、同时更新版本等竞态未做多线程压测；仅验证了数据库唯一约束的兜底行为。
3. 多租户并存：本套件固定默认租户；跨租户隔离与同编码并存由 BD-01 harness 覆盖。
4. 软删除后编码复用：已覆盖 BOM 版本复用；物料/资源编码、日历同名版本在软删除后的复用未覆盖。
5. 日历边界：节假日、跨午夜班次、首尾相接（12:00 结束 / 12:00 开始）未覆盖。
6. 订单域引用：物料/资源被生产订单引用时的删除拦截未覆盖（需要订单服务与状态机）。
7. BOM 深度上限：仅验证 4 层，`MAX_TREE_DEPTH=5` 与超深截断未覆盖。
8. 性能与大数据量：大 BOM 树、分页上限、批量导入未覆盖。
9. 前端、部署、真实数据库迁移回滚：不在本卡范围。

## 7. 本地复跑

一键：

```powershell
.\scripts\test-realdb.ps1
```

手动（等价）：

```powershell
$env:JAVA_HOME = 'D:\tools\_aps-toolchain\jdk-21.0.13+9'
$env:PATH = "$env:JAVA_HOME\bin;D:\tools\_aps-toolchain\apache-maven-3.9.16\bin;$env:PATH"
$env:APS_TEST_DB_ENABLED = 'true'
$env:APS_TEST_DB_HOST = '127.0.0.1'
$env:APS_TEST_DB_PORT = '55432'
$env:APS_TEST_DB_NAME = 'aps_local'
$env:APS_TEST_DB_USER = 'aps'
$env:APS_TEST_DB_PASSWORD = 'aps_dev_pwd'
mvn -B -pl aps-service -am test -Dcheckstyle.skip=true -Dpmd.skip=true
```

只跑集成测试：

```powershell
mvn -B -pl aps-service -am test -Dtest='*RealDbIntegrationTest,ProductionPersistenceConfigRealDbTest' -Dsurefire.failIfNoSpecifiedTests=false
```

不设置 `APS_TEST_DB_ENABLED` 时集成测试自动跳过；`aps-service/target/qa-evidence/m1-qa-01-evidence.log` 为本次运行的证据文件。

## 8. 边界与未修改项

- 未修改：`db/migration/**`、`docs/api/**`、`deploy/**`、前端、PRD、`controller/contract/**`、`persistence/config/**`。
- 修改范围：`aps-service/src/test/**`（新增 10 个测试文件）、`aps-service/pom.xml`（新增 `org.postgresql:postgresql` test 作用域依赖）、`docs/testing/m1-test-plan.md`、`scripts/test-realdb.ps1`、`.github/workflows/ci.yml`（仅 `backend-test` 最小补丁）。
- 已知环境问题：`.m2` 中存在并发构建产生的过期 `aps-*` SNAPSHOT（如缺 `CapacityMode`），因此集成测试命令必须带 `-am` 使用 reactor `target/classes`，不要只写 `-pl aps-service`。
