# 数据落点与备份恢复（广州单机）

> 适用：`deploy/gz-api/docker-compose.yml`（compose 项目名 `hulkai-gz-api`）。
> 本文只讲数据存在哪、怎么迁、怎么备、怎么验；上线流程见
> [README.md](README.md)，OrcaTerm 命令见 [orcaterm-quickstart.md](orcaterm-quickstart.md)。

> ## 禁止操作
>
> **不要执行 `docker compose down -v`。**
> `-v` 会删除 `mfg_data`、`hulkai_aps_pgdata`、`hulkai_hogo_runtime` 三个命名卷，
> 等于把 mfg 业务库、案例库、向量索引、APS 全部业务数据和 hogo 会话密钥一次性清空，
> 且无法从镜像恢复（镜像里只有初始种子）。
>
> 停服请用 `docker compose stop` 或 `docker compose down`（不带 `-v`）。
> 同理，**不要**执行 `docker volume prune` / `docker system prune --volumes`。

---

## 1. 逐个服务的数据落点

| 数据 | 容器内路径 | 存储形态 | 重建容器后 | `compose down` 后 | `compose down -v` 后 | 入库 |
| --- | --- | --- | --- | --- | --- | --- |
| APS 业务库（orders / task / plan_version 等 39 张表） | `hulkai_aps_postgres:/var/lib/postgresql/data` | 命名卷 `hulkai_aps_pgdata` | 保留 | 保留 | **丢失** | 否 |
| APS 表结构 | Flyway `V1`–`V15` 共 12 个迁移文件（无 V8–V10） | 镜像内 `db/migration` | 跟随镜像 | 跟随镜像 | 空卷首启自动重建 | 是 |
| APS 演示业务数据 | `scripts/seed-m1-demo.sql`（compose 一次性服务 `aps-seed`） | 仓库 SQL（幂等，齐备即跳过） | — | — | 需手动重跑 | 是 |
| mfg 业务库（37 张表：`spc_measurement`、`quality_defect`、`platform_workflow_task` 等） | `/app/data/mfg_agent.db` | 命名卷 `mfg_data` | 保留 | 保留 | **丢失** | 否 |
| mfg 案例库（8 张表：`case`、`case_chunk` 等） | `/app/data/case.db` | 命名卷 `mfg_data` | 保留 | 保留 | **丢失** | 否 |
| mfg 向量库（知识库 / 案例库两个 collection） | `/app/data/chroma`、`/app/data/chroma_case` | 命名卷 `mfg_data` | 保留 | 保留 | **丢失**，首启按 `SEED_MODE=auto` 重建（1–3 分钟） | 否 |
| mfg 生态链演示数据 | `/app/data/demo_storyline/` | 命名卷 `mfg_data`（首启从镜像补齐） | 保留 | 保留 | **丢失**，首启从镜像重新拷贝 | 否（镜像内有种子副本） |
| mfg 初始种子库 | `/app/seed/demo_seed/mfg_agent.db`、`case.db`（约 19MB / 0.8MB） | **镜像层**，只读 | 跟随镜像 | 跟随镜像 | 仍在，但不会自动覆盖已有库 | 是 |
| mfg 种子 SQL / 语料 | `/app/seed/*.sql`、`/app/seed/knowledge`、`/app/seed/kbqa_corpus` | 镜像层，只读 | 跟随镜像 | 跟随镜像 | 跟随镜像 | 是（仅作离线导入，启动时不执行 SQL） |
| hogo 运行时密钥 | `/app/backend/data/runtime/auth-secret.key` | 命名卷 `hulkai_hogo_runtime` | 保留（会话不失效） | 保留 | **丢失**，首启自动生成新密钥（旧登录态失效，数据不丢） | 否 |
| hogo 样例数据 | `/app/backend/data/samples/hogo-qa-sample.xlsx` | 镜像层，只读 | 跟随镜像 | 跟随镜像 | 跟随镜像 | 是 |
| hogo 前端产物 | `/app/frontend/dist-server` | 镜像层，只读 | 跟随镜像 | 跟随镜像 | 跟随镜像 | 否（线上页面走 COS） |
| Redis | 内存 | **无持久化**（`--save ""`） | 清空 | 清空 | 清空 | 否 |
| Nginx 配置 / 证书 / realip | 宿主 `./nginx/`、`./certs/`、`./nginx/realip.conf` | 宿主目录挂载 | 保留 | 保留 | 保留（不在卷里） | 配置入库、证书**不入库** |
| 部署变量 | 宿主 `deploy/gz-api/.env` | 宿主文件 | 保留 | 保留 | 保留（不在卷里） | **绝不入库** |
| 容器日志 | Docker `json-file`（20MB×5，每服务） | Docker 管理 | 随容器删除 | 删除 | 删除 | 否 |

