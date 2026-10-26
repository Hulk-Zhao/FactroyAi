# Hogo-QA-tool

> 本目录是 `hulkai` 单仓的 `projects/hogo-qa/` 子项目（历史由 git subtree 完整保留）。
> 线上形态由仓库根目录的 [deploy/hk-web](../../deploy/hk-web/README.md) 部署到
> `https://hulkai.cn/demo/hogo-qa/`；下文命令均在**本目录内**执行。

[![CI](https://github.com/Hulk-Zhao/hulkai/actions/workflows/hogo-qa.yml/badge.svg)](https://github.com/Hulk-Zhao/hulkai/actions/workflows/hogo-qa.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

给工厂质量人员的**离线 SPC 分析工具**：导入 Excel 测量值与不良数据，得到 Cp/Cpk/Pp/Ppk、控制图判异、
柏拉图与能力图，可打印 / 导出 Excel / Word，并可选用 AI 做解读。

- **纯离线**：构建产物是**单个 `index.html`**，双击即用。没有后端、没有数据库，**数据不出本机**。
- **统计学口径是对的**：Cp/Cpk 用组内 σ、Pp/Ppk 用整体 σ —— 修正了旧工具「Pp 恒等于 Cp」的缺陷（见下）。
- **AI 是可选项**：可接任意 OpenAI 兼容服务（DeepSeek、本机 Ollama…）。不配即为纯离线工具，功能一样不少。
- **可上线展示**：`frontend/` + `backend/` 分离部署，自带登录门禁（防刷）与内置样例数据集，
  `docker compose up -d` 一条命令即可部署到云端服务器作为作品展示（见[云端部署](#云端部署)）。

## 仓库结构

```
Hogo-QA-tool/
├── frontend/                 # 前端模块（React 18 + Vite，含统计内核 core/）
│   ├── src/                  #   源码（core / data / services / store / ui）
│   ├── samples/              #   标准样例 xlsx 与复算脚本（sample:verify）
│   ├── docs/                 #   PRD、架构设计、基准口径等文档与截图
│   └── scripts/              #   构建/服务/自检脚本
├── backend/                  # 后端模块（零 npm 依赖 Node 18+ 服务）
│   ├── server.mjs            #   入口：静态托管 + SPA 回退 + 登录鉴权 + 样例 API
│   ├── lib/                  #   config / auth / static / samples
│   ├── scripts/smoke.mjs     #   9 项冒烟自检
│   └── data/samples/         #   可下载样例（含 manifest.json 元数据）
├── Dockerfile                # 多阶段构建：容器内构建前端 → 零依赖后端运行
├── docker-compose.yml        # 一条命令部署（端口 8788）
└── start.bat                 # Windows 一键构建 + 本机服务
```

## 界面

下面 6 张图都取自**真实构建产物** + 仓库自带的样本数据
（[`frontend/samples/hogo-qa-sample.xlsx`](frontend/samples/hogo-qa-sample.xlsx)），
由 `.probe/p13-readme-shots.mjs` 在真 Chrome 里自动拍摄 —— 不是手截的示意图，可自己复现。

**数据导入** —— 拖入旧工具格式的 xlsx，先给校验结果（特性 3 / 测量值 150 / 不良类型 6 / 空值 0），列名自动识别、可改。

![数据导入与校验](frontend/docs/images/01-import.png)

**能力分析** —— Cp/Cpk 与 Pp/Ppk 并列（1.89/1.42 vs 1.08/0.81），直方图叠加规格限，双西格玛水平标注差异。

![过程能力分析](frontend/docs/images/02-capability.png)

**控制图** —— Xbar-R 双图 + 分区带，违规点标红，右侧 12 条判异准则逐条开关，下方给出判异明细。

![控制图与判异](frontend/docs/images/03-control-chart.png)

**柏拉图** —— 双 Y 轴、80% 分界线，一眼看出「毛刺 + 尺寸超差 + 划伤」三类占 81.31%。

![柏拉图](frontend/docs/images/04-pareto.png)

**报表导出** —— 导出范围 7 项逐项勾选（数据表 4 + 图表 3），导出 Excel 时图表会内嵌进 `[图表]` sheet。

![报表导出](frontend/docs/images/05-report.png)

**项目库** —— 每次导入都会落盘成一个项目，刷新或重开后自动恢复上次项目。

![项目库](frontend/docs/images/06-library.png)

## 样例数据集（一键体验）

内置 **3 个确定性合成数据集**，已并入「数据导入」页的样例区（原独立「样例数据」页已移除）：
每个卡片上的 Cpk/Ppk 都由统计内核**实时计算、可复算**，
并配有迷你趋势图与规格限虚线。点击「载入并分析」直接进入能力分析页（同一口径：子组容量 5，Xbar-R）；
点击「下载 XLSX」可取回与仓库标准样例同构的 Excel，再走「数据导入」回灌验证。

| 样例                     | 演示要点                                                                                     |
| ------------------------ | -------------------------------------------------------------------------------------------- |
| **标准样例 · 机加工三特性** | 三条特性 Cpk 分属 合格 / 偏低 / 不合格 三档；慢漂移 + 阶跃把 Ppk 拉低 0.3~0.6（Cp≠Pp）；柏拉图越过 80% 分界线 |
| **焊点拉力 · 双 σ 口径**    | Cpk 1.92（优秀）vs Ppk 1.62（合格）：批间慢漂移吃掉整体能力余量，两口径差约 0.3              |
| **注塑件重量 · 漂移与超差** | 均值上漂 + 阶跃出界：超 USL 2 点、判异连片命中、Ppk 0.43 显著劣化                            |

所有样例数据为合成数据（mulberry32 种子随机数，固定种子可复现），不含任何真实产线数据。
数字回归断言见 `frontend/src/data/samples/__tests__/sampleDatasets.spec.ts`。

## 登录门禁（防刷）

线上部署形态自带一个轻量登录页：输入口令 + 一次性图形验证码后签发 HMAC-SHA256 无状态 token
（默认 12 小时有效），配合按 IP 限流防止口令被暴力穷举：

- **图形验证码**：4 位字符 SVG（点图刷新），一次性校验、默认 10 分钟有效；同一 IP 每分钟最多签发 30 张
  （`CAPTCHA_TTL_SECONDS` / `CAPTCHA_RATE_LIMIT` 可调）。
- **同一 IP 连续输错 5 次 → 锁定 10 分钟**（验证码错误同样计数）；每次失败固定延迟 600ms（抗时序侧信道 + 拖慢脚本）。
- token 载荷只含签发/过期时间，**不落任何用户信息**；密钥首次启动自动生成并持久化到
  `backend/data/runtime/auth-secret.key`（容器里走挂载卷）。
- **离线包（`file://` 双击打开）自动跳过门禁**——本地单机工具没有可攻击的登录端点，
  强行弹登录框只会妨碍离线核心功能。

全部行为有 12 项冒烟自检：`cd backend && npm run smoke`。

## 快速开始

> 只有**构建**需要 Node ≥ 22.22.2（见 `frontend/.nvmrc` / `package.json` 的 `engines`）；
> 后端运行只需 Node ≥ 18（零 npm 依赖）。
> 构建出来的 `frontend/dist/index.html` 是单文件，拷到任何机器上双击即可用，**目标机器不需要 Node**。

**第 1 步 · 取代码并安装依赖**（所有系统都一样）

```bash
git clone https://github.com/Hulk-Zhao/Hogo-QA-tool.git
cd Hogo-QA-tool/frontend
npm ci
```

**第 2 步 · 运行，四选一**

| 方式                           | 怎么做                                                                                              | 适合                                                                                                                                   |
| ------------------------------ | --------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| **本地服务**<br>（功能最完整） | Windows：双击根目录 `start.bat`<br>macOS / Linux：在 frontend 内 `npm run build:server`，然后仓库根目录 `node backend/server.mjs` → http://127.0.0.1:8788 | 日常使用。IndexedDB 可用，**项目库能长期保存**；登录门禁正常工作（`/api` 由同一进程提供）                     |
| **单文件离线包**               | `npm run build`，然后双击 `frontend/dist/index.html`                                                | 拷给同事 / 放 U 盘。目标机器无需 Node，项目库照常保存（Chrome 上实测 `file://` 下 IndexedDB 可用）；`file://` 下门禁自动关闭 |
| **开发模式**                   | `npm run dev` → http://localhost:5173/（`/api` 代理到 8788，需另开终端先跑 `node backend/server.mjs`） | 改代码，热更新                                                                                                                         |
| **云端展示**                   | 仓库根目录 `docker compose up -d --build` → http://<服务器IP>:8788                                  | 作品展示 / 演示（见下节）                                                                                                              |

**常用命令**

```bash
cd frontend
npm test        # 全部单测
npm run verify  # typecheck + lint + 单测（提交前跑这个）

cd ../backend
npm run smoke   # 后端 9 项冒烟（登录/限流/静态托管）
```

### 构建

| 命令                          | 产物                    | 用途                           |
| ----------------------------- | ----------------------- | ------------------------------ |
| `npm run build`               | `frontend/dist/index.html` | **单文件离线版**，双击即可运行 |
| `npm run build:server`        | `frontend/dist-server/` | HTTP 服务版（支持 code-split），后端托管的就是它 |

## 云端部署

生产形态已并入 `hulkai` 单仓，由香港机器上的 `deploy/hk-web` 统一部署，
入口是 **`https://hulkai.cn/demo/hogo-qa/`**（Nginx 反代到本机 `hogo-qa` 容器 8788）。

```bash
# 仓库根目录执行
cp deploy/hk-web/.env.example deploy/hk-web/.env   # 改 HOGO_AUTH_PASSWORD
docker compose -f deploy/hk-web/docker-compose.yml up -d --build
```

子路径部署的关键是**构建基路径**：compose 传入 `HOGO_BASE=/demo/hogo-qa/`，
前端据此生成资源地址、Router basename 与 API 前缀（`${BASE_URL}api/`）。
不传该参数则按相对路径构建，适用于独立域名或直接访问 `:8788` 的部署方式。

### 独立部署（不并入站点时）

```bash
AUTH_PASSWORD=你的口令 docker compose up -d --build   # 容器内端口 8788
```

多阶段构建：阶段 1 在容器内执行 `npm ci + npm run build:server`；阶段 2 只携带
零依赖后端与静态产物（最终镜像**不含 node_modules / 源码 / 构建工具链**），
以非 root 用户运行，密钥写入挂载卷 `hogo-runtime`。默认监听 `8788` 端口。

### 纯 Node（无 Docker）

```bash
cd frontend && npm ci && npm run build:server && cd ..
node backend/server.mjs          # 默认 0.0.0.0:8788
```

服务器长期运行建议配 systemd（`ExecStart=/usr/bin/node /opt/Hogo-QA-tool/backend/server.mjs`），
或用 pm2：`pm2 start backend/server.mjs --name hogo-qa`。

### 环境变量（backend/.env 或容器 environment）

| 变量               | 默认值                     | 说明                                                                 |
| ------------------ | -------------------------- | -------------------------------------------------------------------- |
| `PORT`             | `8788`                     | 监听端口                                                             |
| `AUTH_PASSWORD`    | `123`                      | **演示口令，公网部署务必修改**                                       |
| `AUTH_SECRET`      | 自动生成并持久化           | HMAC 密钥；多实例部署时固定同一值                                    |
| `TOKEN_TTL_HOURS`  | `12`                       | 登录会话有效期                                                       |
| `RATE_MAX_FAILS`   | `5`                        | 同一 IP 最大连续失败次数（超过锁定）                                 |
| `RATE_LOCK_MINUTES`| `10`                       | 锁定时长                                                             |
| `LOGIN_DELAY_MS`   | `600`                      | 登录失败固定延迟                                                     |
| `STATIC_DIR`       | `../frontend/dist-server`  | 前端静态产物目录（相对 `backend/` 或绝对路径）                       |

生产环境建议在前面加一层 Nginx/Caddy 做 HTTPS 与 gzip，反代到 `127.0.0.1:8788` 即可；
后端已内置基础安全响应头与 SPA 回退，无需额外配置。

## 功能

| 模块         | 说明                                                                                                                                                                                                                        |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **数据导入** | 旧工具 xlsx 双 sheet、CSV 长/宽表、粘贴导入；列名容错（大小写 / 全角 / 全角空格）；20 万条上限仅提示不截断；页内样例区内置 3 个确定性合成样例，实时计算 Cpk/Ppk，一键载入分析或下载 xlsx 回灌 |
| **能力分析** | Cp/Cpk/Pp/Ppk 双口径、双西格玛水平、正态性检验、异常值两步确认；规格限按特性自动预填；多特性 Cpk 汇总对比表                                                                                                                 |
| **控制图**   | Xbar-R / Xbar-S / I-MR / P / NP / C / U 七种，分区带、违规高亮、变限 P/U                                                                                                                                                    |
| **判异准则** | Western Electric W1–W4 + Nelson N1–N8，逐条开关（刷新后保持），去重与等效标注                                                                                                                                               |
| **柏拉图**   | 双 Y 轴、80% 分界线、悬浮显示频数与累计占比                                                                                                                                                                                 |
| **报表导出** | 导出范围 7 项逐项勾选；打印 / PDF 为 A4 光栅快照（光栅宽 2200px ÷ 186mm 可打印宽 ≈ **300 DPI**，单测契约 ≥ 250 DPI；图铺满可打印区）；Excel 含 4 个数据 sheet + 图表 sheet（内嵌 PNG）+ AI 分析 sheet                       |
| **AI 助手**  | 微信式对话视窗；OpenAI 兼容 API；默认只发统计摘要、不发原始测量值；一键全面诊断；**6 大类分析方向 × 6 个报表模块**的 AI 分析导出（Markdown / Excel / Word）；回复可**引用真实图表**（前端按你自己的数据现算，不是模型画图） |
| **项目库**   | 搜索 / 打开 / 重命名 / 复制 / 删除；刷新或重开后自动恢复上次项目（IndexedDB，降级 localStorage / 内存）                                                                                                                     |
| **设置**     | AI 配置与连通性测试、一键清除已保存配置、判异准则开关、常数表、AI 调用审计（跨会话保留）                                                                                                                                    |

## 样本数据与复算

仓库自带一份**合成**样本 [`frontend/samples/hogo-qa-sample.xlsx`](frontend/samples/hogo-qa-sample.xlsx)
（3 个特性 × 50 条测量值 + 6 类不良，与上面的截图是同一份数据）。
它是确定性生成的：`node samples/make-sample.mjs` 可以重新产出逐字节相同的文件。

下面所有数字都能用一条命令、通过**产品自己的统计内核**复算出来：

```bash
cd frontend && npm run sample:verify
```

它走的是界面同一条链路 —— `parseXlsx` → `buildModel` → `buildSubgroups` / `computeCapability` /
`buildControlChart` / `evaluateRules` / `buildPareto`，**没有第二份公式实现**。
样本被改动、或内核被改坏时这条命令会失败，所以下面这张表不会悄悄过期。

**1. 过程能力**（σ_within 走 R̄/d2，σ_overall 走样本标准差 ddof=1）

| 特性     |   n |    均值 | σ_within | σ_overall |   Cp |  Cpk |   Pp |  Ppk | 3×Cpk | 3×Cpk+1.5 | 判定                        |
| -------- | --: | ------: | -------: | --------: | ---: | ---: | ---: | ---: | ----: | --------: | --------------------------- |
| 外壳长度 |  50 | 50.0496 |  0.03520 |   0.06159 | 1.89 | 1.42 | 1.08 | 0.81 |  4.27 |      5.77 | 合格（≥1.33）               |
| 转轴直径 |  50 | 12.0027 |  0.00525 |   0.00653 | 1.27 | 1.10 | 1.02 | 0.88 |  3.30 |      4.80 | 偏低（1.00~1.33），建议改善 |
| 端面跳动 |  50 |  0.0598 |  0.00763 |   0.00865 | 1.31 | 0.88 | 1.16 | 0.78 |  2.65 |      4.15 | 不合格（<1.00），必须改善   |

三个特性刻意落在三档判定上。注意 `外壳长度` 的 **Cp 1.89 与 Pp 1.08 差了 43%** ——
σ_within 0.0352 与 σ_overall 0.0616 本来就是两个量，旧工具 `std_sub = std_total`
会让 Pp 恒等于 Cp，把这条信息抹平（见下节）。

**2. 控制图与判异**（Xbar-R，子组容量 5）

| 特性     | 子组数 | CL（X̄） |     UCL |     LCL |       R̄ | 命中规则       |
| -------- | -----: | ------: | ------: | ------: | ------: | -------------- |
| 外壳长度 |     10 | 50.0496 | 50.0968 | 50.0023 | 0.08187 | N5、N6、N8、W1 |
| 转轴直径 |     10 | 12.0027 | 12.0097 | 11.9956 | 0.01222 | N5、W1         |
| 端面跳动 |     10 |  0.0598 |  0.0700 |  0.0496 | 0.01774 | W1             |

样本里人为埋了「整段均值阶跃 / 单点尖峰 / 连续上升」三种异常，
所以判异不是空转 —— 界面上这些点会被标红并逐条给出窗口起点与涉及点。

**3. 柏拉图**（合成不良数据）

| 排名 | 不良类型 | 数量 |  占比 | 累计占比 |
| ---: | -------- | ---: | ----: | -------: |
|    1 | 毛刺     |   42 | 39.3% |   39.25% |
|    2 | 尺寸超差 |   27 | 25.2% |   64.49% |
|    3 | 划伤     |   18 | 16.8% |   81.31% |
|    4 | 变形     |    9 |  8.4% |   89.72% |
|    5 | 气孔     |    6 |  5.6% |   95.33% |
|    6 | 其他     |    5 |  4.7% |  100.00% |

前 3 类累计 81.31%，第 3 项越过 80% 分界线。

## 核心差异化：双西格玛口径

旧版 Python 工具里 `spc.py` 有一处缺陷：`std_sub = std_total`，**未做子组分组**，导致
**Pp 恒等于 Cp** —— 组内变异与整体变异无法区分，能力指数不可信。本工具按标准口径拆开：

| 指数     | σ 口径                         | 含义             |
| -------- | ------------------------------ | ---------------- |
| Cp / Cpk | σ_within（组内：R̄/d2 或 S̄/c4） | 过程**潜在**能力 |
| Pp / Ppk | σ_overall（整体，ddof=1）      | 过程**实际**表现 |

同时展示**双西格玛水平**（短期 3×Cpk vs 工程口径 3×Cpk+1.5）并标注差异，避免两者混用造成误判。

## 技术栈与架构

Vite 5 · React 18 · TypeScript 5 · MUI 5 · Tailwind 3 · zustand 4 · ECharts 5 · SheetJS 0.18 · idb 8 · vitest 2；
后端为**零 npm 依赖**的 Node 原生 HTTP 服务（`node:http` / `node:crypto` / `node:fs`）。

```
frontend/src：core（纯函数内核）→ data（导入/导出/持久化）→ services（业务编排）→ ui / store
backend/：    server.mjs（路由）→ lib/{config, auth, static, samples}
```

`src/core/**` 通过 ESLint `no-restricted-imports` **强制零 React / DOM 依赖**，
保证统计公式可在 node 环境被独立验证 —— 这是整个项目可验证性的基石。

## 安全与数据主权

本工具默认离线、数据不出本机，但有几处**已知边界**需要知情：

1. **API Key 以明文存于本机浏览器**。`file://` 形态下没有可用的加密后端，密钥只能明文写入
   `localStorage["hogo-qa-settings"]`。影响面仅本机浏览器配置目录；工具**不会**把密钥发往任何
   非用户配置的地址。「设置」页有「清除已保存的 AI 配置」可一键擦除。
2. **xlsx@0.18.5 的原型污染风险（CVE-2023-30533）**。SheetJS 0.18.5 是 npm 上可安装的最后一个版本，
   因此导入器在**出口**做了独立于版本的防护：解析前后对比 `Object.prototype` 并清除注入属性（同时给出告警），
   单元格只放行 `string | number | null`。中期计划评估迁移到 `exceljs`。
3. **AI 默认只发送统计摘要，绝不包含逐条原始测量值**。发送原始数据需显式勾选并二次确认，
   每次请求的实际字段以气泡上的「发送字段」清单为准（由构造请求的同一份白名单直接产出）。
4. **线上部署形态的登录门禁是防刷而非强鉴权**。单口令 + 限流足以拦截爬虫与恶意扫站，
   但不是多用户权限系统；所有分析计算都在浏览器本地完成，后端只托管静态文件与样例下载，
   **用户测量数据不上传服务器**。

<details>
<summary>本机存储键清单（便于审计与手工清理）</summary>

| localStorage 键         | 内容                                   |
| ----------------------- | -------------------------------------- |
| `hogo-qa-settings`      | AI 配置（含明文 API Key）              |
| `hogo-qa-preferences`   | 判异准则 12 条开关 + 报表导出范围 7 项 |
| `hogo-qa-diagnosis`     | AI 全面诊断报告（最新一份）            |
| `hogo-qa-ai-usage-logs` | AI 调用审计记录（最多 500 条）         |
| `hogo-qa-last-project`  | 上次保存 / 打开的项目 id               |
| `hogo-auth-session`     | 线上门禁的登录会话（token + 过期时间） |

项目实体与测量值不在上述键中：走 IndexedDB（库名 `hogo-qa-tool`，对象存储 `projects` / `measurements`），不可用时降级
localStorage，再不可用则仅内存（UI 会提示导出 JSON）。

</details>

## 文档

| 文件                                                                     | 内容                                           |
| ------------------------------------------------------------------------ | ---------------------------------------------- |
| [frontend/docs/PRD_v1.0.md](frontend/docs/PRD_v1.0.md)                   | 产品需求文档                                   |
| [frontend/docs/system_design.md](frontend/docs/system_design.md)         | 系统架构设计（含统计公式与判异算法定义）       |
| [frontend/docs/00-范围决策与调研结论.md](frontend/docs/00-范围决策与调研结论.md) | 范围决策、旧工具缺陷、行业调研                 |
| [frontend/docs/01-基准数据与验证口径.md](frontend/docs/01-基准数据与验证口径.md) | 回归基准值与验收口径                           |
| [frontend/docs/02-AI接入与排障.md](frontend/docs/02-AI接入与排障.md)     | AI 接入步骤、报错对照表、对话与图表引用行为    |
| [frontend/docs/03-构建与运行形态.md](frontend/docs/03-构建与运行形态.md) | 离线/HTTP 两种形态的差异与原因、开发约定与门禁 |
| [CONTRIBUTING.md](frontend/CONTRIBUTING.md)                              | 贡献指南（开发环境、门禁、代码约定）           |
| [.probe/README.md](.probe/README.md)                                     | 真机验收脚本怎么跑（含真 LLM 探针）            |

## 开源与协作

- **许可证**：[MIT](LICENSE)。
- **贡献**：见 [CONTRIBUTING.md](frontend/CONTRIBUTING.md)。提交前请跑 `npm run verify` 与 `npm run build`。
- **CI**：[.github/workflows/ci.yml](.github/workflows/ci.yml) 在 Node 24 上跑前端类型 / 静态检查 / 单测 / 两种构建，
  并对后端做语法检查与 9 项冒烟。
- **测试用的基准数据**：需要真实 xlsx 的用例默认读仓库内 fixture
  （`frontend/src/data/__tests__/fixtures/quality_data.xlsx`，纯合成数据）。想换成自己的文件：
  `HOGO_REAL_XLSX=/path/to/quality_data.xlsx npm test`；两者都没有时这几组用例**整体 skip**（不会红）。
