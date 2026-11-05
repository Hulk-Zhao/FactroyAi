# 约束 DSL 使用说明（开发者文档）

> 实现模块：`aps-solver/solver-dsl`（解析/校验/编译）；接口模块：`aps-web` + `aps-service`（版本、激活、回滚）。
> 契约依据：C19（`docs/api/contract-freeze.md`）与 `docs/api/openapi/system.yaml`。
> 校对口径：2026-11-04 代码。**本文示例均使用 `ConstraintDslParser` 的白名单与约束，已被单元测试覆盖（`ConstraintDslParserTest`）。**

## 1. DSL 是什么

`constraint-dsl` 是一份**受控 YAML**，用于声明 8 条核心软约束及其权重。它只允许：

- 3 个顶层键：`version`、`constraints`，以及兼容别名 `constraint`（单对象，默认开启、可用 `aps.dsl.legacy-singular=false` 关闭）；
- 8 个固定 `ruleCode`（大小写敏感），缺一条、重复一条、或出现未知编码都不合法；
- 每条规则的参数只能命中该规则自己的参数白名单。

**重要现状**：DSL 的业务链路目前是“校验 → 规范化 → 版本化 → 激活”，但 `ScheduleService` **没有读取激活的 DSL 作为求解输入**。求解软约束权重来自 `SolveRequest.parameters.softWeights` 或 `strategyId` 对应的 `strategy.rules`。DSL 与策略规则是两套数据，接线尚待产品/架构确认（见交付报告）。

## 2. 语法

### 2.1 顶层结构

```yaml
version: 1          # 正整数，或 "1.0" 形式；缺省为 1；序列化回写 "N.0"
constraints:        # 必须是数组，且恰好 8 条
  - ...             # 规则项
```

- 顶层出现任何其它键（例如 `stream`、`filters`）→ `42202`。
- `constraints` 与 `constraint` 同时出现 → `42201`。
- 文档最大 256K 字符；嵌套深度 ≤ 32；集合别名 ≤ 50；重复 YAML 键直接拒绝。
- 使用 SnakeYAML `SafeConstructor`：`!!python/...` 等标签一律解析失败。

### 2.2 规则项字段

| 字段 | 必填 | 合法值 | 说明 |
|---|---|---|---|
| `id` | 否 | 非空字符串 | 标识；序列化时写为 `ruleCode` 的小写形式 |
| `ruleCode` / `code` / `rule_code` | 是 | 见 §3 | 三个键名等价，`ruleCode` 优先 |
| `type` | 否 | 仅 `SOFT` | 其它值 → `42204` |
| `weight` | 否 | 0–100 整数 | 缺省取该规则默认权重；`enabled=false` 时必须为 0 |
| `enabled` | 否 | 布尔 | 缺省 true；为 false 时权重强制 0 |
| `params` | 否 | 映射，值只能是字符串/数字/布尔 | 键必须命中该规则白名单 |

未列出的规则键（如 `priority`、`phase`）→ `42202`。

### 2.3 参数白名单

| 参数 | 允许值 | 适用规则 |
|---|---|---|
| `unit` | `MINUTE` / `SECOND` / `HOUR` | `MIN_TARDINESS`、`MIN_SETUP_TIME`、`MIN_MAKESPAN` |
| `groupBy` | `PRODUCT_FAMILY` / `MATERIAL` | `SAME_PRODUCT_CONTINUOUS` |
| `metric` | `LOAD_SECONDS` / `TASK_COUNT` | `BALANCE_LOAD` |
| `maxPriority` | 整数（无枚举值限制） | `PRIORITY_RESPECT` |

参数值若不是白名单枚举（例如 `unit: DAY`）→ `42204`；`maxPriority: high` → `42204`。

## 3. 规则编码与默认权重

| ruleCode | 中文 | 默认权重 | 允许参数 |
|---|---|---:|---|
| `MIN_TARDINESS` | 最小超期 | 20 | `unit` |
| `EDD` | 交期优先 | 15 | — |
| `SPT` | 短任务优先 | 10 | — |
| `SAME_PRODUCT_CONTINUOUS` | 同品连续生产 | 10 | `groupBy` |
| `MIN_SETUP_TIME` | 最小换型时长 | 10 | `unit` |
| `BALANCE_LOAD` | 均衡负载率 | 10 | `metric` |
| `PRIORITY_RESPECT` | 优先级尊重 | 15 | `maxPriority` |
| `MIN_MAKESPAN` | 最小总完工时间 | 10 | `unit` |

强制约束：

- 8 条**必须全部出现**（`42207`）；
- 同一条规则出现两次 → `42206`；
- 8 条权重之和必须**精确等于 100**（`42203`），单条 0–100；
- 默认权重合计正好 100。

