# 演示前检查清单（30 分钟剧本配套）

> 目标：客户到场前 10 分钟，把环境、数据、恢复手段三项确认完。
> 基线数据快照时间：2026-11-05 03:00（Asia/Shanghai），来源：18085 只读接口实测。
> 原则：**只读探测 18085，不重启、不写库、不碰 `aps_local_2`。**

## 一、环境检查（P0，逐项打勾）

### 1. 后端 18085
- [ ] 端口在监听：`Get-NetTCPConnection -State Listen -LocalPort 18085`
- [ ] 服务可用（返回 200 即通过）：
```powershell
curl.exe -s -o NUL -w "%{http_code}" `
  -H "X-API-Key: $env:APS_DEMO_READ_KEY" `
  -H "X-Trace-Id: demo-check-health" `
  "http://127.0.0.1:18085/api/v1/health"
```
- [ ] 订单接口可读（返回 200）：
```powershell
curl.exe -s -o NUL -w "%{http_code}" `
  -H "X-API-Key: $env:APS_DEMO_READ_KEY" `
  -H "X-Trace-Id: demo-check-orders" `
  "http://127.0.0.1:18085/api/v1/orders?page=1&size=1"
```
- [ ] 启动参数确认（不要执行重启，只核对）：
  - `--spring.profiles.active=dev`
  - `--server.port=18085`
  - `--aps.security.mode=dev`
  - `--aps.security.dev.api-key-enabled=true`
  - `--aps.security.api-keys.read=<演示读密钥，经环境变量注入>`
  - `--aps.security.api-keys.write=<演示写密钥，经环境变量注入>`
- [ ] 如果 18085 不可用：**不要现场重启**，按“应急处理”切到备用实例或直接说明环境异常；重启会清掉正在跑的任务。

### 2. 数据库（P0）
- [ ] 后端连的是 `aps_local`，不是 `aps_local_2`。
  - 启动环境变量应为 `DB_HOST=127.0.0.1`、`DB_PORT=55432`、`DB_NAME=aps_local`。
  - 只读核对（只连 `aps_local`，不要连 `aps_local_2`）：
```powershell
$env:PGPASSWORD='aps_dev_pwd'
D:\tools\_aps-toolchain\pgsql\bin\psql.exe `
  -h 127.0.0.1 -p 55432 -U aps -d aps_local `
  -c "select current_database() as db, now() as db_time;"
```
- [ ] 若发现 `DB_NAME=aps_local_2`：立即停止演示写入（不要建单、不要求解、不要冻结），通知环境负责人；今天所有实测数据都不适用于 `aps_local_2`。

### 3. 前端 5173（P0，当前环境必须先修一项）
- [ ] 前端在跑：`http://localhost:5173/` 可打开。
- [ ] **开发代理密钥与后端写密钥一致**。当前 [`.env.development.local`](../../aps-frontend/.env.development.local) 写的是 `APS_DEV_API_KEY=dev-write-key`，而后端只认启动参数注入的演示写密钥（经环境变量提供，不写入仓库）；Vite 会把无效 Key 注入每个 `/api` 请求，后端在 dev 模式下会**直接 401，连 JWT 都不再回退**。
  - 修法（只改前端本地环境文件，改完只重启 Vite，不动 18085）：
```dotenv
# aps-frontend/.env.development.local
APS_DEV_API_KEY=<从环境变量读取，不写入仓库>
```
  - 或在演示时不用 Vite 代理，改用已构建静态包 + 网关/JWT。
- [ ] 验证写密钥可读（应返回 `200`）：
```powershell
curl.exe -s -o NUL -w "%{http_code}" `
  -H "X-API-Key: $env:APS_DEMO_WRITE_KEY" `
  -H "X-Trace-Id: demo-check-writekey" `
  "http://127.0.0.1:18085/api/v1/orders?page=1&size=1"
```
- [ ] 验证无效 Key 会被拒（应返回 `401`，说明过滤器正常，不是放行）：
```powershell
curl.exe -s -o NUL -w "%{http_code}" `
  -H "X-API-Key: dev-write-key" `
  -H "X-Trace-Id: demo-check-badkey" `
  "http://127.0.0.1:18085/api/v1/orders?page=1&size=1"
```
- [ ] 修完 Key 后，登录任意页面点一个查询，浏览器 Network 里 `/api/v1/orders` 必须是 200，不能是 401。

