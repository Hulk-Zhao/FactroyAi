# CI/CD 流水线设计（P0-04）

面向「智能APS 开源版」v0.1 MVP 的持续集成与制品发布方案。同一套构建命令同时落地产出 **GitHub Actions** 与 **GitLab CI** 两份配置，便于开源社区按所用平台自行切换。

## 1. 交付物

| 文件 | 作用 |
|---|---|
| `.github/workflows/ci.yml` | GitHub Actions 主流水线（打包 / 单测 / 静态检查 / 前端 / 镜像） |
| `.gitlab-ci.yml` | GitLab CI 等价流水线（build / lint / test / package / image） |
| `aps-admin/Dockerfile` | 后端 multi-stage 镜像（Maven/Temurin 21 JDK 构建 → Temurin 21 JRE 运行，非 root） |
| `aps-frontend/Dockerfile` | 前端 multi-stage 镜像（Node 22 构建 → nginx-unprivileged 托管 dist，非 root / 8080） |
| `aps-frontend/nginx/default.conf.template` | SPA 路由回退、静态资源缓存、`/api` 反向代理 |
| `scripts/ci-build.ps1` / `scripts/ci-build.sh` | 本地复现 CI 的构建脚本（与流水线命令一致） |
| `.dockerignore` / `aps-frontend/.dockerignore` | 缩小镜像构建上下文 |
| `docs/ci-cd.md` | 本文档 |

**Dockerfile 位置选择**：后端选 `aps-admin/Dockerfile`，构建上下文固定为**仓库根目录**（需要父 POM 与全部内部模块）；未在根目录放 `Dockerfile`，避免与未来其他服务（如独立调度服务）冲突，也让镜像与「启动入口模块」一一对应。前端 `aps-frontend/Dockerfile` 自带上下文，与前端源码同目录。

## 2. 流水线结构

```
push / PR
   │
   ├─ build → backend:build（mvn package -DskipTests）  ──┐
   │        └ frontend:build（npm ci && npm run build）   │
   ├─ lint  → backend:lint（mvn verify）                  │ 全部通过
   │        └ frontend:lint（eslint / prettier）          │   ↓
   ├─ test  → backend:test（mvn test + JaCoCo）           │  image
   │                                                      │  ├─ aps-admin
   └─ package → bundle（jar + dist → tar.gz）─────────────┘  └─ aps-frontend
```

GitHub Actions 中 job 并行执行（`backend-package`、`backend-test`、`static-analysis`、`frontend-build`），镜像 job 通过 `needs` 等待全部前置 job 成功；GitLab CI 用 stage 串行表达同样的依赖关系。

## 3. 触发条件

### GitHub Actions

| 事件 | 条件 | 行为 |
|---|---|---|
| `push` | 分支 `main` / `develop` | 全流水线；`main` 额外推送 GHCR 镜像 |
| `pull_request` | 目标分支 `main` / `develop` | 全流水线；镜像仅构建不推送 |
| `workflow_dispatch` | 手动 | 全流水线 |

- `concurrency`：同 ref 的旧流水线自动取消（`cancel-in-progress: true`）。
- 权限：`contents: read`、`packages: write`（推送 GHCR 必需）。

### GitLab CI

```yaml
workflow:
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
    - if: '$CI_COMMIT_BRANCH && $CI_OPEN_MERGE_REQUESTS'   # 已有 MR 时不再跑分支流水线
      when: never
    - if: '$CI_COMMIT_BRANCH'
    - if: '$CI_COMMIT_TAG'
```

- 任意分支、MR、tag 触发 `build/lint/test/package`；
- `image` 阶段仅在 **MR**（只构建）、**默认分支**与 **tag**（构建并推送）创建；
- 全局 `interruptible: true`，新提交可打断旧流水线。

## 4. Job 明细与产物