结论：

- **唯一会因 `down -v` 丢数据的**是三个命名卷；`down` 不会删卷，`restart`/重建容器更不会。
- mfg 的 SQLite **不在镜像层运行**：镜像里 `/app/seed` 只是种子，运行库始终在 `mfg_data` 卷的 `/app/data` 下。
- Redis 丢了不影响业务数据，只影响进行中的 SSE 流与限流计数（可重连）。

---

## 2. 分类：入库 / 不入库 / 必须迁

**必须入库（已在仓库里）**

| 内容 | 位置 | 作用 |
| --- | --- | --- |
| APS 表结构迁移 | `projects/smart-aps-agent/db/migration/V*.sql` | 空库首启由 Flyway 自动建表，幂等（已应用版本跳过） |
| APS 扩展初始化 | `projects/smart-aps-agent/deploy/initdb/01-extensions.sql` | 仅空库首次初始化执行 |
| APS 演示数据 SQL | `projects/smart-aps-agent/scripts/seed-m1-demo.sql` | 服务器 / Linux 通用：`docker compose --profile seed run --rm aps-seed`（见 6.3） |
| APS 演示数据脚本（Windows 本地） | `projects/smart-aps-agent/scripts/seed-m1-demo.ps1` | 本地 Windows 开发用法（含 `-Clean`），依赖便携版 psql；服务器不需要 PowerShell |
| mfg 种子库 | `projects/mfg-agent/api/data/demo_seed/*.db` | **初始种子**，不是运行数据；容器首启且目标文件不存在时复制 |
| mfg 建空库脚本 | `projects/mfg-agent/api/src/mfg_agents/common/init_db.py` | 没有种子时的兜底建表 + 内置少量 `SEED_DATA` |
| mfg 种子 SQL / 语料 | `projects/mfg-agent/api/data/seed_*.sql`、`knowledge/`、`kbqa_corpus/` | 离线导入与知识索引来源，启动时不自动执行 SQL |

**绝不能入库**

- `deploy/gz-api/.env`（含模型 Key、DB 口令、JWT/AUTH 密钥）
- `deploy/gz-api/certs/*.pem|*.key`
- `projects/hogo-qa/backend/data/runtime/auth-secret.key`（本机开发时生成，已被 `.gitignore` 的 `*.key` 覆盖）
- 任何运行中的 `*.db`、`*.dump`、卷打包 `*.tgz`
- `deploy/cos/dist/`、`.demo-verify/`（构建产物与截图）

**必须在服务器上从旧部署迁移**

| 数据 | 为什么 |
| --- | --- |
| APS PostgreSQL 全库 | 旧栈的 orders / task / plan_version / 用户与策略只存在旧 PG 数据目录里，仓库无法重建 |
| mfg `mfg_agent.db` + `case.db` + `chroma/` + `chroma_case/` | 运行期产生的工单、审批、案例与向量索引不在仓库里；种子只是 2026 年初的演示基线 |
| `demo_storyline/` 运行期改动（若有） | 演示治理库 `demo_governance.db` 等是运行期写入 |
| hogo `auth-secret.key`（可选） | 不迁只会让已登录会话失效，不丢业务数据；要"重启不掉线"就迁 |
| `deploy/gz-api/.env` 与证书 | 密钥与证书从来不进仓库，只能人工放到新机器 |

---

## 3. 卷命名与旧部署对照

新栈（本仓库）固定卷名：

| 卷名 | 挂载点 | 说明 |
| --- | --- | --- |
| `mfg_data` | `app:/app/data` | mfg 业务库 + 案例库 + 两个 Chroma 目录 + storyline |
| `hulkai_aps_pgdata` | `aps-postgres:/var/lib/postgresql/data` | APS 全部业务数据 |
| `hulkai_hogo_runtime` | `hogo-qa:/app/backend/data/runtime` | hogo HMAC 密钥 |

与旧部署的关系：