### 4. Mock 开关（P0）
- [ ] `aps-frontend/.env.development` 中 `VITE_ENABLE_MOCK=false`。
- [ ] 登录页/页脚没有 “Mock 模式” 提示。
- [ ] 甘特页必须带 `?source=api`：
  - `http://localhost:5173/gantt?source=api`
  - `http://localhost:5173/gantt-orders?source=api`
  - 右上角数据源标签显示 `API（/gantt/...）`，不是 `Mock`。
- [ ] 若显示 Mock：只改 URL，不要在客户面前改代码或重启后端。

### 5. 登录与浏览器（P1）
- [ ] 账号：`admin`；初始口令由 `APS_ADMIN_INIT_PASSWORD` 环境变量注入（仓库不记录明文；若现场重置过，以现场为准）。
- [ ] **最多试 5 次**：连续失败 5 次会锁 15 分钟，现场没有第二个管理员账号可用。
- [ ] JWT 有效期 8 小时；上午演示、下午复用一般够用。
- [ ] 浏览器 1440×900 以上，缩放 90%；提前登录，不要在客户面前首次登录。
- [ ] 时区 `Asia/Shanghai`；种子数据日期全部相对运行当天生成，**演示当天必须重灌**（见下方“新种子库专项检查”第一条），不要沿用上一轮数据。

### 6. AI 解读（LLM，可选，但要在开场前定稿口径）

> AI 演示不占用 18085：由 [scripts/run-demo-llm.ps1](../../scripts/run-demo-llm.ps1) 在**独立端口**（默认 18099）启动一个临时实例；18085 只读，不重启、不改环境变量。

- [ ] 已决定今天**演 AI 还是讲“未启用”**：两种口径都写在 [demo-script.md](./demo-script.md) 第 6.5 段，选定后不再现场切换。
- [ ] 启动实例（Ollama 示例；DeepSeek 需先设 `APS_AGENT_LLM_BASE_URL` / `APS_AGENT_LLM_API_KEY` / `APS_AGENT_LLM_MODEL`）：
```powershell
$env:APS_AGENT_LLM_BASE_URL='http://127.0.0.1:11434'
$env:APS_AGENT_LLM_MODEL='qwen3.5:9b'
.\scripts\run-demo-llm.ps1 -Provider ollama
```
- [ ] 启动输出逐项核对：`provider` / `model` / `API key 已配置：是|否` / `超时(秒)`；**确认输出里没有任何密钥值**。
- [ ] `explain` 返回 `source` 符合预期：
  - AI 开着：`source=LLM`、`llm.configured=true`、`fallbackReason` 为空；
  - AI 没开：`source=EVIDENCE`、`fallbackReason=NO_PROVIDER_CONFIGURED`。
```powershell
$h=@{'X-API-Key'=$env:APS_SECURITY_API_KEYS_READ}
$r=Invoke-WebRequest -Uri "http://127.0.0.1:18099/api/v1/agent/explain/46" -Headers $h -UseBasicParsing
($r.Content | ConvertFrom-Json).data | % { "source=$($_.source) reason=$($_.llm.fallbackReason) latencyMs=$($_.llm.latencyMillis)" }
```
- [ ] 首次调用先预热（本机模型冷启动可能数秒）：连续调 2 次，第 2 次应为亚秒级；预热不在客户面前做。
- [ ] 若 `source=EVIDENCE` 且 `fallbackReason=PROVIDER_ERROR / PROVIDER_UNAVAILABLE / TIMEOUT`：**按“AI 没开怎么讲”口径**，不现场查密钥、不改配置、不重启。
- [ ] 演示结束后停掉临时实例（脚本会打印 `Stop-Process -Id ...`），临时库 `aps_llm_demo` 用 `DROP DATABASE ... WITH (FORCE)` 清理。

## 二、数据检查（21 项，逐项打勾）

> “当前实测”列是 2026-11-05 03:00 的只读结果，可直接用来判断是否被别人改过。