| 阶段 | GitHub job | GitLab job | 关键命令 | 产物 |
|---|---|---|---|---|
| 打包 | `backend-package` | `backend:build` | `mvn -B -ntp -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true package` | `aps-admin/target/*.jar` |
| 前端 | `frontend-build`（Node 20/22 矩阵） | `frontend:build`（Node 22） | `npm ci`、`npm run lint --if-present`、`npm run build` | `aps-frontend/dist` |
| 静态检查 | `static-analysis` | `backend:lint` / `frontend:lint` | `mvn -B -ntp -DskipTests verify`；`npm run lint --if-present`；`npm run format:check --if-present` | —（违规即失败） |
| 单测 | `backend-test` | `backend:test` | `mvn ... jacoco:prepare-agent test jacoco:report` | surefire 报告、`target/site/jacoco` |
| 归档 | 各 job 的 `upload-artifact` | `package:bundle` | `tar -czf aps-<sha>.tar.gz`（jar + dist） | GitHub Artifact / GitLab 归档包 |
| 镜像 | `docker-images` | `image:backend` / `image:frontend` | `docker build -f aps-admin/Dockerfile .`；`docker build -f aps-frontend/Dockerfile aps-frontend` | GHCR / GitLab Registry 镜像 |

要点：

- **打包与单测分离**：打包 job 用 `-DskipTests` 快速产出可运行 jar；单测 job 独立运行，失败不会阻断 jar 产出（但会阻断后续 `image`）。
- **静态检查为门禁**：违规直接让 job 失败，`docker-images` 依赖它，因此不合规代码无法进入镜像。
- **制品归档**：GitHub Artifact 保留 7 天（jar、dist、测试报告）；GitLab 额外生成 `aps-<sha>.tar.gz` 发布包，`expire_in: 1 week`。
- **覆盖率**：见 §8.3，当前为「命令行临时采集」，`pom.xml` 正式接入后简化。

## 5. 缓存策略

| 生态 | GitHub Actions | GitLab CI |
|---|---|---|
| Maven | `actions/setup-java` → `cache: maven`，`cache-dependency-path: '**/pom.xml'`（缓存 `~/.m2/repository`） | `cache.paths: [.m2/repository]`，`key.files: [pom.xml, aps-solver/pom.xml]`，`fallback_keys: [maven-fallback]` |
| npm | `actions/setup-node` → `cache: npm`，`cache-dependency-path: aps-frontend/package-lock.json`（缓存 `~/.npm`） | `cache.paths: [.npm]`，`key.files: [aps-frontend/package-lock.json]` |
| Docker 层 | Buildx `cache-from/to: type=gha,scope=aps-admin / aps-frontend` | 未启用跨 job 构建缓存（dind 下 `--cache-from` 无共享存储）；如需可迁移到 buildx + registry cache |

策略说明：

- 打包 job 使用 `pull-push` 写回缓存；只读的 lint/test job 使用 `policy: pull`，避免并发写同一 key 造成 cache 竞争。
- Maven 缓存 key 以**根 POM**为准：根 POM 变更（依赖/插件版本）即换 key，模块级 POM 变更由 Maven 自身增量解析覆盖。
- `npm ci` 严格依赖 `package-lock.json`，因此 lock 文件变更必然刷新缓存，且保证可复现构建。

## 6. 镜像设计

### 6.1 后端 `aps-admin/Dockerfile`

- 构建阶段：`eclipse-temurin:21-jdk`，Maven 3.9 从官方 `maven:3.9-eclipse-temurin-21` 镜像以 `COPY --from` 方式复制（不引入额外下载步骤）；先复制全部 POM 预热依赖缓存，再复制源码 `package`。
- Flyway 迁移：`aps-admin/pom.xml` 将仓库根目录 `db/migration` 作为 resources 打进 `classpath:db/migration`；Dockerfile 在 `mvn package` 前 `COPY db/migration db/migration`（只复制该目录）。因此镜像自带 `V1__init.sql` 等脚本，空库启动即可建表，**部署侧无需再挂载迁移目录或覆盖 `SPRING_FLYWAY_LOCATIONS`**。
- 运行阶段：`eclipse-temurin:21-jre-jammy`（glibc 基础镜像，兼容 OR-Tools 的 JNI 原生库；不使用 Alpine/musl）。
- 安全：`aps` 系统用户（uid/gid 1001）非 root 运行，仅暴露 8080。
- JVM：`JAVA_OPTS` 默认 `-XX:MaxRAMPercentage=75 -XX:+UseG1GC`，时区 `Asia/Shanghai`。
- 健康检查：`HEALTHCHECK` 调用 `GET http://127.0.0.1:8080/api/v1/health`（与 `HealthController` 一致）；`start-period=60s` 留给 Spring Boot + Flyway 启动。
- 配置：镜像内不含任何密钥；数据库等参数由部署环境注入环境变量（`SPRING_DATASOURCE_*` 等，具体由 P0-05 / compose 决定）。