- `deploy/hk-web/docker-compose.yml`（香港旧栈）只持有 `hulkai_hogo_runtime`，**卷名与新栈一致**；
  但它在香港机器上，跨机不会自动共享，需要手工迁（见 3.2）。香港栈没有 mfg SQLite 与 APS PG。
- 旧的**广州**栈不在本仓库里（本仓库的 `deploy/gz-api` 是这次新建的），
  它的卷名/路径**无法静态确认**，必须上服务器现场核对。若卷名不同，新栈会新建空卷并走首启 seed，
  表现是"历史数据不见了"——数据其实还在旧卷里，按 3.2 迁过来即可。

### 3.1 现场确认（只读，不改数据）

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api

# 1) 现有容器与状态（旧容器名可能不同）
docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'

# 2) 现有卷
docker volume ls

# 3) 逐个看挂载点，确认哪个卷是 mfg 的 /app/data、哪个是 PG 的 data 目录
docker inspect mfg_app hulkai_aps_postgres hulkai_hogo_qa \
  --format '{{.Name}} → {{range .Mounts}}{{.Type}} {{.Name}}{{.Source}} ⇒ {{.Destination}}; {{end}}' 2>/dev/null

# 4) 旧容器若还活着，直接看它挂的是什么
docker inspect <旧容器名> --format '{{range .Mounts}}{{.Type}} {{.Name}}{{.Source}} ⇒ {{.Destination}}; {{end}}'
```

### 3.2 迁移步骤（统一顺序：停写 → 备份 → 拷贝 → 启动 → 核对）

> 全程**不要** `down -v`。旧容器只 `stop`，迁完并核对无误后再考虑删除。

**A. mfg（SQLite + Chroma，卷 → 卷）**

```bash
# 1) 停写：先停旧 mfg 容器，再停新 app（避免两个进程同时写同一个 SQLite）
docker stop <旧mfg容器名> 2>/dev/null || true
docker compose stop app

# 2) 备份旧卷（只读打包，保留现场）
mkdir -p /opt/hulkai/backups
docker run --rm -v <旧mfg卷>:/from:ro -v /opt/hulkai/backups:/backup \
  nginx:1.27-alpine tar czf /backup/mfg_data-old-$(date +%F-%H%M).tgz -C /from .

# 3) 拷贝（保留属主与时间戳；新卷为空时才做，避免覆盖新数据）
docker run --rm -v <旧mfg卷>:/from:ro -v mfg_data:/to \
  nginx:1.27-alpine sh -c 'cp -a /from/. /to/ && ls -l /to'

# 4) 起新栈并核对（见第 6 节）
docker compose up -d --no-build app
```

如果旧部署是**宿主目录**而不是卷，把 `-v <旧mfg卷>:/from:ro` 换成 `-v /旧/路径:/from:ro`。

**B. APS（PostgreSQL，逻辑迁移，推荐）**

```bash
# 1) 停写：停旧 APS 后端（让连接断掉），保留旧 PG 运行
docker stop <旧aps后端容器名> 2>/dev/null || true

# 2) 从旧 PG 导出（自定义格式，含 schema 与数据）
docker exec <旧PG容器名> pg_dump -U aps -d aps -Fc -f /tmp/aps-pre-migrate.dump
docker cp <旧PG容器名>:/tmp/aps-pre-migrate.dump /opt/hulkai/backups/aps-pre-migrate-$(date +%F).dump

# 3) 新栈只起 PG，先不要起 aps-backend（避免 Flyway 与恢复并发）
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose up -d aps-postgres

# 4) 恢复：--clean 会重建 schema，dump 里带 flyway_schema_history，版本保持一致
docker cp /opt/hulkai/backups/aps-pre-migrate-*.dump hulkai_aps_postgres:/tmp/aps.dump
docker compose exec -T aps-postgres pg_restore -U aps -d aps --clean --if-exists --no-owner /tmp/aps.dump

# 5) 启动后端（Flyway 只会补缺失的版本）并核对
docker compose up -d --no-build aps-backend
```

如果旧 PG 已经删了、只剩数据目录，则用物理方式：`docker compose stop aps-postgres` →
`docker run --rm -v <旧PG卷>:/from:ro -v hulkai_aps_pgdata:/to nginx:1.27-alpine sh -c 'cp -a /from/. /to/'`，
**必须**保证新旧 PostgreSQL 大版本一致（都是 15），否则不要物理拷贝。

**C. hogo（可选）**

```bash
docker run --rm -v <旧hogo卷>:/from:ro -v hulkai_hogo_runtime:/to \
  nginx:1.27-alpine sh -c 'cp -a /from/. /to/ && chown -R 1000:1000 /to'
