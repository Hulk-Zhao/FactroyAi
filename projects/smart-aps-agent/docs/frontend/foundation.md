# 前端基座约定（M1-FE-01）

> 适用范围：`aps-frontend/**`。本文件是 FE2/FE5/FE6 并行开发的路由、菜单、API、Mock 与命名约定。
> API 契约以 `docs/api/conventions.md`、`docs/api/openapi/*.yaml` 为准；枚举以 `docs/db/schema.md` 4.1 节 CHECK 取值为准。

## 1. 目录结构

```text
aps-frontend/src/
  api/                   # 按域拆分的接口文件 + http.ts
  components/common/     # 分页表格、状态页、状态标签
  components/gantt/      # 甘特图适配器接口占位（FE-06 PoC）
  constants/             # 错误码、枚举与标签映射
  layouts/               # AppLayout、面包屑、路由分组容器
  mocks/                 # 契约 Mock（默认关闭）
  router/
    index.ts             # 冻结的根路由，禁止页面直接修改
    modules/             # 各业务模块路由；index.ts 为唯一追加点
    types.ts             # RouteMeta / RouterModule 类型
  stores/                # Pinia（app、trace）
  types/                 # 按域类型；api.ts 仅 re-export
  utils/                 # 表单校验等通用工具
  views/                 # 页面
```

## 2. 路由与菜单

### 2.1 注册机制

根文件 [router/index.ts](../../aps-frontend/src/router/index.ts) 只负责创建 Layout 与合并模块，**页面开发不要修改它**。

新增页面步骤：

1. 在 `src/router/modules/` 新建 `<module>.ts`，默认导出一个 `RouterModule`：

```ts
import type { RouterModule } from '../types'

export const demoModule: RouterModule = {
  key: 'demo',
  label: '示例模块',
  menuPath: '/demo/list',
  order: 70,
  menu: true,
  routes: [
    {
      path: 'demo',
      component: () => import('@/views/demo/DemoListView.vue'),
      meta: { title: '示例模块' }
    }
  ]
}
```

2. 在 [modules/index.ts](../../aps-frontend/src/router/modules/index.ts) 追加 import 与数组项，这是唯一需要改动的公共文件。
3. 若已有模块需要加子页面（如基础数据），在**该模块文件内部**追加 `routes` 子项，不改根文件。

### 2.2 已预留分组

| 路径 | 菜单 | 归属 | 当前状态 |
| --- | --- | --- | --- |
| `/` | 总览 | — | 已实现 |
| `/master-data/materials`、`/boms`、`/resources`、`/calendars` | 基础数据 | M1-FE-02/03 | 占位页 |
| `/process/routings` | 工艺路线 | M1-FE-04 | 占位页 |
| `/orders` | 订单管理 | M1-FE-05 | 列表骨架 |
| `/solve-tasks` | 排产求解 | — | 骨架 |
| `/gantt` | 甘特图 | M1-FE-06 | 占位页 + 适配器接口 |

分组路由使用 `src/layouts/RouteGroup.vue` 作为容器；子路由的 `meta.title` 会进入面包屑。

### 2.3 约定

- 路由 `name` 使用 kebab-case，全局唯一（如 `master-data-materials`）。
- 面包屑由 `route.matched` 自动生成，`meta.title` 必填；不希望出现在面包屑的路由设置 `meta.hidden = true`。
- 菜单由 `menuModules` 自动生成，顺序取 `RouterModule.order`；`menu: false` 的模块不出现在菜单。
- 不要把业务状态判断放进路由守卫；MVP 未启用登录态拦截。

## 3. API 层

### 3.1 文件边界

- 所有接口实现写 `src/api/<domain>.ts`：`materials`、`orders`、`solve`、`gantt`、`system`。
- `src/api/index.ts`、`src/types/api.ts` **只允许 re-export**，禁止追加实现或类型定义。
- 类型写 `src/types/<domain>.ts`，公共类型在 `src/types/common.ts`。

### 3.2 统一返回与错误

`src/api/http.ts` 创建唯一 Axios 实例：

