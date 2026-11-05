# OpenAPI 静态契约（P0-08）

本目录按域拆分 OpenAPI 3.0.3 文档，避免单文件长期合并冲突。运行时文档由 SpringDoc 从代码注解生成，
两者应保持一致；冲突时以静态 YAML + [conventions.md](../conventions.md) 为评审基线。

| 文件 | 分组 | 运行时地址 |
|---|---|---|
| [materials.yaml](materials.yaml) | materials | `/v3/api-docs/materials` |
| [orders.yaml](orders.yaml) | orders | `/v3/api-docs/orders` |
| [solve.yaml](solve.yaml) | solve | `/v3/api-docs/solve` |
| [gantt.yaml](gantt.yaml) | gantt | `/v3/api-docs/gantt` |
| [system.yaml](system.yaml) | system | `/v3/api-docs/system` |

## 状态标记

每个操作带 `x-implementation-status`：

- `implemented`：已实现并有单测/冒烟覆盖（如 `GET /health`、`GET /orders`、`POST /solve-tasks`）；
- `placeholder`：仅冻结契约，调用返回 `HTTP 501 + code=50100`，**不可当作可用功能**。

Swagger UI 中对应描述以 `状态：占位` 开头，避免误用。

## 使用方式

```bash
# 聚合文档
curl http://localhost:8080/v3/api-docs
# 分组文档
curl http://localhost:8080/v3/api-docs/materials
# UI
open http://localhost:8080/swagger-ui.html
```

## 维护规则

1. 新增/修改端点：先改 YAML 与 `conventions.md`，再改 Controller 注解，最后跑 `mvn verify`。
2. 路径、方法、请求/响应字段、错误码四类信息必须齐全。
3. 字段命名以 `docs/db/schema.md` 的 snake_case 映射为 camelCase，不直接暴露表结构。
4. 占位端点实现后，把 `x-implementation-status` 改为 `implemented` 并补充测试。

## 校验

仓库不强制引入 Node 依赖；如需本地校验 YAML，可使用：

```bash
npx --yes @redocly/cli lint docs/api/openapi/materials.yaml
```