```

hogo 容器以 `node`（uid 1000）运行，属主不对会导致无法写密钥。

---

## 4. 覆盖风险：SQLite 会不会被镜像里的种子盖掉

**现状（已满足"只在不存在时复制"）**

- 镜像里 `COPY data /app/seed`，运行库固定写 `/app/data`（挂 `mfg_data` 卷），两者物理分离。
- `entrypoint.sh` 的逻辑：
  - `mfg_agent.db`：`[ ! -f "$DB_FILE" ]` 才 `cp` 种子；已存在打印 `skip seed`。
  - `case.db`：同样只在文件不存在时复制。
  - `demo_storyline/`：目录不存在才整目录复制；存在但缺 `*.jsonl` 时用 `cp -rn` 补齐，**不覆盖**已有文件。
  - 知识索引：`SEED_MODE=auto` 下两个 collection 都非空就跳过，任一为空才补；`force` 才会清空重建。
- hogo：`AUTH_SECRET` 环境变量优先；未设置且文件不存在时才生成密钥，存在则复用。

因此**正常 `pull` + `up` 不会覆盖任何运行数据**。

**副作用（需要知道）**

1. 换了新镜像、种子库升级了，也**不会**自动更新已有的 `mfg_agent.db`——要刷新演示基线必须显式替换（先备份）：
   ```bash
   docker compose stop app
   # 用同一镜像起一次性容器，把镜像里的种子复制进卷（先按第 5 节备份）
   docker run --rm -v mfg_data:/to "$(docker inspect mfg_app --format '{{.Config.Image}}')" \
     sh -c 'cp /app/seed/demo_seed/mfg_agent.db /to/mfg_agent.db && ls -l /to/mfg_agent.db'
   docker compose up -d --no-build app
   ```
   这会丢掉运行期写入，务必先按第 5 节备份。
2. `SEED_MODE=force` 会清空并重建向量库，**只在换 embedding 模型时用**；平时保持 `auto`。
3. 若把 `SEED_MODE` 误设为 `force` 并重启，索引会重建（耗时 1–3 分钟），但 SQLite 数据不受影响。

---

## 5. 备份与恢复

统一备份目录：`/opt/hulkai/backups`（不存在会创建）。脚本：[backup.sh](gz-api/backup.sh)。

### 5.1 一键备份（推荐，在线、不锁库、可定时）

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
bash backup.sh                 # 默认写到 /opt/hulkai/backups
bash backup.sh /data/backups   # 也可指定目录
```

脚本做三件事：`pg_dump -Fc` 导出 APS、用 SQLite backup API 在线备份两个库、打包 hogo 运行时目录；
自动清理 14 天前的文件。**不停止任何容器，不删除任何数据。**

定时（每天 03:10，日志落 `/var/log/hulkai-backup.log`）：

```bash
( crontab -l 2>/dev/null; echo "10 3 * * * bash /opt/hulkai/FactroyAi/deploy/gz-api/backup.sh >> /var/log/hulkai-backup.log 2>&1" ) | crontab -
```

### 5.2 手工命令（脚本不可用时）

**APS：pg_dump / pg_restore**

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
mkdir -p /opt/hulkai/backups

# 备份（自定义格式，支持并行恢复）
docker compose exec -T aps-postgres pg_dump -U aps -d aps -Fc \
  > /opt/hulkai/backups/aps-$(date +%F-%H%M).dump

# 恢复：先停后端，避免恢复期间写库
docker compose stop aps-backend
docker cp /opt/hulkai/backups/aps-2026-11-11-0310.dump hulkai_aps_postgres:/tmp/aps.dump
docker compose exec -T aps-postgres pg_restore -U aps -d aps --clean --if-exists --no-owner /tmp/aps.dump
docker compose start aps-backend

