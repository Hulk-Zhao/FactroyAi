# 编码规范（智能APS）

本规范基于《阿里巴巴 Java 开发手册》与团队工程实践，覆盖后端、前端与提交前检查。所有规则以仓库内配置为准，评审时如与本规范冲突以本规范为准。

## 1. 通用要求

- 源文件统一 UTF-8、LF 换行、文件末尾保留一个空行，缩进以 `.editorconfig` 为准（Java 4 空格，前端 2 空格）。
- 禁止提交编译产物、IDE 配置、密钥与本地数据库文件。
- 所有新增代码必须通过 `mvn verify` 与 `npm run lint`，不允许通过关闭规则来规避检查。

## 2. 后端（Java 21 / Spring Boot 3.3）

### 2.1 命名

| 元素 | 规则 | 示例 |
|---|---|---|
| 包 | 全小写，点分隔 | `com.hengce.aps.service.schedule` |
| 类 / 接口 / 枚举 / Record | 大驼峰 | `ProductionOrderService` |
| 方法 / 变量 | 小驼峰 | `listOrders`、`orderNo` |
| 常量 | 全大写 + 下划线 | `API_PREFIX`、`DEFAULT_SOLVER_ENGINE` |
| 布尔 | `is` / `has` / 语义名 | `deleted`、`isPanel` |
| 数据库字段 | snake_case，见 `docs/db/schema.md` | `order_no`、`due_date` |

- 业务编码统一使用 `code` / `{实体}_no`；对外 API 使用业务编码，内部主键使用 `id`。
- Java 属性使用小驼峰，与数据库 snake_case 通过 ORM 映射，不在 API 中直接暴露表结构细节。

### 2.2 分层与依赖方向

```
aps-web → aps-service → aps-domain / solver-api / aps-infrastructure
aps-admin（组合根）→ aps-web / solver-timefold / solver-ortools
solver-timefold / solver-ortools → solver-api（只实现抽象）
```

- `aps-web` 只做参数接收、响应封装与异常转换，不写业务规则。
- `aps-service` 编排领域模型与仓储，不依赖具体求解引擎；引擎通过 `SolverAdapter` 注入。
- `aps-infrastructure` 实现仓储、缓存、消息与外部集成，不反向依赖 `aps-service`。
- **Timefold / OR-Tools 类型只允许出现在对应 solver 模块**，禁止泄漏到 `aps-service`、`aps-web`。
- 模块间只通过 `pom.xml` 声明的依赖通信，禁止跨模块引用未声明的类。

### 2.3 注释与 Javadoc

- 所有 public 类、接口、枚举、Record 必须有 Javadoc，说明职责与关键约束。
- public 方法需说明业务含义、参数与返回值；重写方法可使用 `{@inheritDoc}`。
- 复杂算法、状态机分支、非直观的兼容处理必须写行内注释说明「为什么」，而不是复述代码。
- 禁止保留过期注释、注释掉的代码与 `TODO` 无负责人信息。

### 2.4 异常与响应

- 可预期业务错误抛 `BusinessException(ErrorCode, message)`，由 `RestExceptionHandler` 统一转换。
- 禁止捕获异常后不处理、不回滚、不记录；兜底异常必须记录 error 日志。
- REST 统一返回 `ApiResponse<T>`，成功 `code=0`；禁止直接返回裸实体或 `Map`。
- 禁止在 Controller 中 `try/catch` 业务异常后自行拼装响应。

### 2.5 日志

- 使用 SLF4J（`LoggerFactory.getLogger`），禁止 `System.out` / `System.err` / `printStackTrace`。
- 日志使用占位符：`LOGGER.info("orderNo={}, qty={}", orderNo, qty)`，禁止字符串拼接。
- 记录异常时传入异常对象：`LOGGER.error("...", exception)`。
- 禁止打印完整敏感数据（密码、密钥、完整报文）。
- 结构化日志字段、traceId 透传与敏感字段脱敏规则见 [observability.md](observability.md)；新增日志字段须按该文档第 3 节确认脱敏方式。

### 2.6 其他

- 时间：日期用 `LocalDate`，时间点用 `LocalDateTime`（数据库 TIMESTAMPTZ 由基础设施层转换），禁止 `java.util.Date`。
- 金额/数量使用 `BigDecimal`，禁止 `double` 参与精度敏感计算。
- 集合返回使用 `List.of()` / `Map.of()` 或不可变视图，避免返回内部可变集合。
- 空值判断先判空后使用；对外部输入在边界处校验，内部调用信任契约。
- 单方法建议不超过 80 行，圈复杂度不超过 12；超限需拆分。

## 3. 前端（Vue 3 / TypeScript）

- 组件文件名使用 PascalCase（`OrdersView.vue`），组合式函数使用 `useXxx`。
- 统一使用 `<script setup lang="ts">`，禁止 `any`；接口类型放 `src/types`。
- API 调用集中在 `src/api`，组件不直接使用 axios；统一通过 `http` 实例。
- 状态管理使用 Pinia，跨组件共享状态才放入 store，页面局部状态留在组件内。
- 样式优先使用 Element Plus 组件与 scoped CSS；全局样式仅放 `src/styles`。
- 提交前必须通过 `npm run type-check`、`npm run lint`、`npm run format:check`。

## 4. 检查工具与执行

| 范围 | 工具 | 配置文件 | 命令 |
|---|---|---|---|
| 后端命名/导入/花括号/行长 | Checkstyle 14 | `config/checkstyle/checkstyle.xml` | `mvn verify` |
| 后端缺陷/复杂度 | PMD 7 | `config/pmd/pmd-ruleset.xml` | `mvn verify` |
| 前端语法与 Vue 规则 | ESLint 10 + typescript-eslint | `aps-frontend/eslint.config.js` | `npm run lint` |
| 前端格式 | Prettier 3 | `aps-frontend/.prettierrc.json` | `npm run format:check` |
| 编辑器基础格式 | EditorConfig | `.editorconfig` | 编辑器自动执行 |

- Checkstyle 与 PMD 绑定在 Maven `verify` 阶段，CI 使用 `mvn clean verify` 强制拦截。
- 前端 `lint` 使用 `--max-warnings 0`，任何 warning 视为失败。
- 确有必要的例外需在 PR 中说明原因，并由架构组评审；禁止个人本地关闭检查后合入。

## 5. 评审要点

1. 模块依赖方向是否被破坏，求解引擎类型是否泄漏。
2. 新增 API 是否遵循 `/api/v1` 前缀、统一响应与错误码。
3. 数据库字段与枚举取值是否与 `docs/db/schema.md` 一致，禁止自行改表结构。
4. 是否补齐 Javadoc、异常处理、日志与边界校验。
5. 是否引入不必要的依赖或重复工具类。