- 响应为 `{ code, message, data, traceId }` 时自动解包，接口函数直接返回 `data`。
- `code != 0` 抛 `ApiError`（含 `code`、`message`、`traceId`、`httpStatus`），并由拦截器统一 `ElMessage.error`。
- 4xxxx 优先展示服务端 `message`；5xxxx 仅展示错误码默认文案，完整信息写 `console.error`。
- 页面只需 `catch` 后设置本地错误态，不要重复弹全局提示。

```ts
try {
  rows.value = await listMaterials({ page, size, type })
} catch (exception) {
  error.value = exception instanceof Error ? exception.message : '加载失败'
}
```

### 3.3 traceId

- 请求自动携带 `X-Trace-Id`（未提供时生成 32 位十六进制）。
- 响应信封或响应头的 traceId 会写入 `useTraceStore()`，顶栏展示最近 12 位并支持复制。
- 错误提示末尾附带 traceId 前 8 位，便于对照后端日志。

### 3.4 分页与排序

- 入参统一 `PageQuery = { page?, size?, sort? }`，`page` 从 1 开始，`size` 默认 20、上限 200。
- `withPageQuery()` 负责补齐分页并合并筛选条件；`paramsSerializer` 将数组序列化为逗号分隔。
- 排序格式：单字段 `dueDate,asc`；多字段用数组，最终序列化为 `field,asc,field2,desc`。
- 出参统一 `PageResult<T> = { items, page, size, total, totalPages }`。
- 默认场景 `sandboxId` 取 `src/constants/defaults.ts` 的 `DEFAULT_SANDBOX_ID`（`00000000-0000-0000-0000-000000000401`，contract-freeze C5）；甘特与求解入参为空时由后端兜底，前端可用该常量显式传值。

### 3.5 写接口与幂等

写接口可传 `idempotencyKey`，统一通过 `idempotencyHeaders(key)` 注入 `Idempotency-Key`；客户端可用 `createIdempotencyKey()` 生成。MVP 阶段不强制，后端启用后各页面直接传参即可。

## 4. Mock

- 开关：`VITE_ENABLE_MOCK`，**默认 `false`**；本地需要离线联调时在 `.env.development.local` 设 `true`。
- 延迟：`VITE_MOCK_DELAY`，默认 300ms。
- 实现：`src/mocks/index.ts` 在 `main.ts` 启动时把 Axios adapter 替换为契约 Mock；请求仍经过统一拦截器，因此仍会走 trace、错误提示与解包逻辑。
- 数据：`src/mocks/data.ts` 按 OpenAPI 字段生成；`handlers.ts` 覆盖物料、BOM、资源、日历、工艺路线、MRP、订单、策略、沙箱、求解、甘特、用户、审计、配置等路径。
- 未覆盖路径返回业务错误 `40400` 或 `50100`，便于尽早发现契约缺口。
- 新增 Mock：在 `handlers.ts` 按「method + path」追加分支，并在 `data.ts` 增加符合 OpenAPI 的样例数据；不要修改真实 API 层。

`.env.development` / `.env.production` 模板均包含 `VITE_API_BASE_URL=/api/v1`，生产由 nginx 转发。

## 5. 通用组件

| 组件 | 用途 |
| --- | --- |
| `DataTable` | el-table + 分页 + 工具栏 slot + 空态/错误态；`v-model:page`、`v-model:size`、`@change`、`@retry` |
| `PageState` | `loading` / `empty` / `error` 三态，错误态带重试 |
| `StatusTag` | 枚举值 → 中文标签 + 颜色，必须在 `src/constants/enums.ts` 注册 |

表单校验统一使用 `src/utils/form-rules.ts`：`required`、`maxLength`、`businessCode`、`positiveNumber`、`nonNegativeNumber`、`isoDate`、`isoUtcDateTime`。提示文案、触发时机保持一致，禁止页面内自造校验正则。

## 6. 枚举