# 纯 SQL 备份（可读性优先）：
docker compose exec -T aps-postgres pg_dump -U aps -d aps > aps.sql
```

**mfg：SQLite 在线备份（不要直接 `cp` 正在写的库）**

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api

docker compose exec -T app python -c "
import sqlite3
for n in ('mfg_agent','case'):
    s = sqlite3.connect(f'/app/data/{n}.db'); d = sqlite3.connect(f'/tmp/{n}.bak')
    s.backup(d); d.close(); s.close()
"
docker cp mfg_app:/tmp/mfg_agent.bak /opt/hulkai/backups/mfg_agent-$(date +%F-%H%M).db
docker cp mfg_app:/tmp/case.bak      /opt/hulkai/backups/case-$(date +%F-%H%M).db
docker compose exec -T app rm -f /tmp/mfg_agent.bak /tmp/case.bak
```

恢复：

```bash
docker compose stop app
docker cp /opt/hulkai/backups/mfg_agent-2026-11-11-0310.db mfg_app:/tmp/mfg_agent.db
docker cp /opt/hulkai/backups/case-2026-11-11-0310.db      mfg_app:/tmp/case.db
docker compose exec -T app sh -c 'cp /tmp/mfg_agent.db /app/data/mfg_agent.db && cp /tmp/case.db /app/data/case.db'
docker compose start app
```

（若容器已停，`docker cp` 仍可用，因为容器还在；**不要**在此时 `down`。）

**卷打包与还原（需要一致性时先停容器）**

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api

# 打包：mfg_data（建议先 stop app，保证 SQLite 一致）
docker compose stop app
docker run --rm -v mfg_data:/data:ro -v /opt/hulkai/backups:/backup \
  nginx:1.27-alpine tar czf /backup/mfg_data-$(date +%F).tgz -C /data .
docker compose start app

# 打包：APS PG（物理打包必须先停 PG）
docker compose stop aps-postgres
docker run --rm -v hulkai_aps_pgdata:/data:ro -v /opt/hulkai/backups:/backup \
  nginx:1.27-alpine tar czf /backup/aps_pgdata-$(date +%F).tgz -C /data .
docker compose start aps-postgres

# 还原到空卷（卷必须已存在；compose up 一次即可创建）
docker run --rm -v mfg_data:/to -v /opt/hulkai/backups:/backup \
  nginx:1.27-alpine sh -c 'tar xzf /backup/mfg_data-2026-11-11.tgz -C /to'
```

**hogo 运行时**

```bash
docker run --rm -v hulkai_hogo_runtime:/data:ro -v /opt/hulkai/backups:/backup \
  nginx:1.27-alpine tar czf /backup/hogo_runtime-$(date +%F).tgz -C /data .

# 还原
docker compose stop hogo-qa
docker run --rm -v hulkai_hogo_runtime:/to -v /opt/hulkai/backups:/backup \
  nginx:1.27-alpine sh -c 'tar xzf /backup/hogo_runtime-2026-11-11.tgz -C /to && chown -R 1000:1000 /to'
docker compose start hogo-qa
```

---

## 6. 部署前后核对清单（30 秒判断数据是否完整）

### 6.1 容器与卷

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose ps                        # 6 个服务应为 running/healthy
docker volume ls | grep -E 'mfg_data|hulkai_aps_pgdata|hulkai_hogo_runtime'
```

### 6.2 关键数据量与基线

```bash
# APS：结构与业务行数（首次导入种子后 orders/task 应非 0）
docker compose exec -T aps-postgres psql -U aps -d aps -Atc "
select 'tables='||count(*) from information_schema.tables where table_schema='public';
select 'orders='||count(*) from orders;
select 'tasks='||count(*) from task;
select 'plan_versions='||count(*) from plan_version;
select 'flyway_max='||max(version) from flyway_schema_history;"

# APS：演示数据是否在位（9 条固定 UUID 订单，7xx 号段只属于演示集）
docker compose exec -T aps-postgres psql -U aps -d aps -Atc "
select 'demo_orders='||count(*) from orders
 where id::text like 'd0000000-0000-0000-0000-0000000007%';"

# mfg：37 张表 + 关键表行数（对齐种子基线）
docker compose exec -T app python -c "
import sqlite3
db = sqlite3.connect('/app/data/mfg_agent.db')
print('tables=', db.execute(\"select count(*) from sqlite_master where type='table'\").fetchone()[0])
for t in ('spc_measurement','quality_defect','platform_workflow_task'):
    print(t, db.execute(f'select count(*) from {t}').fetchone()[0])"

# hogo：密钥存在且非空（大小 64B 左右）
docker compose exec -T hogo-qa ls -l /app/backend/data/runtime/auth-secret.key
```

首启种子基线（仅用于判断"种子是否导入成功"，运行后会增长）：