| # | 场景 | 至少应有 | 当前实测 | 结论 |
|---|---|---|---|---|
| D01 | 登录 | 1 个可用管理员 | 1 用户 `admin` | ✅ |
| D02 | 物料 | 成品 `DEMO-FG-100` ACTIVE | 存在，PCS | ✅ |
| D03 | 物料 | 半成品 `DEMO-SA-200` ACTIVE | 存在 | ✅ |
| D04 | 物料 | 原料 `DEMO-RM-301/302` ACTIVE | 存在 | ✅ |
| D05 | BOM | `DEMO-FG-100` 三层结构 ACTIVE | FG→SA→RM，3 层 | ✅ |
| D06 | 资源 | 6 个 `DEMO-RES-*`（4 主 + 2 辅） | 6 个 | ✅ |
| D07 | 日历 | 201 生效并绑定 6 资源 | 6/6 绑定；7×24 无停机 | ⚠️ 无班次/节假日 |
| D08 | 工艺路线 | `RT-DEMO-1` ACTIVE、6 节点 | 存在，6 节点 | ✅ |
| D09 | 工序模板 | CNC/ASM/QA 3 个 | 9 个模板，演示用 3 个 | ✅ |
| D10 | 订单 | `DEMO-ORD-001..008` 全部 CONFIRMED | 8 张 CONFIRMED | ✅ |
| D11 | 订单 | 3 张延期（001/002/003） | 基线 3 张 late | ✅ |
| D12 | 任务 | 主计划 57 条任务、6 资源行 | 57 条、6 行、4/6 绑日历 | ✅ |
| D13 | 计划版本 | 主计划 ≥2 个版本（1 DRAFT+1 PUBLISHED） | **0 个** | ❌ 缺口 G01 |
| D14 | 库存 | 演示物料 ≥3 条库存行 | **0 条**，可用量全 0 | ❌ 缺口 G02 |
| D15 | 采购建议 | 演示物料 ≥3 条建议 | 5 条（含 2 条他场景） | ⚠️ 含噪音 |
| D16 | 沙箱 | 1 个演示 WHAT_IF 含任务 | 只有 QA2/SE5/SE6 | ❌ 缺口 G03 |
| D17 | 策略 | `DEMO-M1-STRATEGY` ≥8 条规则 | **0 条规则** | ❌ 缺口 G05 |
| D18 | 用户 | ≥3 个（计划员/主管/审计） | **1 个** | ❌ 缺口 G07 |
| D19 | 角色 | ≥3 个 | 5 个 | ✅ |
| D20 | 审计 | ≥20 条，覆盖冻结/发布/求解 | 14 条，7 条冻结 | ⚠️ 缺口 G08 |
| D21 | 导入样例 | 订单模板 + 5 行样例文件 | 模板可下载，无样例文件 | ⚠️ 缺口 G12 |

- [ ] 演示前确认问清版本任务数与甘特任务数的差异来源：`GET /plans/{id}` 的 `taskCount` 是版本快照（不可变），甘特是场景当前工作集（随重排变化），两者不得互校；现场口径“版本是拍照，甘特是现场”。

### 新种子库（aps_demo）专项检查（P0，演示前必做）

> 2026-11-05 起演示使用 `aps_demo` 种子库（`DM-SO-*` 订单），不再用 18085 的 `DEMO-*` 数据。下列各项不通过就不要开始缺料/一键排产镜头。

- [ ] **演示前必须用当天日期重新执行 `seed-demo.ps1 -Reset`**，否则冻结任务可能落到求解窗口外：求解窗口是 `运行时刻 -2h ~ +172h` 的滑动窗口，种子里的 `FROZEN`/锁窗按旧基准日生成后会滑出窗口，pinned 任务重排必然 `-8hard`，给多少预算都解不出。
```powershell
# AnchorDate 缺省 = 运行当天（Asia/Shanghai）；演示当天必须重灌，不要沿用上一轮的数据
.\scripts\seed-demo.ps1 -Database aps_demo -Scene all -Reset
# 彩排可固定基准日复现：-AnchorDate 2026-11-05
```
- [ ] **冻结/锁窗落点已落在当天窗口内**：`DM-SO-1003` 8 条 `FROZEN` 起始应在 `当天 +4 天 08:00`；`DM-SO-1005` 锁窗应在 `当天 +1 天 08:00–11:00`；`DM-SO-1002` 锚点应在 `当天 +6 天 08:00`。
- [ ] **缺料单仍为 `SHORTAGE`**：`DM-SO-1010` 单张 MRP 返回 `status=SHORTAGE`、`kitted=false`，缺料物料 `RAW-BMS-001`、需求 40 / 可用 0 / 缺 40。
```powershell
curl.exe -s -X POST "http://127.0.0.1:18203/api/v1/mrp/analysis" -H "Content-Type: application/json" `
  -d '{\"sandboxId\":\"00000000-0000-0000-0000-000000000401\",\"orderNos\":[\"DM-SO-1010\"],\"includeSuggestions\":true,\"blocking\":false}'