## 4. 校验与编译流程

### 4.1 校验（不落库）

`POST /api/v1/constraint-dsls/validate`（权限 `SS_READ`）

```json
{ "yamlText": "version: 1\nconstraints:\n  - ..." }
```

返回 `Result<DslValidationReport>`：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "valid": false,
    "issues": [
      { "code": "42203", "field": "constraints", "message": "8 条规则权重之和必须为 100，实际为 95" }
    ]
  },
  "traceId": "…"
}
```

- `valid=true` 时 `issues=[]`。
- 接口本身 HTTP 200，`code=0`；422xx 只在 `data.issues[].code` 中。
- 报告按发现顺序返回，`primaryCode()` 取第一条。

### 4.2 创建版本（落库）

`POST /api/v1/constraint-dsls`（权限 `SS_WRITE`）

```json
{ "code": "default-strategy", "yamlText": "<YAML>" }
```

服务端步骤：

1. `ConstraintDslParser#load` 重新校验；失败抛 `422/42200`，`message` 形如 `非法 DSL; [42203] constraints: ...; ...`；
2. `ConstraintDslSerializer#serialize` 规范化：`version: "N.0"`、字段固定为 `id/ruleCode/type/weight/enabled/params`；
3. 计算规范化文本的 SHA-256 作 `checksum`；
4. `ConstraintDslCompiler#compile` 生成 `compiled_spec`（每项 `code/type/weight/hard/phase/params`；8 条均为 `type=SOFT`、`hard=false`、`phase=OPTIMIZATION`）；
5. 写入 `constraint_dsl`，`versionNo = 同 code 最大版本 + 1`，`status=DRAFT`；
6. 并发版本号冲突（唯一键）→ `409/40901`。

### 4.3 激活 / 回滚

状态机只有三个值：`DRAFT`、`ACTIVE`、`ROLLBACK`。

- `POST /{id}/activate`（`SS_WRITE`）
  1. 目标已是 `ACTIVE` → 原样返回（幂等）；
  2. 重新校验库内 YAML（防绕过写入校验）；
  3. 当前 ACTIVE → `ROLLBACK`（记录旧版本）；
  4. 目标 → `ACTIVE`，写 `effective_from`（UTC）；
  5. 任一步失败 → 恢复旧 ACTIVE，返回 `409/40901`，message 带上旧/新版本号。
- `POST /{id}/rollback`（`SS_WRITE`）
  1. 目标已是 ACTIVE → `409/40901`；
  2. 当前 ACTIVE → `ROLLBACK`；
  3. 目标 → `ACTIVE`，写 `rollback_of=当前版本 id` 与 `effective_from`；
  4. 失败 → 恢复原 ACTIVE，返回 `409/40901`。
- 目标不存在 → `404/40400`；`id` 为空 → `400/40000`。

> 激活**不是**整段数据库事务：先停旧、再启新、失败补偿。并发修改通过 `updateById` 返回 0 检测并报 `40901`。

## 5. 校验错误码（DSL 内 422xx）

| code | 常量 | 触发场景 |
|---:|---|---|
| 42201 | `SYNTAX_ERROR` | 空文本、YAML 语法错误、根节点非映射、`constraints` 非数组、`constraint` 与 `constraints` 同时出现、重复 YAML 键、`version` 非法 |
| 42202 | `UNKNOWN_RULE` | 未知顶层键（如 `stream`）、未知规则键、未知 `ruleCode` |
| 42203 | `INVALID_WEIGHT` | 单条权重越界、`enabled=false` 却给正权重、8 条合计 ≠ 100 |
| 42204 | `INVALID_PARAM` | `type≠SOFT`、`id` 空、`enabled` 非布尔、参数键不在白名单、参数值非标量或不在枚举、`maxPriority` 非整数 |
| 42205 | `LIMIT_EXCEEDED` | 文本 > 256K 字符（深度/别名超限由 SnakeYAML 报 `42201`） |
| 42206 | `DUPLICATE_RULE` | 同一 `ruleCode` 重复声明 |
| 42207 | `MISSING_RULE` | 缺少规则编码、规则数量 ≠ 8、缺少 `constraints` |

注意：`42201` 与业务错误码 `ADJUSTMENT_INFEASIBLE` 数值相同，但前者只出现在 `issues[].code`（字符串），后者是外壳 `code`（整数）。调用方应按响应层级区分。

## 6. 可运行示例

### 6.1 示例 A：C19 冻结字段形状（通过）