| 指标 | 期望值 |
| --- | --- |
| mfg `mfg_agent.db` 表数 | 37 |
| mfg `spc_measurement` | 7748 |
| mfg `quality_defect` | 4587 |
| mfg `platform_workflow_task` | 2093 |
| `case.db` 表数 | 8 |
| APS 表数（public） | 39（Flyway 跑到 V15） |
| APS 演示数据（导入后） | materials=5、boms=2、bom_items=4、resources=6、templates=4、routing_nodes=6、routing_edges=5、orders=9（全部 DRAFT） |

### 6.3 空卷首启的自动 seed（哪些自动、哪些手动）

| 服务 | 空卷首启行为 | 是否幂等 |
| --- | --- | --- |
| mfg SQLite | entrypoint 复制 `/app/seed/demo_seed/*.db`；无种子才 `init_db` 建空表 | 是（目标文件存在即跳过） |
| mfg Chroma | `bootstrap`（`SEED_MODE=auto`）仅在 collection 为空时建索引 | 是（两边都非空即跳过） |
| mfg storyline | 目录缺失整目录复制；缺 `*.jsonl` 时 `cp -rn` 补齐 | 是（不覆盖已有文件） |
| hogo | 无 `AUTH_SECRET` 且无密钥文件时生成并写卷 | 是（文件存在即复用） |
| APS 表结构 | Flyway 按版本号补迁移；`initdb/01-extensions.sql` 仅空库执行 | 是（已应用版本跳过） |
| APS admin 口令 | dev profile 下仅当 admin 无口令时写入一次 | 是 |
| APS 演示业务数据 | **手动一次性**：`docker compose --profile seed run --rm aps-seed`（`aps-seed` 为 profile 任务，`up -d` 不会触发）；9 条演示订单齐全时打印 `SKIP` | 是（固定 UUID + `ON CONFLICT (id) DO NOTHING`） |
| Redis | 无数据，无需 seed | — |

#### 6.3.1 APS 演示数据：先判断库空不空，再决定导不导

不能把种子放进 PG 镜像的 `/docker-entrypoint-initdb.d`：那是**数据目录首次初始化**时执行的，
此时 Flyway 还没建表，SQL 必然失败。正确顺序是「aps-backend 健康 → 表结构就位 → 再导数据」。

```bash
cd /opt/hulkai/FactroyAi/deploy/gz-api
docker compose exec -T aps-postgres psql -U aps -d aps -Atc "
select 'tables='||count(*) from information_schema.tables where table_schema='public';
select 'demo_orders='||count(*) from orders
 where id::text like 'd0000000-0000-0000-0000-0000000007%';"
```

判断规则：

| 输出 | 含义 | 动作 |
| --- | --- | --- |
| `tables` < 39 或查询报 `relation "orders" does not exist` | Flyway 还没跑完 / 启动失败 | 先 `docker compose logs --tail=200 aps-backend`，不要导数据 |
| `tables=39`、`demo_orders=0` | 空库，表结构就位，没有演示数据 | 执行下面的导入命令 |
| `tables=39`、`demo_orders=9` | 演示数据已在 | 不用再导；误执行也只会打印 `SKIP` |
| `tables=39`、`0 < demo_orders < 9` | 演示集不完整（被人删过） | 执行导入命令，会补齐缺的部分 |

导入（`aps-seed` 会等 `aps-backend` healthy 后才连库）：

```bash
docker compose --profile seed run --rm aps-seed
```

三种结果，都会在最后打印一行明确结论：

| 输出 | 含义 | 退出码 |
| --- | --- | --- |
| `SEEDED: APS 演示数据已导入（materials=5, ... orders=9, ...）` | 本次插入/补齐完成 | 0 |
| `SKIP: APS 演示数据已存在（9/9 演示订单齐全），本次未做任何修改` | 幂等跳过，数据没动 | 0 |
| `FAIL: APS 演示数据导入失败（psql 退出码 N）...` + 上方 psql `ERROR` 行 | 事务整体回滚 | 非 0 |

`aps-seed` 带 `profiles: ["seed"]`，所以 `docker compose up -d`、`restart`、发版都不会自动跑它，
不会出现"每次 up 都灌一遍"。重复执行本身也安全（见下表"幂等"）。

导入日期全部相对执行时刻计算，不写死：