```
- [ ] **六单全量为 `SOLVED`**：`DM-SO-1002..1007` → `status=SOLVED`、`scoreHard=0`、`scoreSoft=0`、`unassignedTaskCount=0`、`tasks=48`、耗时约 17s；不得出现 `hard<0` 或 `unassigned>0`。
- [ ] **坐标未漂移**：`DM-SO-1005` 时间锁窗 `当天 +1 天 08:00–11:00`；`DM-SO-1002` 基线锚点 `当天 +6 天 08:00`；`DM-SO-1008` 单张 MRP 必须是 `FULL`（它已不是缺料单）。

### 会话前 60 秒快速核对（3 条命令）

```powershell
$k = $env:APS_DEMO_READ_KEY
# 1) 订单 17 张、8 张 DEMO CONFIRMED
curl.exe -s -H "X-API-Key: $k" -H "X-Trace-Id: demo-check-d10" "http://127.0.0.1:18085/api/v1/orders?page=1&size=200" | ConvertFrom-Json | % { $_.data.items } | Group-Object status | Format-Table Name,Count
# 2) 主计划任务与延期
curl.exe -s -H "X-API-Key: $k" -H "X-Trace-Id: demo-check-d12" "http://127.0.0.1:18085/api/v1/gantt/order?sandboxId=00000000-0000-0000-0000-000000000401&from=2026-10-20T00:00:00Z&to=2026-12-01T00:00:00Z" | ConvertFrom-Json | % { $_.data.orders } | Measure-Object
# 3) 主计划版本数（当前预期 0）
curl.exe -s -H "X-API-Key: $k" -H "X-Trace-Id: demo-check-d13" "http://127.0.0.1:18085/api/v1/plans?sandboxId=00000000-0000-0000-0000-000000000401&page=1&size=50" | ConvertFrom-Json | % { $_.data.total }
```

## 三、一键重置/恢复

### 1. 首选：数据车道脚本（**待交付**）
`scripts/seed-demo.ps1` 当前尚不存在；交付后按实际参数执行，**不要自行猜参数**。预期形态：

```powershell
# 待交付后按脚本实际参数回填；预期支持 BaseUrl / ApiKey / DbName / Reset / 场景名
pwsh -NoProfile -File scripts/seed-demo.ps1 `
  -BaseUrl http://localhost:18085 `
  -ApiKey  $env:APS_DEMO_WRITE_KEY `
  -DbName  aps_local `
  -Scenario demo-30min `
  -Reset
```

执行前必须确认：
- [ ] 目标库是 `aps_local`；
- [ ] 脚本带 `-Reset` 时只清 `DEMO-*` 固定 UUID，不按前缀 LIKE 删全库；
- [ ] 执行后重跑 D10–D16 的核对命令。

### 2. 兜底：演示前快照 + 还原（推荐每次演示前做）
工具链在 `D:\tools\_aps-toolchain\pgsql\bin`。快照只读，不会改库：

```powershell
$env:PGPASSWORD='aps_dev_pwd'
D:\tools\_aps-toolchain\pgsql\bin\pg_dump.exe `
  -h 127.0.0.1 -p 55432 -U aps -d aps_local `
  -Fc -f D:\tools\aps-demo-before-20261105.dump
```

演示后还原（**会覆盖当前库，必须先经环境负责人同意**）：

```powershell
D:\tools\_aps-toolchain\pgsql\bin\pg_restore.exe `
  -h 127.0.0.1 -p 55432 -U aps -d aps_local `
  --clean --if-exists `
  D:\tools\aps-demo-before-20261105.dump