```yaml
version: "1.0"
constraints:
  - id: min_tardiness
    ruleCode: MIN_TARDINESS
    type: SOFT
    weight: 20
    enabled: true
    params: {}
  - id: edd
    ruleCode: EDD
    type: SOFT
    weight: 15
    enabled: true
    params: {}
  - id: spt
    ruleCode: SPT
    type: SOFT
    weight: 10
    enabled: true
    params: {}
  - id: same_product_continuous
    ruleCode: SAME_PRODUCT_CONTINUOUS
    type: SOFT
    weight: 10
    enabled: true
    params: {}
  - id: min_setup_time
    ruleCode: MIN_SETUP_TIME
    type: SOFT
    weight: 10
    enabled: true
    params: {}
  - id: balance_load
    ruleCode: BALANCE_LOAD
    type: SOFT
    weight: 10
    enabled: true
    params: {}
  - id: priority_respect
    ruleCode: PRIORITY_RESPECT
    type: SOFT
    weight: 15
    enabled: true
    params: {}
  - id: min_makespan
    ruleCode: MIN_MAKESPAN
    type: SOFT
    weight: 10
    enabled: true
    params: {}
```

### 6.2 示例 B：带白名单参数 + 关闭两条规则（通过）

`EDD`、`MIN_MAKESPAN` 关闭（权重 0），其余权重上调，合计仍为 100。

```yaml
version: 1
constraints:
  - code: MIN_TARDINESS
    weight: 30
    params:
      unit: HOUR
  - code: EDD
    enabled: false
    weight: 0
  - code: SPT
    weight: 15
  - code: SAME_PRODUCT_CONTINUOUS
    weight: 10
    params:
      groupBy: MATERIAL
  - code: MIN_SETUP_TIME
    weight: 15
    params:
      unit: SECOND
  - code: BALANCE_LOAD
    weight: 10
    params:
      metric: TASK_COUNT
  - code: PRIORITY_RESPECT
    weight: 20
    params:
      maxPriority: 3
  - code: MIN_MAKESPAN
    enabled: false
    weight: 0
```

### 6.3 示例 C：非法 DSL（会被拒绝，用于验证错误报告）

```yaml
version: 1
constraints:
  - code: MIN_TARDINESS
    weight: 20
  - code: EDD
    weight: 15
  - code: SPT
    weight: 10
  - code: SAME_PRODUCT_CONTINUOUS
    weight: 10
  - code: MIN_SETUP_TIME
    weight: 10
  - code: BALANCE_LOAD
    weight: 10
  - code: PRIORITY_RESPECT
    weight: 15
stream:
  - filter: system
```

预期 `issues` 至少包含：`42202`（顶层 `stream`）、`42207`（缺少 `MIN_MAKESPAN`）、`42203`（合计 70）。

### 6.4 curl

```bash
BASE=http://localhost:8080/api/v1
TOKEN="<登录返回的 accessToken>"

# 校验（把 YAML 转义进 JSON；实际可用 jq/python 生成）
curl -s -X POST "$BASE/constraint-dsls/validate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"yamlText":"version: 1\nconstraints:\n  - code: MIN_TARDINESS\n    weight: 20\n  - code: EDD\n    weight: 15\n  - code: SPT\n    weight: 10\n  - code: SAME_PRODUCT_CONTINUOUS\n    weight: 10\n  - code: MIN_SETUP_TIME\n    weight: 10\n  - code: BALANCE_LOAD\n    weight: 10\n  - code: PRIORITY_RESPECT\n    weight: 15\n  - code: MIN_MAKESPAN\n    weight: 10\n"}'

# 创建版本
curl -s -X POST "$BASE/constraint-dsls" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: dsl-create-default-001" \
  -d '{"code":"default-strategy","yamlText":"<同上 YAML>"}'

# 激活
curl -s -X POST "$BASE/constraint-dsls/<id>/activate" \
  -H "Authorization: Bearer $TOKEN"

# 回滚到历史版本
curl -s -X POST "$BASE/constraint-dsls/<历史 id>/rollback" \
  -H "Authorization: Bearer $TOKEN"
```

## 7. 使用建议

1. 新规则先走 `validate`，确认 `valid=true` 再 `create`；`create` 会把 YAML 规范化并回写，以返回的 `yamlText` 为准。
2. 版本号由服务端生成，不要自己计算；`checksum` 可作为发布指纹。
3. 生产变更用 `activate`，回退用 `rollback`；两者都会把旧 ACTIVE 置 `ROLLBACK`，不要手工改状态。
4. 需要让 DSL 权重真正影响求解时，当前应改为传 `strategyId` 或 `parameters.softWeights`；DSL 接线完成前不要把 DSL 当作求解生效依据。
5. `enabled=false` 与 `weight: 0` 等价；如果要“关闭但仍占位”，两者都写更直观。
