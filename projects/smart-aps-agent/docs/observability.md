# 可观测性与日志约定（P0-07）

> 适用版本：v0.1 MVP。配套实现：`aps-common` traceId 上下文、`aps-web` 过滤器/异常处理、
> `aps-admin/src/main/resources/logback-spring.xml`。

## 1. 日志形态

| 环境 | Appender | 形态 | 用途 |
|---|---|---|---|
| 默认 / dev | `CONSOLE` | 单行文本，含 `traceId`、`tenantId`、请求耗时 | 本地开发、CI 冒烟 |
| prod | `JSON` | Logstash JSON（stdout） | Loki / ELK 采集，字段可检索 |

容器统一输出 stdout，由平台侧采集；不写本地滚动文件，避免多副本挂载冲突。

### 1.1 字段约定

| 字段 | 来源 | 说明 |
|---|---|---|
| `timestamp` | Logback | ISO-8601 带时区 |
| `level` | Logback | 日志级别 |
| `logger` / `thread` | Logback | 日志器与线程名 |
| `message` | 业务日志 | 使用占位符，不拼接字符串 |
| `traceId` | MDC | 单次请求链路标识；异步线程需透传 |
| `tenantId` | MDC | 租户标识，MVP 缺省 `00000000-0000-0000-0000-000000000001` |
| `userId` | MDC | 登录用户；接入认证后由安全过滤器写入 |
| `durationMs` | MDC | 访问日志耗时，单位毫秒 |
| `service` / `env` | 自定义字段 | 服务名与运行环境 |

## 2. traceId 规则

- 入口：`TraceIdFilter` 读取请求头 `X-Trace-Id`；仅接受 `[A-Za-z0-9._-]{8,64}`，非法或缺失时生成
  32 位无连字符 UUID。
- 出口：响应头 `X-Trace-Id` 始终回写，值为本次请求实际使用的 traceId。
- 上下文：同时写入 `TraceContext`（业务代码取用）与 SLF4J MDC（日志取用），请求结束清理。
- 异步：`AsyncTraceConfiguration` 注册 `TaskDecorator`，`@Async` 任务自动继承 MDC；
  非 Spring 托管的求解回调须用 `TraceContext.wrap(...)` 包装。
- 跨服务：下游调用须透传 `X-Trace-Id`，保持全链路一致。

## 3. 敏感信息脱敏

日志与审计记录中禁止出现以下原始值，必须脱敏后输出：

| 类别 | 字段示例 | 脱敏规则 |
|---|---|---|
| 凭证 | `password`、`token`、`apiKey`、`secret`、`authorization` | 完全不记录；如必须定位，只记录是否为空/长度 |
| 个人信息 | 手机号、身份证、邮箱 | 手机号保留前 3 后 4；身份证保留前 6 后 4；邮箱保留首字符与域名 |
| 报文 | 完整请求/响应体、BOM/工艺全量 JSON | 只记录关键业务编码与字段数量，禁止整包打印 |
| 数据库 | SQL 参数、连接串密码 | 参数用占位符；连接串密码来自环境变量，不落日志 |

实施要求：

1. 业务日志使用业务编码（`orderNo`、`materialCode`）定位，不打印对象 `toString()`。
2. 异常日志记录异常对象（`LOGGER.error("...", exception)`），但响应体只返回 `ErrorCode` 默认信息。
3. 审计前后值写入数据库前按上表脱敏（`audit_log.before_value/after_value`）。
4. 新增日志字段若涉及用户输入，评审时须确认脱敏方式。

## 4. 与告警/追踪的衔接

- MVP 先落地 traceId + 结构化日志；OpenTelemetry + Jaeger 属二期（PRD 5.5）。
- 后续接入 Prometheus 指标时，沿用本文件的服务名、环境字段，避免多套标签体系。