```

### 3. 仅回滚现场“软动作”（不还原库）
- 新建/确认的急单：保留即可，下一轮演示把订单号改成 `DEMO-RUSH-002`。
- 冻结的订单：在 `/orders` 点“解冻”。
- 任务调整：订单甘特点“撤销本次”（仅最近一次）。
- 发布版本：用 `/plans/publish` 的“回滚”（会生成新版本，需填原因）。
- 求解产生的新任务：UI 无法撤回；要干净基线只能走快照还原或 `seed-demo.ps1 -Reset`。

### 4. 不推荐的兜底
- `scripts/seed-m1-demo.ps1` 会直连库写固定 UUID 的 M1 数据，且订单是 DRAFT；它会与当前已确认的演示单叠加。只在环境负责人同意、且 `seed-demo.ps1` 未交付时使用，并先做快照。

## 四、应急处理（按现象查）

| 现象 | 判断 | 处置 |
|---|---|---|
| 登录成功，但所有页面 401 | Vite 代理注入了无效 `APS_DEV_API_KEY`（常见是 `dev-write-key`） | 改 `.env.development.local` 为后端写密钥，**只重启 Vite**（`Ctrl+C` → `npm run dev`）；不要重启 18085 |
| 单页 401，其他页正常 | JWT 过期（8 小时） | 重新登录；若登录被锁，等 15 分钟，不要连续试 |
| 求解超过 60 秒 | 服务端 60 秒上限 + 5 秒宽限；失败重试间隔 30 秒 | 等宽限结束；点“取消求解”；不要刷新页面；直接切甘特讲上一版 |
| 求解 `TIMEOUT` / `FAILED` | 任务进入终态 | 用“立即刷新”看 `errorMessage`；现场不追第二次重试 |
| 订单点“确认”报 40901 | 行版本过期（别人/上一轮改过） | 刷新列表再点一次；不要连点 |
| 订单无法冻结/解冻 | 状态或版本不匹配 | 刷新后重试；换 `DEMO-ORD-002`；冻结中的任务调整报 42201 属正常 |
| 订单“卡死”在 FROZEN | 页面只是展示状态 | 在订单列表点“解冻”，不要改库；解冻后重排才会放开机时 |
| 甘特空白或显示 Mock | URL 缺 `?source=api`，或超出 11-01–11-12 窗口 | 改 URL 重进；确认右上数据源标签；窗口外任务口头说明 |
| 甘特调整 40901/42201 | 版本冲突 / 订单已冻结 | “重新加载”后换未冻结任务；或只做 dryRun |
| 发布页 0 条 | 主计划无 `plan_version`（已知 G01） | 不现场补数据；切 `a2a2a2a2-0000-4000-8000-000000000001` 展示已有 4 个版本 |
| 计划对比“版本不足 2 个” | sandboxId 错或主计划无版本 | 用 QA2 UUID；确认版本勾选 ≥2 |
| 沙箱对比为空 | 源/目标选反 | 对比方向是“源 − 目标”，重选后再点 |
| 求解进度条不动 | SSE/WebSocket 抖动 | 点“立即刷新”（有 2 秒轮询兜底）；状态以详情为准 |
| MRP 分析失败 | 场景 ID/订单号格式 | 场景 UUID 用默认；订单号英文逗号；仍失败用只读建议接口 |
| 页面提示无权限 | 角色不足（当前仅 admin 全权） | 用 admin；不要现场建号提权 |
| 数据库疑似 `aps_local_2` | 启动参数被改 | 立即停止写操作，通知负责人；不要自行切库 |
| 现场时间已过 11-14 | 演示数据日期陈旧，超期数会异常 | 停止“3 张超期”话术，改用“历史数据回放”，会后重播 |

## 五、演示后 5 分钟

- [ ] 记录现场写操作：建单、确认、冻结、求解任务号、任务调整、发布。
- [ ] 解冻演示冻结的订单。
- [ ] 如果客户碰了“Apply 沙箱”或“发布”：必须快照还原，避免下一场版本错乱。
- [ ] 用 `seed-demo.ps1 -Reset`（交付后）或 `pg_restore` 恢复到演示前快照。
- [ ] 把新发现的缺口补到 [demo-data-gap.md](./demo-data-gap.md)。