### 6.2 前端 `aps-frontend/Dockerfile`

- 构建阶段 `node:22-alpine`：`npm ci` → `npm run build`（含 `vue-tsc --noEmit` 类型检查）。
- 运行阶段 `nginxinc/nginx-unprivileged:1.31-alpine`：以 **uid 101** 非 root 运行，`dist` 拷贝到 `/usr/share/nginx/html`，配置模板由镜像入口 `envsubst` 渲染。
- 监听端口：容器内为 **8080**（非特权镜像不能绑定 <1024 端口），`EXPOSE 8080`；宿主机端口映射必须指向容器 8080（如 `${APS_FRONTEND_PORT:-80}:8080`），健康检查也改为 `http://127.0.0.1:8080/healthz`。
- SPA 路由：`try_files $uri $uri/ /index.html` 兜底 `createWebHistory` 的深链路由。
- 接口代理：`/api/` → `${APS_BACKEND_URL}`，默认 `http://aps-backend:8080`（与 compose 服务名一致）；使用变量 + `resolver 127.0.0.11`，后端容器未启动时 nginx 仍可正常启动。
  - `127.0.0.11` 为 Docker 内置 DNS；迁移到 Kubernetes 等非 Docker 环境时，需改为对应 DNS/服务发现方式（或由 Ingress 直接承接 `/api`）。
- 健康检查：`GET /healthz`（容器 8080）返回 `200 ok`。
- `index.html` 不缓存，`/assets/` 长缓存（一年 + immutable），确保发版立即生效。
- 注意：前端 API 基址默认 `/api/v1`（相对路径），由 Nginx 同源代理，因此镜像**无需在构建期注入后端地址**；跨域部署时通过 `APS_BACKEND_URL` 调整。

### 6.3 镜像命名与版本策略

| 平台 | 仓库 | tag |
|---|---|---|
| GitHub | `ghcr.io/<owner>/mfg-aps-admin`、`ghcr.io/<owner>/mfg-aps-frontend` | `sha-<7位>`、`main`/`develop`（分支名）、`pr-<编号>`（仅构建）、`<semver>`（打 `v*` tag 时）、`latest`（默认分支） |
| GitLab | `$CI_REGISTRY_IMAGE/aps-admin`、`$CI_REGISTRY_IMAGE/aps-frontend` | `sha-<8位>`、`<分支 slug>`、`<CI_COMMIT_TAG>`、`latest`（默认分支） |

- 版本以 `sha-*` 作为不可变标识，语义化 tag（打 Git tag 时）用于里程碑发布，`latest` 仅跟随默认分支，禁止用于生产固定引用。
- 仅默认分支 / tag 推送镜像；PR/MR 只验证「镜像可构建」。

## 7. 所需 Secrets / Variables

### GitHub Actions

| 名称 | 必需 | 说明 |
|---|---|---|
| `GITHUB_TOKEN` | 自动 | `packages: write` 权限下推送 GHCR，无需手工创建 PAT |
| `HTTP_PROXY` / `HTTPS_PROXY` | 可选 | 仅自托管 Runner 需要内网代理时在 Runner 侧设置；**不要**把 `127.0.0.1:7890` 写进 workflow（云端 Runner 无此端口） |
| 外部镜像仓库账号 | 可选 | 若改用 Docker Hub 等，需新增 `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` 并调整 `docker/login-action` 与 `images` |

### GitLab CI

| 名称 | 必需 | 说明 |
|---|---|---|
| `CI_REGISTRY` / `CI_REGISTRY_IMAGE` / `CI_REGISTRY_USER` / `CI_REGISTRY_PASSWORD` | 自动 | GitLab 预置，用于推送到项目容器镜像仓库 |
| `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` | 可选 | 在项目或 Runner 的 CI/CD Variables 中配置；`127.0.0.1:7890` 仅适用于本机 Runner |
| 外部仓库凭据 | 可选 | 推送第三方 Registry 时作为 Masked Variable 注入 |