- 唯一来源：`docs/db/schema.md` 4.1 节 CHECK 取值；`src/constants/enums.ts` 只做中文展示映射。
- 不允许在页面里硬编码状态中文或新增枚举值；缺枚举时先补 `schema.md` 与后端契约，再补前端映射。
- 已知契约差异：求解提交 `engine` 对外为小写 `timefold | ortools | rule_based`；库表 `solve_task.engine` CHECK 为 `TIMEFOLD | ORTOOLS | RULE_BASED`。API 入参使用小写值，详情展示用 schema 大写值。
- 已按 `docs/api/contract-freeze.md` 对齐：路径 ID（materials/orders/resources 用 code，boms/routings/calendars/tasks/solve-tasks 用 UUID 或 bigint）、默认 sandboxId、甘特 `resourceType` 与 `setupStartTime/setupEndTime` 非空约束。若后续 freeze 与 yaml 冲突，以 freeze 为准。

## 7. 命名约定

- 文件：组件 PascalCase，其余 kebab-case；域文件固定 `materials.ts` / `orders.ts` / `solve.ts` / `gantt.ts` / `system.ts`。
- API 函数：列表 `listXxx`、详情 `getXxx`、创建 `createXxx`、更新 `updateXxx`、动作 `confirmXxx` / `freezeXxx` / `publishXxx`。
- 类型：请求 `XxxCreateRequest` / `XxxUpdateRequest`，查询 `XxxQuery`。
- 变量/函数 camelCase，常量 UPPER_SNAKE_CASE，枚举字面量保持后端大写。
- 组件 props/emits 使用 kebab-case（模板）与 camelCase（脚本）。

## 8. FE2 接入清单

- [ ] 只改自己模块的路由文件与 `router/modules/index.ts`
- [ ] 接口写 `src/api/materials.ts`，类型写 `src/types/materials.ts`
- [ ] 列表页使用 `DataTable`，状态列使用 `StatusTag`
- [ ] 表单使用 `form-rules.ts`，错误交给全局拦截器
- [ ] 需要离线数据时开启 `VITE_ENABLE_MOCK=true`，并补 `handlers.ts` 路径
- [ ] 不修改 `router/index.ts`、`api/index.ts`、`types/api.ts`、后端与部署文件

## 9. 甘特图占位

`src/components/gantt/index.ts` 定义 `GanttAdapter`（`load` / `adjust`）与 `GanttDataset`、`GanttRowModel`、`GanttBarModel`。
FE-06 PoC 实现适配器后再接 `/gantt` 路由；本任务不创建实际甘特页面。

## 10. 开发代理与配置生效排障

- `/api` 代理指向后端 `127.0.0.1:18085`；`/ws` 同目标且开启 `ws: true`，用于求解进度 STOMP。
- 开发态写请求鉴权：`vite.config.ts` 读取 `APS_DEV_API_KEY`（值只放 `.env.development.local`，已被 `.gitignore` 的 `*.local` 覆盖），非空时仅由 Vite 代理注入 `X-API-Key`；未设置则不注入。生产构建无代理，不会携带该头，生产鉴权由网关/JWT 负责。
- **踩坑记录（2026-11-04）**：修改 `vite.config.ts` 或 `.env*.local` 后，运行中的 Vite **不一定**自动重载。实测 PID 28776 于 03:23 启动、配置 05:43 更新，进程未重启，写请求仍为 401，一度被误判为“注入逻辑失效”。判断方式：`Get-Process -Id <pid> | Select StartTime` 与配置 mtime 对比。
- 排障顺序：先 touch `vite.config.ts`（仅改 mtime、不改内容），等 5~6 秒，再测一条写请求；确认代理生效后，再去怀疑密钥或拦截器。不要先改注入逻辑，也不要为此重启共享后端。
- 验证 `/ws` 代理是否真的转发，不要用“两边都 404”当证据（Vite 对未匹配路径返回 200 的 app shell，后端对未知端点返回带 `x-trace-id` 的 JSON/文本错误，两者可以区分）。可区分做法：直连与经代理各取一次响应，比对 `x-trace-id` 响应头或响应体是否来自后端（如后端返回 `400 Can "Upgrade" only to "WebSocket".`，含 trace id）。
- 后端实例运行期间不要动 `target/`（打包会覆盖为瘦包，`/health` 仍可能返回 200，重启才暴露）。