- 订单交期 = 导入日 + 3…10 天，单号 = `SO-<导入日 YYYYMMDD>-0001..0009`；
- 日历、工艺路线、BOM、策略的生效日 = 导入日。

所以导入后看到的是"当前进行中"的计划；隔天不会自动改日期，需要刷新演示日期时先清理再导入
（见 6.3.2）。

#### 6.3.2 两条路径的取舍

| | 服务器 / Linux（默认） | Windows 本地开发 |
| --- | --- | --- |
| 入口 | `docker compose --profile seed run --rm aps-seed` | `.\scripts\seed-m1-demo.ps1` |
| 依赖 | 只要 Docker + 仓库里的 `seed-m1-demo.sql` | PowerShell 5.1 + 便携版 psql（`-Toolchain`） |
| 建表 | 必须等 `aps-backend` 建好表 | 检测到空库时会先执行 `V1__init.sql` |
| 数据内容 | 同一份演示集（固定 UUID） | 同一份演示集（固定 UUID） |
| 清理 | 无内置 `-Clean`（见下方说明） | `-Clean` 只删演示数据 |
| 适用 | 服务器首启、重建空卷后补数据 | 本地起库调试 |

服务器上不建议装 `pwsh` 来跑 ps1：多一层运行时、还要传便携版 psql；Linux 直接用
`aps-seed` 一条命令即可。Windows 侧保留 ps1，是因为它顺带管理本地库的起停与建表。

> Linux 上确实需要"清空演示数据"时，按固定 UUID 删除即可（先删引用订单的 `task`，
> 再删 `orders`，顺序反了会撞外键）：
>
> ```bash
> docker compose exec -T aps-postgres psql -U aps -d aps -c "
> delete from task   where order_id in (select id from orders where id::text like 'd0000000-0000-0000-0000-0000000007%');
> delete from orders where id::text like 'd0000000-0000-0000-0000-0000000007%';"
> ```
>
> 之后重新执行导入命令即可拿到以当天为基准的新日期。主数据（物料/BOM/资源/路线/策略）
> 的 id 固定，`ON CONFLICT DO NOTHING` 会保留原行，不会重复。

导入后核对（与 6.2 的基线一致）：

```bash
docker compose exec -T aps-postgres psql -U aps -d aps -Atc "
select 'materials='||count(*) from material where id::text like 'd0000000-0000-0000-0000-0000000001%';
select 'boms='||count(*) from bom where id::text like 'd0000000-0000-0000-0000-0000000006%';
select 'resources='||count(*) from resource where id::text like 'd0000000-0000-0000-0000-0000000003%';
select 'templates='||count(*) from operation_template where id::text like 'd0000000-0000-0000-0000-0000000004%';
select 'routing_nodes='||count(*) from routing_node where routing_id='d0000000-0000-0000-0000-000000000501';
select 'routing_edges='||count(*) from routing_edge where routing_id='d0000000-0000-0000-0000-000000000501';
select 'orders='||count(*) from orders where id::text like 'd0000000-0000-0000-0000-0000000007%';
select 'orders_draft='||count(*) from orders where id::text like 'd0000000-0000-0000-0000-0000000007%' and status='DRAFT';"
```

期望：`materials=5 boms=2 resources=6 templates=4 routing_nodes=6 routing_edges=5 orders=9 orders_draft=9`。

### 6.4 部署后 30 秒判定

1. `docker compose ps` 全部 `running`，`app` / `aps-backend` / `hogo-qa` 为 `healthy`；
2. APS `orders` 行数不少于迁移前记录值；
3. mfg `tables=37` 且 `spc_measurement` 行数不少于迁移前；
4. `auth-secret.key` 存在（或 `.env` 里显式设置了 `HOGO_AUTH_SECRET`）；
5. `docker volume inspect mfg_data` 的 `Mountpoint` 与迁移时用的卷一致。

任何一项不符：**先停服务、保留现场**（`docker compose stop`），按第 5 节从备份恢复，再回来核对。

---

## 7. 与旧部署并存的注意事项

- 迁移期间新旧两套容器**不能同时写同一个 SQLite 文件**（旧栈若是宿主目录，则连"同一个文件"都不会，优先按卷迁移）。
- 新旧两套若端口冲突（旧栈也占 80/443），先停旧栈再起新栈；停旧栈用 `stop`，不要 `down -v`。
- 旧栈确认下线后，删除旧卷前先按 5.1 做一次完整备份，并保留至少 14 天。