## 8. 与 P0-02（代码规范/静态检查）的对接点

> 本任务与 P0-02 并行开发。交付时 P0-02 的配置文件已落盘，CI 按实际路径与命令对接，无需再改动流水线；若后续 P0-02 调整绑定阶段或路径，保持「`mvn verify` 触发」即可。

### 8.1 后端 Checkstyle / PMD

- 现状（交付时）：根 `pom.xml` 已将 `maven-checkstyle-plugin:check` 与 `maven-pmd-plugin:check` 绑定到 **`verify`** 阶段，规则文件为 `config/checkstyle/checkstyle.xml`、`config/pmd/pmd-ruleset.xml`。
- CI 对接：静态检查 job 执行 `mvn -B -ntp -DskipTests verify`，违规即 job 失败并阻断镜像构建。
- 打包/单测 job 通过 `-Dcheckstyle.skip=true -Dpmd.skip=true` 跳过规则执行，保证职责分离（规则违规只由 lint job 报出）。

### 8.2 前端 ESLint / Prettier

- 现状（交付时）：`aps-frontend` 已有 `eslint.config.js`、`.prettierrc.json`、`.prettierignore`，`package.json` 已提供 `lint`、`lint:fix`、`format`、`format:check` 脚本，且 `package-lock.json` 与依赖声明一致（可用 `npm ci`）。
- CI 对接：调用 `npm run lint --if-present` 与 `npm run format:check --if-present`；`lint` 使用 `--max-warnings 0`，告警同样阻断。
- `--if-present` 仅为兼容尚未合并 P0-02 的早期分支：脚本存在时一定执行，不会静默跳过。

### 8.3 单元测试覆盖率

- 现状：`pom.xml` 未接入 JaCoCo，且各模块暂无 `src/test`，因此 surefire 暂无报告。
- CI 处理：以命令行方式临时采集（不改 POM）：
  ```bash
  mvn -B -ntp org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test \
                 org.jacoco:jacoco-maven-plugin:0.8.12:report
  ```
  报告输出到各模块 `target/site/jacoco/`，并作为 Artifact 归档；无测试时 JaCoCo 会跳过（不失败）。
- 后续：骨架/P0-02 在 POM 正式接入 `jacoco-maven-plugin` 后，把该命令简化为 `mvn -B -ntp test`，并可在 GitLab 中启用 `coverage_report`（`coverage_format: jacoco`，路径 `**/target/site/jacoco/jacoco.xml`）。

### 8.4 其他并行依赖

- P0-03（分支与 PR 门禁）：若分支模型改为 `feature/*` 等，需同步调整两份配置的 `branches` / `workflow.rules`。`CONTRIBUTING.md` 与 `docs/coding-standard.md` 已由并行任务落盘，本任务未修改。
- 部署编排（P0-06）：`deploy/docker-compose*.yml` 当前只有 PG/Redis/MinIO。接入应用服务时建议：
  - 服务名 `aps-backend`（镜像 `mfg-aps-admin`），容器端口 8080，可仅内网或发布到宿主机；
  - 服务名 `aps-frontend`（镜像 `mfg-aps-frontend`），容器端口 **8080**，宿主映射形如 `${APS_FRONTEND_PORT:-80}:8080`，`APS_BACKEND_URL=http://aps-backend:8080`；
  - 两者加入 `aps-net`；健康检查已内置，compose 可直接 `depends_on: condition: service_healthy`。
- M1-QA-02 一键栈（`deploy/docker-compose.yml`）随本次镜像修复需同步：可删除 `SPRING_FLYWAY_LOCATIONS: "filesystem:/app/db/migration"` 与其只读挂载（迁移脚本已进 jar）；前端 `ports` 与 `healthcheck` 的容器端口由 80 改为 8080。compose 与 `docs/deploy/docker-compose.md` 由 QA-02 维护，本任务未修改。

## 9. 本地复现

