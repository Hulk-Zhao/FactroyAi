# 数据库迁移（Flyway）

> 目录：`db/migration/`；架构说明见 [schema.md](./schema.md)；契约见 [../api/contract-freeze.md](../api/contract-freeze.md)。

## 1. 现状

- `V1__init.sql` 为基线：28 张表 + 默认租户/角色/权限/管理员/BASELINE 场景/7x24 日历种子数据。
- 当前已执行到 **V7**（`aps_local` 于 2026-11-04 验证：`Successfully validated 7 migrations`，schema v7；V7 由 M2-SE-05 引入 `constraint_justification.weight/deduction`）。
- `aps-admin` 在打包时把 `db/migration` 原样放入 classpath（`resources` 配置），迁移文件不得移动目录。

## 2. 号段分配

| 号段 | 归属流 | 说明 |
|---|---|---|
| V2–V3 | 主数据流 | 物料、BOM、资源、日历、客户、供应商 |
| V4–V5 | 工艺订单流 | 工序模板、工艺路线、订单 |
| V6–V7 | 求解流 | V6 求解任务幂等；V7 约束归因补记权重/扣分（M2-SE-05） |
| V8–V9 | 测试部署流 | 测试夹具、索引/约束修正 |

## 3. 规则

1. **禁止修改 `V1__init.sql`**，包括注释、种子数据与 CHECK 约束。
2. **只有 S0 发号**；业务流不得自行新增 `V*.sql`，申请时说明「流 / 目的 / 是否改列 / 是否回填」。
3. M1 优先零迁移；能在现有列/索引上实现的需求不申请迁移。
4. 新迁移命名：`V{n}__{snake_case_description}.sql`，例如 `V2__add_material_safety_stock.sql`。
5. 迁移必须可重复执行环境验证（dev/test/prod 一致），DDL 与种子数据分开；不得包含环境相关路径或密钥。
6. 回滚不写 `U*.sql`；回滚通过前向修复迁移或备份恢复完成。
7. 号段用尽或跨流共享时由 S0 重新分配，不得抢占他流号段。
8. **已应用的迁移文件禁止再改，连注释/空行也不行**。Flyway 校验和覆盖文件全文，任何改动都会让所有连到该库的会话启动失败（`Migration checksum mismatch`）。
   - 想改注释：新增一个迁移，用 `COMMENT ON COLUMN/ TABLE ... IS '...'` 表达，不改历史文件。
   - 想改语义：同样必须新增 `V{n}` 前向迁移；**不得**用 `repair` 掩盖语义变更。
   - 只有**纯注释/格式漂移且已核对 DDL 与库内对象完全一致**时，才允许 `flyway repair` 只更新 `flyway_schema_history` 的 checksum（不 `clean`、不重跑 `migrate`、不改文件）。

## 4. 校验

- Flyway 默认开启校验和（checksum），任何对历史迁移的修改都会导致启动失败。禁止用 `--spring.flyway.enabled=false` 绕过：那只是把问题推给下一个不知情的人。
- 检查命令（只读，不改库）：
  `mvn -o -pl aps-admin org.flywaydb:flyway-maven-plugin:10.22.0:validate -Dflyway.url=jdbc:postgresql://127.0.0.1:55432/aps_local -Dflyway.user=aps -Dflyway.password=*** -Dflyway.locations=filesystem:db/migration`
- 漂移排查：`flyway_schema_history` 的 `checksum` 与当前文件的实际 checksum 逐一比对（可用独立空库跑 `migrate` 得到当前文件的 checksum，再与共享库对照），**不要**假设只有某一个版本漂移。
- 修改表结构后同步更新 [schema.md](./schema.md)。
- 新增枚举值同步更新 [../api/enums.md](../api/enums.md)。
