# mfg-agent backend（广州）

FastAPI + SSE 后端，负责编排、数据查询、案例/制度检索。向量化全部使用
Qwen MaaS `qwen3.7-text-embedding-flash`（DashScope 原生接口，1024 维），
检索再用 `qwen3.7-text-rerank` 精排（失败自动回落 RRF）。不加载本地模型。

## 部署

```bash
cp .env.example .env
vim .env                       # DS_*（对话）+ QW_*（仅向量）+ AUTH_SECRET
docker compose up -d --build
docker logs -f mfg_app
curl -s localhost:8004/api/v2/health | jq '.checks.embedding, .checks.vector'
```

首次启动：`entrypoint.sh` 初始化 SQLite → `common.bootstrap` 按 `SEED_MODE`
决定是否灌库（auto 会跳过已建好的索引）→ uvicorn 单 worker 启动。

## 换 embedding 模型

1. 改 `.env` 的 `EMBEDDING_MODEL / EMBEDDING_DIM`；
2. `docker compose exec app python scripts/rebuild_index.py --source all`
   （或在一次性容器里执行）；
3. `/api/v2/health` 确认 `embedding` 与 `vector` 均为 `ok`。

## 数据

- 种子与语料在 `data/`；演示种子库在 `data/demo_seed/`（含 2093 条历史工单）；
- 镜像内 `/app/seed` 放只读种子；运行时数据写 `/app/data` volume；
- 首次启动如果 `/app/data/mfg_agent.db` 不存在，entrypoint 会先复制
  `/app/seed/mfg_agent.db`（案例库同理），保留旧环境的演示数据；
- 运行库：`/app/data/mfg_agent.db`、`/app/data/case.db`、`/app/data/chroma*`；
- 备份：`docker run --rm -v mfg_data:/data -v "$PWD":/backup alpine tar czf /backup/data.tgz -C /data .`

> 种子里是合成演示数据，不含真实客户信息；`.db` 已在 `.gitignore` 中赦免
> `backend/data/demo_seed/*.db` 这一条，其余运行库仍不入库。
