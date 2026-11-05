# M3-QA-02 MRP 缓存并发修复：single-flight + stale-while-revalidate

修复后（100 并发）：**混合 P95 402.18 ms / MRP 到期瞬间 P95 410.45 ms / 错误率 0.00% / 超时 0 次**。目标达成。

---

## 1. 结论

- TTL 到期瞬间不再有 100 路并发回源：同一 key 只执行 1 次全量齐套分析，其余请求共享同一个结果。
- TTL 到期后先返回旧值，由唯一后台线程刷新；旧值超过可配置上限后，请求才必须等待新值。
- 写库存主动失效后，下一个请求一定拿到新值；失效前已在途的旧计算也不会把旧结果写回缓存。
- 100 并发复测：混合 P95 从 **462.79 ms** 降到 **402.18 ms**，MRP P95 从 **27,462.46 ms** 降到 **378.57 ms**，54 次客户端超时降到 **0**，错误率从 **0.71%** 降到 **0.00%**。
- 本次只改缓存实现、缓存配置、服务调用点与测试；MRP 业务计算逻辑、契约、OpenAPI、迁移、pom 均未改动。

---

## 2. 实现

### 2.1 single-flight（同一 key 同时只算一次）

实现位置：[MrpSuggestionCache.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/mrp/MrpSuggestionCache.java#L132-L221)。

- `ConcurrentHashMap<Key, CompletableFuture<List<...>>> flights` 记录在途计算；`putIfAbsent` 选出唯一 leader。
- leader 在当前请求线程执行加载并落缓存；其余请求 `await` 同一个 `CompletableFuture`，拿到的是同一个结果。
- 不同 key 之间不串行，避免把整个 MRP 端点做成全局单线程。
- 等待有界：默认 `10,000 ms`，由 `aps.mrp.suggestion-cache-wait-millis` 配置。
- 超时行为：抛 `BizException(ErrorCode.SERVICE_UNAVAILABLE)`，业务码 **50300**，HTTP **503**；请求快速失败，不无限挂起。leader 的计算不会被取消，完成后结果仍会进入缓存，后续请求直接命中。
- 冷启动 100 并发实测：100 个请求全部成功，P50 2,232 ms、P95 2,245 ms、max 2,247 ms，相当于“1 次约 2.2 s 的分析 + 99 个等待”，而不是 100 次分析。单测用计数器确认计算次数为 1。

### 2.2 stale-while-revalidate（旧值先返回，后台单线程刷新）

实现位置：[MrpSuggestionCache.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/mrp/MrpSuggestionCache.java#L229-L275)。

- 缓存条目记录 `expiresAt` 与 `staleDeadline = expiresAt + staleMax`。
- `now < expiresAt`：正常命中。
- `expiresAt <= now < staleDeadline`：**立即返回旧值**，同时用 `flights.putIfAbsent` 保证只提交一个后台刷新任务；刷新线程为单线程守护线程 `mrp-suggestion-refresh`，不会并发刷新多个 key。
- `now >= staleDeadline`：旧值不可再用，走 single-flight 等待新值。
- 后台线程执行前设置 `TenantContext`，结束后清理，保证多租户隔离。
- 刷新失败时旧值保留到 `staleDeadline`，期间请求继续返回旧值并重试刷新；超过上限后请求改走同步加载（single-flight），失败会显式返回错误，不会永久服务旧值。

### 2.3 失效优先（写库存后不得返回旧值）

实现位置：[MrpSuggestionCache.invalidate](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/mrp/MrpSuggestionCache.java#L156-L168)。

- 每个租户维护一个 epoch；`invalidate` 递增 epoch，并移除该租户的缓存条目与在途 flight。
- 计算完成时重新校验 epoch；若期间发生过失效，结果直接丢弃，不写回缓存。
- 失效后的下一个请求不会加入失效前的旧 flight，而是开启新计算，因此不会拿到旧值。
- 复测证据：预热为空结果（`total=0`）→ `PUT /inventory` 把 `MAT-RAW-951` 置 0 → 立即再查耗时 2,370 ms、返回 9,941 B 且包含 `MAT-RAW-951` 缺料；第二次查询 1.28 ms 返回同一新值。恢复库存后同理。

### 2.4 配置

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `aps.mrp.suggestion-cache-ttl-seconds` | `APS_MRP_SUGGESTION_CACHE_TTL_SECONDS` | 45 | 仅决定何时进入 stale 窗口；本次**没有**用调大 TTL 作为修复手段 |
| `aps.mrp.suggestion-cache-stale-max-seconds` | `APS_MRP_SUGGESTION_CACHE_STALE_MAX_SECONDS` | 300 | TTL 到期后旧值最长可继续服务的时长；超过则必须等新值 |
| `aps.mrp.suggestion-cache-wait-millis` | `APS_MRP_SUGGESTION_CACHE_WAIT_MILLIS` | 10,000 | 非 leader 请求等待新值的上限；超时返回 50300 / HTTP 503 |

配置位置：[application.yml](file:///d:/tools/smart-aps-agent/aps-admin/src/main/resources/application.yml#L71-L78)。

---

## 3. 单元测试清单

文件：[MrpSuggestionCacheTest.java](file:///d:/tools/smart-aps-agent/aps-service/src/test/java/com/hengce/aps/service/mrp/MrpSuggestionCacheTest.java)。

| # | 测试 | 覆盖点 | 结果 |
|---|---|---|---|
| 1 | `concurrentRequestsForSameKeyComputeOnlyOnce` | 20 线程同一 key；loader 计数器必须为 1，所有请求拿到同一结果 | 通过 |
| 2 | `expiredEntryServesStaleAndRefreshesOnlyOnce` | TTL 45 s 后第 46 s 首个请求返回旧值；刷新期间 10 个并发仍返回旧值；后台刷新计数只增加 1；放开后读到新值 | 通过 |
| 3 | `invalidateEnsuresNextRequestGetsNewValue` | 先缓存 `RM-OLD`，`invalidate` 后下一个请求必须重新计算并返回 `RM-NEW` | 通过 |
| 4 | `beyondStaleMaxWaitsForNewValueInsteadOfServingStale` | 推进 346 s（45 + 300 + 1）后不得返回旧值，必须等待新值 | 通过 |
| 5 | `waiterIsBoundedWhenComputationExceedsWaitTimeout` | 等待上限 100 ms、leader 计算 500 ms；等待方在 500 ms 内收到 50300，不随计算无限挂起 | 通过 |

定向测试（缓存 + 服务 + 库存）：**20/20 通过**。新增缓存测试：**5/5 通过**。

全量回归：`APS_TEST_DB_ENABLED=true`，自建测试库 `aps_test_sf`，12 个模块、101 个测试类、**566 个测试，Failures=0 / Errors=0 / Skipped=0**，`BUILD SUCCESS`。测试库跑完已 `DROP`。

---

## 4. 复测方法与数据量

- 应用：当前源码编译后的 classpath（遵守“不 `mvn package`”约束），独立端口 **18101**；profiles=dev，限流关闭，API Key 读写密钥；`aps.mrp.suggestion-cache-ttl-seconds=45`（与修复前同口径）。
- 机器：8 逻辑核；本地 PostgreSQL 55432；JVM `-Xms512m -Xmx4g`。
- 数据量：500 订单、3,000 任务、6,001 条审计日志、10 个 BOM/组件；`POST /mrp/analysis` 返回 `status=FULL`、500 单、标签齐全，与修复前一致。
- 压测：闭包式（closed-loop），8 接口 **1:1 轮转**（`orders_p20 / resources_p50 / gantt_order / gantt_resource / audit_logs_p20 / plans_list / mrp_suggestions / materials_p50`），每档 20 s，梯度 20 / 50 / 100，客户端超时 30 s。
- 到期档：先预热 1 次 MRP 并记录完成时刻，等待到 TTL 到期前 5 s，再跑 100 并发 20 s，确保窗口内跨过 45 s TTL 边界；另有一轮混合 100 档在开始后约 45 s 自然跨过到期点。
- 客户端校准：本机没有 Node 运行时，最终采用 JDK 21 原始 Socket keep-alive 客户端（与 Node 版同为 closed-loop、同接口集合、同 20 s/档、同 30 s 超时、同数据量）。校准过程中 Python/`HttpClient` 封装在 100 并发下自身 CPU/内存分配成为瓶颈（混合 P95 分别到 746 ms / 622 ms），因此不作为最终数据；最终数据与 Node 客户端开销同量级，并用服务端日志交叉验证。
- 卫生：压测库 `aps_verify_perf3` 与回归库 `aps_test_sf` 均已 `DROP`；18101 已停止并释放，端口净变化 **0**；未连接/修改 `aps_local`、`aps_local_2`。

---

## 5. 复测结果

### 5.1 修复前后对照（100 并发，跨 TTL 到期档）

| 指标 | 修复前 | 修复后 | 变化 |
|---|---:|---:|---:|
| 混合 P95 | 462.79 ms | **402.18 ms** | -13.1% |
| 混合 P99 | 689.01 ms | 482.40 ms | -30.0% |
| MRP P95 | 27,462.46 ms | **378.57 ms** | -98.6% |
| MRP 到期瞬间 P95 | 27,462.46 ms（该档即到期档） | **410.45 ms** | -98.5% |
| 错误率 | 0.71%（54 / 7,575） | **0.00%（0 / 9,515）** | -0.71 pp |
| 客户端 30 s 超时 | 54 | **0** | -54 |
| 混合最大耗时 | 30,016.11 ms | 590.20 ms | -98.0% |
| 吞吐 | 164.4 RPS | 472.7 RPS | +187.5% |
| MRP 慢请求（>1 s） | 100（该档 946 条 MRP） | **0**（该档 1,189 条 MRP） | -100% |
| 冷调用 MRP P50 | 1,945.62 ms | 1,959.75 ms | +0.7%（同一计算量，属噪声） |
| 热调用 MRP P50 | 1.95 ms | 0.82 ms | -58.0% |

补充：修复前“未跨 TTL”的首轮 100 档混合 P95 为 407.31 ms；修复后 100 档**本身就跨过了 TTL 到期点**，混合 P95 仍为 402.18 ms，低于修复前未到期档的水平。这说明差异不是靠“避开到期窗口”取得的。

### 5.2 修复后梯度

| 并发 | 请求数（20 s） | RPS | 混合 P50 | 混合 P95 | 混合 P99 | 错误率 | MRP P95 | MRP >1 s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 20 | 10,357 | 516.6 | 33.33 ms | 88.02 ms | 117.37 ms | 0% | 57.74 ms | 0 |
| 50 | 9,940 | 495.2 | 96.62 ms | 194.97 ms | 228.78 ms | 0% | 179.54 ms | 0 |
| **100** | **9,515** | **472.7** | **195.82 ms** | **402.18 ms** | **482.40 ms** | **0%** | **378.57 ms** | **0** |

100 并发分接口：

| 接口 | P50 | P95 | P99 | 错误率 | 平均响应体 |
|---|---:|---:|---:|---:|---:|
| orders_p20 | 202.96 ms | 401.79 ms | 486.24 ms | 0% | 7,667 B |
| resources_p50 | 197.61 ms | 397.40 ms | 479.76 ms | 0% | 16,594 B |
| gantt_order（3,000 任务） | 225.42 ms | 429.20 ms | 515.36 ms | 0% | 1,330,622 B |
| gantt_resource（3,000 任务） | 216.11 ms | 421.78 ms | 502.06 ms | 0% | 1,089,845 B |
| audit_logs_p20 | 176.50 ms | 381.54 ms | 418.72 ms | 0% | 3,294 B |
| plans_list | 179.60 ms | 384.94 ms | 457.23 ms | 0% | 139 B |
| **mrp_suggestions** | **173.27 ms** | **378.57 ms** | **436.37 ms** | **0%** | 139 B |
| materials_p50 | 193.82 ms | 400.68 ms | 495.56 ms | 0% | 3,446 B |

### 5.3 强制到期档（100 并发 20 s，窗口内跨 TTL）

| 指标 | 数值 |
|---|---:|
| 请求数 / RPS | 8,572 / 424.0 |
| 混合 P50 / P95 / P99 | 216.96 / **470.97** / 525.29 ms |
| 混合最大耗时 | 623.48 ms |
| MRP P50 / P95 / P99 | 186.63 / **445.88** / 507.49 ms |
| MRP 最大耗时 | 585.66 ms |
| 到期后 2 s 窗口 MRP（n=113） | P50 180.35 ms / **P95 397.38 ms** / max 511.30 ms |
| 错误率 / 超时 / MRP >1 s | **0% / 0 / 0** |

混合 P95 470.97 ms 仍 `< 500 ms`，但余量只有约 29 ms；这是 8 核单实例、100 并发、1.33 MB Gantt 响应体共同作用下的正常排队水平，不是回源尖峰。

### 5.4 到期瞬间为什么没有尖峰

服务端日志按窗口统计（`durationMs` 为请求线程实际处理时长）：

| 窗口 | 服务端样本 | 服务端 P95 | 服务端 MRP P95 | 服务端 >1 s | 服务端错误 |
|---|---:|---:|---:|---:|---:|
| 混合 100 档（跨 TTL） | 9,467 | 236 ms | 225 ms | **0** | 0 |
| 强制到期 100 档 | 8,514 | 272 ms | 253 ms | **0** | 0 |

整个压测中服务端 >1 s 的 MRP 请求只出现在冷启动/冷并发阶段（单次分析 2 s 或 99 个等待者），到期窗口内为 0。即：到期瞬间没有任何请求线程执行全量分析，分析只在 `mrp-suggestion-refresh` 后台线程执行一次。

### 5.5 冷并发（同一 key 100 并发）

| 指标 | 数值 |
|---|---:|
| 请求数 / 成功 | 100 / 100 |
| P50 / P95 / P99 | 2,232.00 / 2,245.12 / 2,246.27 ms |
| Max | 2,246.54 ms |
| 整体完成时间 | 2,249 ms |
| 错误率 | 0% |

100 个冷请求在同一 key 上只产生 1 次全量分析（单测计数器确认），其余 99 个等待该结果；没有再出现 100 路并发各自分析导致的 27 s 级排队。

### 5.6 缓存失效（写库存后立即回显）

| 步骤 | 状态 | 耗时 | 响应 |
|---|---|---:|---|
| 预热查询（未失效） | 200 | 1.83 ms | 139 B，`total=0` |
| `PUT /inventory` 将 `MAT-RAW-951` 置 0 | 200 | 8.18 ms | 110 B，`accepted=1, rejected=0` |
| **立即再查同入参** | 200 | **2,370.31 ms** | **9,941 B，包含 `MAT-RAW-951` 缺料** |
| 再次查询（已重新缓存） | 200 | 1.28 ms | 9,941 B，同一新值 |
| `PUT /inventory` 恢复 `onHandQty=100000` | 200 | 5.93 ms | 110 B，`accepted=1, rejected=0` |
| **立即再查同入参** | 200 | **2,452.01 ms** | 139 B，`total=0`（缺料消失） |

失效后的首个请求重新计算（约 2.4 s），第二个请求命中新值（1.28 ms）；没有返回失效前的旧值。

---

## 6. “旧值最长可用时长”最终取值与理由

**最终取值：TTL 到期后最多 300 秒；从写入缓存起算，旧值最长可用 `45 + 300 = 345` 秒。** 配置项 `aps.mrp.suggestion-cache-stale-max-seconds`（环境变量 `APS_MRP_SUGGESTION_CACHE_STALE_MAX_SECONDS`）。

理由：

1. **写路径已主动失效，stale 窗口只影响“无写入的纯读空闲”场景。** 订单、库存、BOM 等会改变 MRP 结果的写操作都会调用 `invalidate`，因此 300 s 不会放大写后脏读的窗口；写后下一个请求已由单测和复测证明拿到新值。
2. **给后台刷新留出失败重试余量。** 单次全量分析约 2 s，300 s 约等于 6.7 个 TTL，可覆盖短时数据库抖动/刷新失败；超过 300 s 后请求必须等新值，避免无限期返回旧值。
3. **上限是硬边界，不是“尽量”。** 超过 `expiresAt + 300 s` 后一律不再返回旧值，符合“旧值最长可用多久”的可验证定义。
4. **300 s 是可用性与新鲜度的折中。** 45 s 过短：一次刷新就占 2 s，刷新失败时很快进入阻塞等待；600 s 过长：对建议类数据没有必要。若部署方更看重新鲜度，可通过环境变量下调到 60–120 s，无需改代码。

---

## 7. 卫生与边界

- 未修改 MRP 业务计算逻辑：`computeSuggestions` 中的过滤、排序、映射与修复前一致，只是把“写缓存”从服务层移到缓存层的 single-flight leader 中（见 [MrpSuggestionService.java](file:///d:/tools/smart-aps-agent/aps-service/src/main/java/com/hengce/aps/service/mrp/MrpSuggestionService.java#L97-L116)）。
- 未修改契约、OpenAPI、数据库迁移与 `pom.xml`。
- 未 commit / push；未执行 `mvn package`。
- 全量回归 `Skipped=0`；定向测试与新增测试全部通过。
- 单独对 `aps-service` 跑 Checkstyle：新增 `MrpSuggestionCache.java` 无告警；仓库中 `ScheduleProblemAssembler.java` 有 2 个既存违规（重复 import、注释前空行），与本次改动无关。
- 已知边界：本报告是 8 核单实例 + 本地 PostgreSQL 的结果；未做多实例/集群下的分布式 single-flight。300 s 旧值上限、10 s 等待上限均已在配置中暴露，部署时可按 SLA 调整。