```powershell
# Windows（PowerShell 5.1+）
.\scripts\ci-build.ps1                                  # 后端 package + 前端 build
.\scripts\ci-build.ps1 -RunTests                        # 追加单测 + 覆盖率
.\scripts\ci-build.ps1 -RunStaticAnalysis               # 追加 Checkstyle/PMD + ESLint
.\scripts\ci-build.ps1 -Target frontend -UseProxy       # 走本机 127.0.0.1:7890
```

```bash
# Linux / macOS / Git Bash
./scripts/ci-build.sh
./scripts/ci-build.sh --tests --static
./scripts/ci-build.sh --target frontend --proxy
```

等价的原始命令：

```bash
# 后端
mvn -B -ntp -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true package
mvn -B -ntp test
mvn -B -ntp -DskipTests verify

# 前端
cd aps-frontend && npm ci && npm run lint --if-present && npm run build

# 镜像（需 Docker daemon）
docker build -f aps-admin/Dockerfile -t aps-admin:local .
docker build -f aps-frontend/Dockerfile -t aps-frontend:local aps-frontend

# 本地联调（前端 nginx 代理到宿主机后端）
docker run --rm -p 8080:8080 aps-admin:local
docker run --rm -p 8081:8080 -e APS_BACKEND_URL=http://host.docker.internal:8080 aps-frontend:local
```

代理：本机拉依赖/镜像统一走 clash（`127.0.0.1:7890`）；脚本通过 `-UseProxy` / `--proxy` 设置 `HTTP_PROXY`、`npm_config_proxy` 与 `MAVEN_OPTS`，Docker 需在 Docker Desktop 中单独配置代理。

## 10. 验证记录与已知限制

- YAML 语法：`.github/workflows/ci.yml`、`.gitlab-ci.yml` 已通过 PyYAML（Python 3.14.6 / PyYAML 6.0.3）解析，并额外校验无重复键、job 依赖引用与必填字段。
- PowerShell 脚本：`scripts/ci-build.ps1` 已通过 PowerShell AST 解析（0 错误）。脚本以 **UTF-8 with BOM** 保存，以兼容 Windows PowerShell 5.1 对中文的解析。
- Bash 脚本：`scripts/ci-build.sh` 已通过 Git Bash `bash -n` 语法检查。
- 前端可复现性：已核对 `aps-frontend/package.json` 与 `package-lock.json` 的依赖声明一致（`npm ci` 可用）。
- Dockerfile 静态检查：Hadolint v2.15.1 检查 `aps-admin/Dockerfile` 与 `aps-frontend/Dockerfile` 均通过（exit 0）；`DL3008`（apt 版本锁定）按注释显式豁免，理由是基础镜像 curl 版本随上游滚动。
- 结构校验脚本：确认迁移脚本 `COPY` 早于 `mvn package`、前端模板/`EXPOSE`/`HEALTHCHECK` 端口均为 8080、`APS_BACKEND_URL` 默认值与 compose 服务名 `aps-backend` 一致、后端 `USER 1001:1001` 非 root。
- 未验证（本机环境限制）：
  - 本机无 `java` / `mvn` / `node`，未实际执行 Maven、npm 构建与 lint；
  - Docker CLI 存在（v29.8.2）但 daemon 未启动（`dockerDesktopLinuxEngine` 不可达），`docker build` 已实际尝试并因守护进程缺失失败，未执行 `docker run`，镜像可构建性仅由配置与 CI 保证；
  - 未执行 GitHub Actions / GitLab CI 实跑（本地无 Git 仓库与 Runner）。
- Node 矩阵按需求固定为 20 / 22；如需跟随最新 LTS（Node 24+），在 `ci.yml` 的 matrix 与 `NODE_VERSION` 中调整即可。
- 无 `mvnw`：流水线使用 Runner/镜像内 Maven 3.9（GitHub Runner 预装，GitLab 用 `maven:3.9-eclipse-temurin-21`）。若后续提交 Maven Wrapper，可把命令替换为 `./mvnw`。
- 前端镜像使用 `nginxinc/nginx-unprivileged:1.31-alpine`（uid 101 非 root），容器端口因此为 **8080**；改用其他 nginx 变体或回退到 `nginx:alpine` 时需同步 `listen`、`EXPOSE`、健康检查与宿主映射（M1-QA-02 的 `deploy/docker-compose.yml` 需相应调整）。
