package com.hengce.aps.service.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository;
import com.hengce.aps.infrastructure.persistence.schedule.ConstraintJustificationRepository.JustificationRow;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository;
import com.hengce.aps.infrastructure.persistence.schedule.SolveTaskRepository.SolveTaskRecord;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScheduleSolution;
import com.hengce.aps.solver.api.SolveConfig;
import com.hengce.aps.solver.api.SolveHandle;
import com.hengce.aps.solver.api.SolveRequest;
import com.hengce.aps.solver.api.SolverEngine;
import com.hengce.aps.solver.api.task.SolveProgress;
import com.hengce.aps.solver.api.task.SolveRetryReasons;
import com.hengce.aps.solver.api.task.SolveTaskDetail;
import com.hengce.aps.solver.api.task.SolveTaskStatus;
import com.hengce.aps.service.mrp.MrpBlockingChecker;
import com.hengce.aps.service.mrp.MrpBlockingDecision;
import com.hengce.aps.service.schedule.portfolio.SolveQueueContext;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 求解任务生命周期服务（M2-SE-03）。
 *
 * <p>职责：受理异步求解、维护 {@code PENDING → RUNNING → 终态}、推送进度快照、
 * 处理取消与超时、引擎不可用/异常降级。执行本身委托给 {@link ScheduleService}，
 * 本类不复制问题装配与结果落库逻辑。</p>
 *
 * <p>并发约束（A1 起）：工作线程数与求解槽位对齐（默认 = {@code aps.solve.max-concurrent-solvers}
 * 解析结果，可用 {@code aps.solve.worker-concurrency} 收窄），因此不同场景可同时有多个
 * {@code RUNNING}；队列仍有界，队列满时按引擎不可用降级为 FAILED 并写 {@code engineFallback} 标记。
 * 从提交到真正开始执行的等待写入 {@code metrics.queueWaitMs}，并累加进
 * {@code metrics.portfolio.slotWaitMs}。取消优先调用引擎 {@code terminateEarly}，
 * 引擎未实现异步句柄时以 {@link Future#cancel(boolean)} 线程中断兜底，并在落库前通过停止信号丢弃结果。</p>
 */
@Service
public class SolveTaskLifecycleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolveTaskLifecycleService.class);

    /**
     * JSON 序列化器。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 进度推送间隔（毫秒）。
     */
    private static final long PROGRESS_TICK_MILLIS = 500L;

    /**
     * 非状态变化进度帧的最小发布间隔（C20：≤1 帧/秒）。
     */
    private static final long PROGRESS_MIN_INTERVAL_MILLIS = 1000L;

    /**
     * M2 唯一降级模式。
     */
    private static final String FALLBACK_MODE = "HARD_ONLY";

    /**
     * 求解任务持久层。
     */
    private final SolveTaskRepository solveTaskRepository;

    /**
     * 归因持久层。
     */
    private final ConstraintJustificationRepository justificationRepository;

    /**
     * 排产执行服务。
     */
    private final ScheduleService scheduleService;

    /**
     * 进度发布器。
     */
    private final SolveProgressPublisher progressPublisher;

    private final MrpBlockingChecker mrpBlockingChecker;

    /**
     * 工作线程池。
     */
    private final ThreadPoolExecutor worker;

    /**
     * 进度与看门狗调度器。
     */
    private final ScheduledExecutorService ticker;

    /**
     * 运行中任务：taskId → 运行上下文。
     */
    private final ConcurrentMap<Long, RunningTask> running = new ConcurrentHashMap<>();

    /**
     * 是否异步执行。
     */
    private final boolean asyncEnabled;

    /**
     * 是否写入引擎降级标记。
     */
    private final boolean fallbackEnabled;

    /**
     * 超时宽限秒数（用于看门狗，避免与引擎内部超时竞争）。
     */
    private final int timeoutGraceSeconds;

    /**
     * C37 重试时钟（可注入，测试不真睡）。
     */
    private final SolveRetryClock retryClock;

    /**
     * C37 首次之外的最大重试次数。
     */
    private final int retryMaxRetries;

    /**
     * C37 固定重试间隔（秒）。
     */
    private final long retryDelaySeconds;

    /**
     * 求解后置钩子调用链（A0 接缝；空实现时为空链）。
     */
    private final SolvePostProcessorChain postProcessorChain;

    /**
     * 工作线程并发度（与求解槽位对齐），仅用于日志/诊断。
     */
    private final int workerConcurrency;

    /**
     * 构造生命周期服务。
     *
     * @param solveTaskRepository 求解任务持久层
     * @param justificationRepository 归因持久层
     * @param scheduleService 排产执行服务
     * @param progressPublisher 进度发布器
     * @param mrpBlockingChecker MRP 齐套阻塞判定（C25：全量与增量口径统一）
     * @param asyncEnabled 是否异步执行
     * @param fallbackEnabled 是否写降级标记
     * @param queueCapacity 有界队列容量
     * @param timeoutGraceSeconds 超时宽限秒数
     * @param retryClock 重试时钟
     * @param retryMaxRetries 首次之外的最大重试次数
     * @param retryDelaySeconds 固定重试间隔（秒）
     * @param postProcessorChain 求解后置钩子链
     * @param maxConcurrentSolvers 求解槽位数；0 表示按 CPU 自动解析
     * @param workerConcurrency 工作线程并发度；0 表示与槽位对齐
     */
    public SolveTaskLifecycleService(SolveTaskRepository solveTaskRepository,
                                     ConstraintJustificationRepository justificationRepository,
                                     ScheduleService scheduleService,
                                     SolveProgressPublisher progressPublisher,
                                     MrpBlockingChecker mrpBlockingChecker,
                                     @Value("${aps.solve.async-enabled:true}") boolean asyncEnabled,
                                     @Value("${aps.solve.fallback-enabled:true}") boolean fallbackEnabled,
                                     @Value("${aps.solve.queue-capacity:32}") int queueCapacity,
                                     @Value("${aps.solve.timeout-grace-seconds:5}") int timeoutGraceSeconds,
                                     SolveRetryClock retryClock,
                                     @Value("${aps.solve.retry.max-retries:1}") int retryMaxRetries,
                                     @Value("${aps.solve.retry.delay-seconds:30}") long retryDelaySeconds,
                                     SolvePostProcessorChain postProcessorChain,
                                     @Value("${aps.solve.max-concurrent-solvers:0}") int maxConcurrentSolvers,
                                     @Value("${aps.solve.worker-concurrency:0}") int workerConcurrency) {
        this.solveTaskRepository = solveTaskRepository;
        this.justificationRepository = justificationRepository;
        this.scheduleService = scheduleService;
        this.progressPublisher = progressPublisher;
        this.mrpBlockingChecker = mrpBlockingChecker;
        this.asyncEnabled = asyncEnabled;
        this.fallbackEnabled = fallbackEnabled;
        this.timeoutGraceSeconds = Math.max(0, timeoutGraceSeconds);
        this.retryClock = retryClock == null ? new SystemSolveRetryClock() : retryClock;
        this.retryMaxRetries = Math.max(0, retryMaxRetries);
        this.retryDelaySeconds = Math.max(0L, retryDelaySeconds);
        this.postProcessorChain = postProcessorChain == null
                ? new SolvePostProcessorChain(List.of()) : postProcessorChain;
        // A1：工作线程与求解槽位对齐；槽位口径与 TimefoldSolverAdapter 一致
        // （显式配置优先，否则 max(2, min(cores-2, 4))）。显式 worker 并发不得高于槽位，
        // 避免“任务都跑起来但全部卡在适配器信号量上”的假并发。
        int slots = maxConcurrentSolvers > 0
                ? maxConcurrentSolvers
                : Math.max(2, Math.min(Runtime.getRuntime().availableProcessors() - 2, 4));
        int workers = workerConcurrency > 0 ? Math.min(workerConcurrency, slots) : slots;
        this.workerConcurrency = Math.max(1, workers);
        AtomicInteger workerSeq = new AtomicInteger();
        this.worker = new ThreadPoolExecutor(this.workerConcurrency, this.workerConcurrency,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "aps-solve-worker-" + workerSeq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "aps-solve-progress");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 异步受理求解任务；相同幂等键返回既有任务。
     *
     * @param request 求解请求
     * @param idempotencyKey 幂等键，可空
     * @return 任务详情（PENDING 或已进入 RUNNING）
     */
    public SolveTaskDetail submit(SolveRequest request, String idempotencyKey) {
        return submit(request, idempotencyKey, true, null);
    }

    /**
     * 受理求解任务，可选择是否重复执行 MRP 齐套阻塞判定。
     *
     * @param request 求解请求
     * @param idempotencyKey 幂等键，可空
     * @param checkMrpBlocking 是否在受理阶段执行齐套阻塞判定；增量入口已判定过可传 {@code false}
     * @return 任务详情（PENDING 或已进入 RUNNING）
     */
    public SolveTaskDetail submit(SolveRequest request, String idempotencyKey, boolean checkMrpBlocking) {
        return submit(request, idempotencyKey, checkMrpBlocking, null);
    }

    /**
     * 在当前调用线程内同步执行求解，用于必须拿到终态结果的 Agent what-if。
     *
     * <p>复用 {@link #submit(SolveRequest, String, boolean, Boolean)} 的同一受理/执行/落库路径，
     * 不复制问题装配、求解与结果落库逻辑；幂等语义不变。若幂等键命中的是历史异步任务，
     * 由调用方检查状态后再决定是否可用。</p>
     *
     * @param request 求解请求
     * @param idempotencyKey 幂等键，可空
     * @param checkMrpBlocking 是否执行齐套阻塞判定
     * @return 已进入终态的任务详情
     */
    public SolveTaskDetail submitSynchronous(SolveRequest request, String idempotencyKey,
                                             boolean checkMrpBlocking) {
        return submit(request, idempotencyKey, checkMrpBlocking, Boolean.TRUE);
    }

    private SolveTaskDetail submit(SolveRequest request, String idempotencyKey, boolean checkMrpBlocking,
                                   Boolean forceSynchronous) {
        if (request == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        UUID tenantId = TenantContext.getTenantId();
        EngineRoute route = scheduleService.route(request);
        ScheduleProblemAssembler.validatePinnedOrderNos(request.orderNos(), request.parameters());
        scheduleService.requireExistingOrderNos(request);
        SolveConfig config = scheduleService.toConfig(request, route.effectiveEngine());
        UUID sandboxId = request.sandboxId() == null ? ScheduleProblem.DEFAULT_SANDBOX_ID : request.sandboxId();
        if (request.sandboxId() != null && !solveTaskRepository.sandboxExists(tenantId, sandboxId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + sandboxId);
        }
        if (checkMrpBlocking) {
            MrpBlockingDecision decision = mrpBlockingChecker.evaluate(sandboxId, request.orderNos(), true);
            if (decision.blocked()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, decision.message());
            }
        }
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (key != null) {
            Optional<SolveTaskRecord> existing = solveTaskRepository.findByIdempotencyKey(tenantId, key);
            if (existing.isPresent()) {
                return detail(existing.get());
            }
        }

        // 订单范围必须随配置固化：缺省增量重排据此判定“当前版本”范围，不能只存引擎参数。
        List<String> scopeOrderNos = scheduleService.effectiveOrderNos(request);
        long taskId;
        try {
            taskId = solveTaskRepository.create(tenantId, sandboxId, request.strategyId(),
                    engineCode(route.effectiveEngine()),
                    ScheduleService.toConfigJson(config, scopeOrderNos), key, "PENDING");
        } catch (DuplicateKeyException exception) {
            return solveTaskRepository.findByIdempotencyKey(tenantId, key)
                    .map(this::detail)
                    .orElseThrow(() -> new BizException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "幂等键冲突，请使用新的 Idempotency-Key"));
        } catch (DataIntegrityViolationException exception) {
            if (isSandboxForeignKeyViolation(exception)) {
                throw new BizException(ErrorCode.NOT_FOUND, "场景不存在: " + sandboxId);
            }
            throw exception;
        }

        progressPublisher.publish(new SolveProgress(taskId, SolveTaskStatus.PENDING.name(), 0.0,
                "QUEUED", null, 0L, null, null, "已受理，等待求解"));
        RunningTask run = new RunningTask(taskId, tenantId, route, config);
        running.put(taskId, run);
        if (!asyncEnabled || Boolean.TRUE.equals(forceSynchronous)) {
            runSynchronously(run, request);
            return detail(taskId);
        }
        try {
            run.future = worker.submit(() -> executeTask(run, request));
        } catch (RejectedExecutionException exception) {
            running.remove(taskId);
            finishFallback(run, "SOLVER_UNAVAILABLE", "求解队列已满，请稍后重试");
        }
        return detail(taskId);
    }

    /**
     * 查询任务详情；运行中优先合并内存中的最新进度。
     *
     * @param taskId 求解任务主键
     * @return 任务详情
     */
    public SolveTaskDetail detail(long taskId) {
        UUID tenantId = TenantContext.getTenantId();
        SolveTaskRecord record = solveTaskRepository.findById(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "求解任务不存在: " + taskId));
        return detail(record);
    }

    /**
     * 取消任务；已终态返回 40901，重复取消幂等返回当前详情。
     *
     * @param taskId 求解任务主键
     * @return 任务详情
     */
    public SolveTaskDetail cancel(long taskId) {
        UUID tenantId = TenantContext.getTenantId();
        SolveTaskRecord record = solveTaskRepository.findById(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "求解任务不存在: " + taskId));
        SolveTaskStatus status = SolveTaskStatus.fromNullable(record.status());
        if (status == SolveTaskStatus.CANCELLED) {
            return detail(record);
        }
        if (status.isTerminal()) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务已结束（" + status + "），无法取消");
        }

        RunningTask run = running.get(taskId);
        if (run != null) {
            run.cancelled.set(true);
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("phase", "CANCELLED");
        metrics.put("progress", 1.0);
        metrics.put("cancelled", true);
        int updated = solveTaskRepository.finish(tenantId, taskId, SolveTaskStatus.CANCELLED.name(),
                null, null, toJson(metrics), "用户取消求解任务");
        if (updated > 0) {
            firePostProcessors(taskId, tenantId, SolveTaskStatus.CANCELLED.name(), null, null,
                    metrics, "用户取消求解任务", null, null);
            publishTerminal(taskId, SolveTaskStatus.CANCELLED, null, metrics, "用户取消求解任务");
        }
        if (run != null) {
            scheduleService.terminateEarly(run.route.effectiveEngine(),
                    new SolveHandle(run.handleId, run.route.effectiveEngine(), SolveTaskStatus.CANCELLED.name()));
            if (run.future != null) {
                run.future.cancel(true);
            }
            // A0：退避期间工作线程已释放，取消必须同时停掉重试定时器与进度 ticker，
            // 否则定时器到期会把已取消的任务重新拉回工作线程。
            run.active.set(false);
            cancelFuture(run.tickerFuture);
            cancelFuture(run.watchdogFuture);
            cancelFuture(run.retryFuture);
            running.remove(taskId);
        }
        return detail(taskId);
    }

    /**
     * 读取归因并重放 task_ids（M2-SE-05）。
     *
     * @param taskId 求解任务主键
     * @return 归因行
     */
    public List<JustificationRow> justifications(long taskId) {
        UUID tenantId = TenantContext.getTenantId();
        solveTaskRepository.findById(tenantId, taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "求解任务不存在: " + taskId));
        return justificationRepository.findBySolveTaskId(tenantId, taskId);
    }

    private void runSynchronously(RunningTask run, SolveRequest request) {
        UUID callerTenantId = TenantContext.getTenantId();
        TenantContext.setTenantId(run.tenantId);
        // 同步路径不经过工作队列，排队等待记 0。
        run.queueWaitMs = 0;
        SolveQueueContext.set(0);
        try {
            if (solveTaskRepository.markRunning(run.tenantId, run.taskId) == 0) {
                return;
            }
            run.startedNanos = System.nanoTime();
            ScheduleService.ExecutionOutcome outcome = executeWithRetry(run, request, false);
            finishOutcome(run, outcome);
            if (outcome.validationFailure()) {
                throw new BizException(ErrorCode.UNPROCESSABLE_ENTITY, outcome.message());
            }
        } catch (RuntimeException exception) {
            if (exception instanceof BizException) {
                throw exception;
            }
            finishFallback(run, "ENGINE_ERROR", safeMessage(exception));
        } finally {
            SolveQueueContext.clear();
            run.active.set(false);
            running.remove(run.taskId);
            // 同步路径在当前调用线程内执行：恢复调用方租户上下文，
            // 否则 submit 返回后的 detail/沙箱 compare 会丢失租户而查不到刚刚落库的任务。
            if (callerTenantId == null) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(callerTenantId);
            }
        }
    }

    private void executeTask(RunningTask run, SolveRequest request) {
        TenantContext.setTenantId(run.tenantId);
        // 从提交到工作线程真正开始执行的等待：写入 metrics.queueWaitMs，
        // 并累加进 portfolio.slotWaitMs（A1 可观测）。
        run.queueWaitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - run.queuedNanos);
        SolveQueueContext.set(run.queueWaitMs);
        try {
            if (run.shouldStop()) {
                cleanup(run);
                return;
            }
            if (solveTaskRepository.markRunning(run.tenantId, run.taskId) == 0) {
                cleanup(run);
                return;
            }
            run.startedNanos = System.nanoTime();
            scheduleTicker(run);
            executeAttemptLoop(run, request);
        } catch (RuntimeException exception) {
            LOGGER.warn("求解任务 {} 执行异常", run.taskId, exception);
            finishFallback(run, "ENGINE_ERROR", safeMessage(exception));
            cleanup(run);
        }
    }

    /**
     * 异步重试编排（A0）：每次尝试结束后，如果还能重试，就把下一次尝试交给
     * {@link SolveRetryClock#schedule} 定时触发，<b>工作线程立即释放</b>，
     * 30 秒退避不再占着 {@code aps-solve-worker}。
     *
     * <p>终态时写入 {@code attemptCount/retryReasons/retryAt} 并完成落库与清理。</p>
     *
     * @param run 运行上下文
     * @param request 求解请求
     */
    private void executeAttemptLoop(RunningTask run, SolveRequest request) {
        int maxAttempts = 1 + retryMaxRetries;
        ScheduleService.ExecutionOutcome outcome = null;
        while (run.attempts.get() < maxAttempts) {
            if (run.shouldStop()) {
                cleanup(run);
                return;
            }
            int attemptNo = run.attempts.incrementAndGet();
            cancelFuture(run.watchdogFuture);
            scheduleWatchdog(run);
            SolveAttempt attempt = executeAttempt(run, request, true, attemptNo);
            outcome = attempt.outcome();
            cancelFuture(run.watchdogFuture);
            if (attempt.retryReason() == null || run.attempts.get() >= maxAttempts || run.shouldStop()) {
                break;
            }
            run.retryReasons.add(attempt.retryReason());
            run.retryAt.add(retryClock.nowUtc().toString());
            scheduleRetry(run, request, attempt.retryReason());
            return;
        }
        if (outcome == null) {
            outcome = failureOutcome(run, SolveRetryReasons.ENGINE_ERROR, "求解任务已终止，未执行");
        }
        finishOutcome(run, stampAttempts(outcome, run.attempts.get(), run.retryReasons, run.retryAt));
        cleanup(run);
    }

    /**
     * 安排下一次尝试：延迟到点后把尝试重新提交给求解工作线程。
     *
     * @param run 运行上下文
     * @param request 求解请求
     * @param reason 触发重试的原因
     */
    private void scheduleRetry(RunningTask run, SolveRequest request, String reason) {
        int nextAttempt = run.attempts.get() + 1;
        publish(run, 0.10, run.lastPhase.get(), run.lastScore.get(),
                "求解失败（" + reason + "），" + retryDelaySeconds + " 秒后进行第 " + nextAttempt + " 次尝试");
        run.retryFuture = retryClock.schedule(Duration.ofSeconds(retryDelaySeconds), () -> {
            try {
                worker.execute(() -> resumeAttemptLoop(run, request));
            } catch (RejectedExecutionException exception) {
                finishFallback(run, "SOLVER_UNAVAILABLE", "重试任务无法进入求解队列");
                cleanup(run);
            }
        });
    }

    /**
     * 定时器到期后的续跑入口；在求解工作线程执行。
     *
     * @param run 运行上下文
     * @param request 求解请求
     */
    private void resumeAttemptLoop(RunningTask run, SolveRequest request) {
        TenantContext.setTenantId(run.tenantId);
        SolveQueueContext.set(run.queueWaitMs);
        try {
            if (run.shouldStop()) {
                cleanup(run);
                return;
            }
            executeAttemptLoop(run, request);
        } catch (RuntimeException exception) {
            LOGGER.warn("求解任务 {} 重试执行异常", run.taskId, exception);
            finishFallback(run, "ENGINE_ERROR", safeMessage(exception));
            cleanup(run);
        }
    }

    /**
     * 终态清理：释放进度/看门狗/重试定时器并移除运行上下文。
     *
     * @param run 运行上下文
     */
    private void cleanup(RunningTask run) {
        run.active.set(false);
        cancelFuture(run.tickerFuture);
        cancelFuture(run.watchdogFuture);
        cancelFuture(run.retryFuture);
        running.remove(run.taskId, run);
        SolveQueueContext.clear();
        TenantContext.clear();
    }

    /**
     * C37 §25.8.1 重试编排：仅 {@code ENGINE_ERROR/TIMEOUT/SOLVER_UNAVAILABLE} 可重试，
     * 首次之外最多 {@link #retryMaxRetries} 次；间隔固定 {@link #retryDelaySeconds} 秒，
     * 等待走 {@link SolveRetryClock}，测试可注入假时钟而不真实休眠。
     *
     * <p>每次尝试都重新武装看门狗：否则第二次尝试会被第一次尝试的超时闹钟误杀。
     * 取消/超时（{@code run.shouldStop()}）优先于重试，不再发起新的尝试。</p>
     *
     * @param run 运行上下文
     * @param request 求解请求
     * @param async 是否异步执行（异步路径异常转降级结果，同步路径保留 BizException 抛出）
     * @return 带 {@code attemptCount/retryReasons/retryAt} 的最终执行结果
     */
    private ScheduleService.ExecutionOutcome executeWithRetry(RunningTask run, SolveRequest request,
                                                              boolean async) {
        int maxAttempts = 1 + retryMaxRetries;
        List<String> retryReasons = new ArrayList<>();
        List<String> retryAt = new ArrayList<>();
        ScheduleService.ExecutionOutcome outcome = null;
        int attempts = 0;
        while (attempts < maxAttempts) {
            if (run.shouldStop()) {
                break;
            }
            attempts++;
            run.attempts.incrementAndGet();
            cancelFuture(run.watchdogFuture);
            if (async) {
                scheduleWatchdog(run);
            }
            SolveAttempt attempt = executeAttempt(run, request, async, attempts);
            outcome = attempt.outcome();
            cancelFuture(run.watchdogFuture);
            if (attempt.retryReason() == null || attempts >= maxAttempts || run.shouldStop()) {
                break;
            }
            retryReasons.add(attempt.retryReason());
            retryAt.add(retryClock.nowUtc().toString());
            if (!awaitRetryDelay(run, attempts + 1, attempt.retryReason())) {
                break;
            }
        }
        if (outcome == null) {
            outcome = failureOutcome(run, SolveRetryReasons.ENGINE_ERROR, "求解任务已终止，未执行");
        }
        return stampAttempts(outcome, attempts, retryReasons, retryAt);
    }

    /**
     * 单次尝试：正常返回执行结果；异常按 {@code ENGINE_ERROR} 转降级结果（异步路径）。
     *
     * @param run 运行上下文
     * @param request 求解请求
     * @param async 是否异步
     * @param attemptNo 当前尝试序号（从 1 开始）
     * @return 单次尝试结果与可重试原因
     */
    private SolveAttempt executeAttempt(RunningTask run, SolveRequest request, boolean async, int attemptNo) {
        ScheduleService.ExecutionOutcome outcome;
        try {
            outcome = scheduleService.execute(request, run.taskId, run.config,
                    scheduleService.adapter(run.route.effectiveEngine()),
                    async ? run::shouldStop : () -> false,
                    (phase, progress, bestScore) -> publish(run, progress, phase, bestScore, phaseMessage(phase)));
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (!async) {
                throw exception;
            }
            LOGGER.warn("求解任务 {} 第 {} 次尝试异常", run.taskId, attemptNo, exception);
            return new SolveAttempt(failureOutcome(run, SolveRetryReasons.ENGINE_ERROR,
                    safeMessage(exception)), SolveRetryReasons.ENGINE_ERROR);
        }
        String reason = retryableReason(outcome);
        return new SolveAttempt(outcome, reason);
    }

    /**
     * 从执行结果提取可重试原因；校验失败、覆盖不完整、重叠守卫等业务结果不重试。
     *
     * @param outcome 执行结果
     * @return 可重试原因；不可重试时为 null
     */
    private static String retryableReason(ScheduleService.ExecutionOutcome outcome) {
        if (outcome == null || outcome.validationFailure()) {
            return null;
        }
        Object raw = outcome.metrics() == null ? null : outcome.metrics().get("engineFallbackReason");
        String reason = raw == null ? null : String.valueOf(raw);
        return SolveRetryReasons.isRetryable(reason) ? reason : null;
    }

    /**
     * 重试退避等待；期间取消/超时则不再发起新尝试。
     *
     * @param run 运行上下文
     * @param nextAttempt 下一次尝试序号
     * @param reason 触发重试的原因
     * @return 允许继续重试时为 true
     */
    private boolean awaitRetryDelay(RunningTask run, int nextAttempt, String reason) {
        publish(run, 0.10, run.lastPhase.get(), run.lastScore.get(), "求解失败（" + reason + "），"
                + retryDelaySeconds + " 秒后进行第 " + nextAttempt + " 次尝试");
        try {
            retryClock.await(Duration.ofSeconds(retryDelaySeconds));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !run.shouldStop();
    }

    /**
     * 写入重试指标（C37：{@code attemptCount/retryReasons/retryAt}）。
     *
     * @param outcome 最终执行结果
     * @param attempts 实际尝试次数
     * @param retryReasons 触发重试的原因序列
     * @param retryAt 触发重试的 UTC 时间序列
     * @return 补全指标后的执行结果
     */
    private static ScheduleService.ExecutionOutcome stampAttempts(ScheduleService.ExecutionOutcome outcome,
                                                                   int attempts, List<String> retryReasons,
                                                                   List<String> retryAt) {
        Map<String, Object> metrics = new LinkedHashMap<>(outcome.metrics());
        metrics.put("attemptCount", attempts);
        metrics.put("retryReasons", List.copyOf(retryReasons));
        metrics.put("retryAt", List.copyOf(retryAt));
        return new ScheduleService.ExecutionOutcome(outcome.status(), outcome.scoreHard(),
                outcome.scoreSoft(), metrics, outcome.message(), outcome.validationFailure(), outcome.tasks(),
                outcome.problem(), outcome.solution());
    }

    /**
     * 异常兜底结果：不落库、已排位数为 0，并保留降级标记。
     *
     * @param run 运行上下文
     * @param reason 降级原因
     * @param message 说明
     * @return 失败执行结果
     */
    private ScheduleService.ExecutionOutcome failureOutcome(RunningTask run, String reason, String message) {
        SolveTaskStatus status = SolveRetryReasons.TIMEOUT.equals(reason)
                ? SolveTaskStatus.TIMEOUT : SolveTaskStatus.FAILED;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("engine", engineCode(run.route.effectiveEngine()));
        metrics.put("phase", status.name());
        metrics.put("progress", 1.0);
        metrics.put("persistedTaskCount", 0);
        metrics.put("assignedTaskCount", 0);
        applyFallback(metrics, reason);
        return new ScheduleService.ExecutionOutcome(status, null, null, metrics, message, false, List.of());
    }

    /**
     * 单次尝试结果。
     *
     * @param outcome 执行结果
     * @param retryReason 可重试原因；不可重试时为 null
     */
    private record SolveAttempt(ScheduleService.ExecutionOutcome outcome, String retryReason) {
    }

    private void scheduleTicker(RunningTask run) {
        run.tickerFuture = ticker.scheduleAtFixedRate(() -> {
            if (!run.active.get() || run.shouldStop()) {
                return;
            }
            long elapsed = elapsedMillis(run.startedNanos);
            long budget = Math.max(1L, run.config.timeoutSeconds()) * 1000L;
            double progress = 0.10 + 0.80 * Math.min(1.0, (double) elapsed / budget);
            publish(run, progress, run.lastPhase.get(), run.lastScore.get(),
                    "求解中，已耗时 " + elapsed + " ms");
        }, PROGRESS_TICK_MILLIS, PROGRESS_TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void scheduleWatchdog(RunningTask run) {
        int timeoutSeconds = Math.max(1, run.config.timeoutSeconds());
        run.watchdogFuture = ticker.schedule(() -> onTimeout(run),
                timeoutSeconds + timeoutGraceSeconds, TimeUnit.SECONDS);
    }

    private void onTimeout(RunningTask run) {
        if (!run.active.get() || run.shouldStop()) {
            return;
        }
        if (!run.timedOut.compareAndSet(false, true)) {
            return;
        }
        int timeoutSeconds = Math.max(1, run.config.timeoutSeconds());
        String message = "求解超时（超过 " + timeoutSeconds + " 秒）";
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("engine", engineCode(run.route.effectiveEngine()));
        metrics.put("phase", SolveTaskStatus.TIMEOUT.name());
        metrics.put("progress", 1.0);
        metrics.put("timeoutSeconds", timeoutSeconds);
        // C20 §24.2：TIMEOUT / FAILED 的 persistedTaskCount 恒为 0。该路径没有结果对象、
        // 也没有调用落库，必须显式写“确认过的 0”，不能靠字段缺失被读成“未知”。
        metrics.put("persistedTaskCount", 0);
        // 没有返回任何任务，已排位数同样是确定值 0（返回体 tasks 恒为空数组）。
        metrics.put("assignedTaskCount", 0);
        metrics.put("attemptCount", Math.max(1, run.attempts.get()));
        applyFallback(metrics, "TIMEOUT");
        run.route.applyFallback(metrics);
        stampQueueWait(metrics, run.queueWaitMs);
        int updated = solveTaskRepository.finish(run.tenantId, run.taskId,
                SolveTaskStatus.TIMEOUT.name(), null, null, toJson(metrics), message);
        if (updated > 0) {
            firePostProcessors(run.taskId, run.tenantId, SolveTaskStatus.TIMEOUT.name(), null, null,
                    metrics, message, null, null);
            publishTerminal(run.taskId, SolveTaskStatus.TIMEOUT, null, metrics, message);
        }
        scheduleService.terminateEarly(run.route.effectiveEngine(),
                new SolveHandle(run.handleId, run.route.effectiveEngine(), SolveTaskStatus.TIMEOUT.name()));
        if (run.future != null) {
            run.future.cancel(true);
        }
        cancelFuture(run.retryFuture);
        run.active.set(false);
        running.remove(run.taskId, run);
    }

    private void finishOutcome(RunningTask run, ScheduleService.ExecutionOutcome outcome) {
        Map<String, Object> metrics = new LinkedHashMap<>(outcome.metrics());
        if (outcome.validationFailure()) {
            run.route.applyDecision(metrics);
        } else {
            run.route.applyFallback(metrics);
        }
        metrics.putIfAbsent("phase",
                outcome.status() == SolveTaskStatus.SOLVED ? "DONE" : outcome.status().name());
        metrics.putIfAbsent("progress", 1.0);
        stampQueueWait(metrics, run.queueWaitMs);
        int updated = solveTaskRepository.finish(run.tenantId, run.taskId, outcome.status().name(),
                outcome.scoreHard(), outcome.scoreSoft(), toJson(metrics), outcome.message());
        if (updated > 0) {
            firePostProcessors(run.taskId, run.tenantId, outcome.status().name(),
                    outcome.scoreHard(), outcome.scoreSoft(), metrics, outcome.message(),
                    outcome.problem(), outcome.solution());
            Long resultTaskId = outcome.status() == SolveTaskStatus.SOLVED ? run.taskId : null;
            publishTerminal(run.taskId, outcome.status(), resultTaskId, metrics, outcome.message());
        }
    }

    private void finishFallback(RunningTask run, String reason, String message) {
        SolveTaskStatus status = "TIMEOUT".equals(reason) ? SolveTaskStatus.TIMEOUT : SolveTaskStatus.FAILED;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("engine", engineCode(run.route.effectiveEngine()));
        metrics.put("phase", status.name());
        metrics.put("progress", 1.0);
        // 同上：异常兜底路径未产生结果、未落库，落库行数与已排位数都是确定值 0。
        metrics.put("persistedTaskCount", 0);
        metrics.put("assignedTaskCount", 0);
        applyFallback(metrics, reason);
        run.route.applyFallback(metrics);
        stampQueueWait(metrics, run.queueWaitMs);
        int updated = solveTaskRepository.finish(run.tenantId, run.taskId, status.name(),
                null, null, toJson(metrics), message);
        if (updated > 0) {
            firePostProcessors(run.taskId, run.tenantId, status.name(), null, null, metrics, message,
                    null, null);
            publishTerminal(run.taskId, status, null, metrics, message);
        }
    }

    /**
     * 触发求解后置钩子（A0 接缝）；钩子异常已在链内被拦截，这里再兜一层，
     * 确保任何后置处理都不会吞掉/改写主流程结果。
     *
     * @param taskId 任务主键
     * @param tenantId 租户
     * @param status 终态
     * @param scoreHard 硬分，可空
     * @param scoreSoft 软分，可空
     * @param metrics 已合并路由/降级的度量
     * @param message 终态说明
     */
    private void firePostProcessors(long taskId, UUID tenantId, String status, Long scoreHard, Long scoreSoft,
                                    Map<String, Object> metrics, String message,
                                    ScheduleProblem problem, ScheduleSolution solution) {
        try {
            postProcessorChain.fire(new SolveFinishEvent(taskId, tenantId, status, scoreHard, scoreSoft,
                    metrics, message, problem, solution));
        } catch (RuntimeException exception) {
            LOGGER.warn("求解后置钩子调用失败（不影响任务终态）: taskId={}", taskId, exception);
        }
    }

    /**
     * 把工作队列等待写入 metrics，并累加到 portfolio.slotWaitMs（A1 可观测）。
     *
     * @param metrics 已构建的度量
     * @param queueWaitMs 排队等待毫秒
     */
    private static void stampQueueWait(Map<String, Object> metrics, long queueWaitMs) {
        metrics.putIfAbsent("queueWaitMs", Math.max(0, queueWaitMs));
        Object raw = metrics.get("portfolio");
        if (!(raw instanceof Map<?, ?> portfolio)) {
            return;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        portfolio.forEach((key, value) -> copy.put(String.valueOf(key), value));
        long base = copy.get("slotWaitMs") instanceof Number number
                ? Math.max(0, number.longValue()) : 0;
        copy.put("slotWaitMs", base + Math.max(0, queueWaitMs));
        metrics.put("portfolio", copy);
    }

    private void applyFallback(Map<String, Object> metrics, String reason) {
        if (!fallbackEnabled) {
            return;
        }
        metrics.put("engineFallback", true);
        metrics.put("engineFallbackReason", reason);
        metrics.put("fallbackMode", FALLBACK_MODE);
        metrics.put("fallbackAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    private void publish(RunningTask run, double progress, String phase, String bestScore, String message) {
        String previousPhase = run.lastPhase.get();
        boolean phaseChanged = phase != null && !phase.equals(previousPhase);
        long lastPublishNanos = run.lastPublishNanos.get();
        long now = System.nanoTime();
        // 阶段切换必须立即推送；普通进度/最优解帧按 1 秒节流（QA2-04）。
        if (!phaseChanged && lastPublishNanos != 0L
                && now - lastPublishNanos < TimeUnit.MILLISECONDS.toNanos(PROGRESS_MIN_INTERVAL_MILLIS)) {
            if (bestScore != null) {
                run.lastScore.set(bestScore);
            }
            return;
        }
        if (phase != null) {
            run.lastPhase.set(phase);
        }
        if (bestScore != null) {
            run.lastScore.set(bestScore);
        }
        run.lastPublishNanos.set(now);
        progressPublisher.publish(new SolveProgress(run.taskId, SolveTaskStatus.RUNNING.name(),
                progress, phase, bestScore, elapsedMillis(run.startedNanos), null, null, message));
    }

    private void publishTerminal(long taskId, SolveTaskStatus status, Long resultTaskId,
                                 Map<String, Object> metrics, String message) {
        String bestScore = asString(metrics.get("bestScore"));
        String phase = asString(metrics.get("phase"));
        String errorCode = status == SolveTaskStatus.SOLVED ? null
                : asString(metrics.getOrDefault("engineFallbackReason", status.name()));
        long elapsed = 0L;
        SolveTaskRecord record = solveTaskRepository
                .findById(TenantContext.getTenantId(), taskId).orElse(null);
        if (record != null && record.startedAt() != null) {
            OffsetDateTime end = record.finishedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : record.finishedAt();
            elapsed = Math.max(0L, Duration.between(record.startedAt(), end).toMillis());
        }
        progressPublisher.publish(new SolveProgress(taskId, status.name(), 1.0,
                phase == null ? terminalPhase(status) : phase, bestScore, elapsed, resultTaskId,
                errorCode, message));
    }

    /**
     * 终态 {@code phase} 的兜底映射（C20）：{@code SOLVED} 必须是 {@code DONE}，其余与状态同名。
     *
     * @param status 终态
     * @return 契约枚举内的阶段值
     */
    private String terminalPhase(SolveTaskStatus status) {
        return status == SolveTaskStatus.SOLVED ? "DONE" : status.name();
    }

    private SolveTaskDetail detail(SolveTaskRecord record) {
        Map<String, Object> metrics = parseMetrics(record.metricsJson());
        SolveTaskStatus status = SolveTaskStatus.fromNullable(record.status());
        SolveProgress live = progressPublisher.latest(record.id()).orElse(null);
        double progress = status.isTerminal() ? 1.0
                : live != null ? live.progress() : asDouble(metrics.get("progress"), 0.0);
        String phase;
        if (live != null && !status.isTerminal()) {
            phase = live.phase();
        } else if (status == SolveTaskStatus.PENDING) {
            phase = "QUEUED";
        } else {
            phase = asString(metrics.get("phase"));
            if (phase == null) {
                phase = status.isTerminal() ? terminalPhase(status) : "QUEUED";
            }
        }
        String bestScore = live != null && live.bestScore() != null ? live.bestScore()
                : asString(metrics.get("bestScore"));
        long elapsed = 0L;
        if (record.startedAt() != null) {
            OffsetDateTime end = record.finishedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : record.finishedAt();
            elapsed = Math.max(0L, Duration.between(record.startedAt(), end).toMillis());
        }
        boolean degraded = Boolean.TRUE.equals(metrics.get("engineFallback"));
        String errorCode = null;
        if (status.isTerminal() && status != SolveTaskStatus.SOLVED) {
            String reason = degraded ? asString(metrics.get("engineFallbackReason")) : null;
            errorCode = reason == null ? status.name() : reason;
        }
        return new SolveTaskDetail(record.id(), record.sandboxId(), record.strategyId(), record.engine(),
                status.name(), progress, phase, bestScore, record.scoreHard(), record.scoreSoft(),
                elapsed, status == SolveTaskStatus.SOLVED ? record.id() : null,
                errorCode, record.errorMessage(), degraded, metrics,
                record.startedAt(), record.finishedAt(), record.createdAt(), record.updatedAt(),
                List.of());
    }

    /**
     * 判断是否为场景外键违反（预检通过但并发删除场景时仍会撞 FK）。
     *
     * @param exception 数据完整性异常
     * @return 约束名为 {@code fk_solve_task_sandbox} 时为 true
     */
    private static boolean isSandboxForeignKeyViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause.getMessage();
        return message != null && message.contains("fk_solve_task_sandbox");
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String key = idempotencyKey.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Idempotency-Key 长度不能超过 64");
        }
        return key;
    }

    private static String engineCode(SolverEngine engine) {
        return engine.getCode().toUpperCase(Locale.ROOT);
    }

    private static String phaseMessage(String phase) {
        if (phase == null) {
            return null;
        }
        return switch (phase) {
            case "ASSEMBLING" -> "装配求解问题";
            case "SOLVING" -> "引擎求解中";
            case "PERSISTING" -> "求解结果落库";
            case "DONE" -> "求解完成";
            default -> phase;
        };
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static long elapsedMillis(long startNanos) {
        if (startNanos == 0L) {
            return 0L;
        }
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void cancelFuture(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private static Map<String, Object> parseMetrics(String metricsJson) {
        if (metricsJson == null || metricsJson.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(metricsJson, new TypeReference<Map<String, Object>>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return Map.of();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /**
     * JSON 序列化。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    static String toJson(Object value) {
        return ScheduleService.toJson(value);
    }

    /**
     * 停止工作线程与调度器。
     */
    @PreDestroy
    public void shutdown() {
        worker.shutdownNow();
        ticker.shutdownNow();
    }

    /**
     * 运行上下文。
     */
    private static final class RunningTask {

        /**
         * 求解任务主键。
         */
        private final long taskId;

        /**
         * 租户标识。
         */
        private final UUID tenantId;

        /**
         * 引擎路由决策。
         */
        private final EngineRoute route;

        /**
         * 求解配置。
         */
        private final SolveConfig config;

        /**
         * 引擎异步句柄标识。
         */
        private final UUID handleId = UUID.randomUUID();

        /**
         * 进入队列的纳秒时间（A1：排队等待观测）。
         */
        private final long queuedNanos = System.nanoTime();

        /**
         * 从入队到工作线程开始执行的毫秒数。
         */
        private volatile long queueWaitMs;

        /**
         * 是否已取消。
         */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /**
         * 是否已超时。
         */
        private final AtomicBoolean timedOut = new AtomicBoolean(false);

        /**
         * 已发起的尝试次数（看门狗超时直接落库时也要能写 attemptCount）。
         */
        private final AtomicInteger attempts = new AtomicInteger();

        /**
         * 是否仍活跃。
         */
        private final AtomicBoolean active = new AtomicBoolean(true);

        /**
         * 最近阶段。
         */
        private final AtomicReference<String> lastPhase = new AtomicReference<>("QUEUED");

        /**
         * 最近最优评分。
         */
        private final AtomicReference<String> lastScore = new AtomicReference<>();

        /**
         * 最近一次进度帧发布时刻（纳秒），用于 1 秒节流。
         */
        private final AtomicLong lastPublishNanos = new AtomicLong();

        /**
         * 工作线程 Future。
         */
        private volatile Future<?> future;

        /**
         * 进度 ticker。
         */
        private volatile Future<?> tickerFuture;

        /**
         * 超时看门狗。
         */
        private volatile Future<?> watchdogFuture;

        /**
         * 重试定时器句柄（A0：到期后再把尝试提交回工作线程）。
         */
        private volatile Future<?> retryFuture;

        /**
         * 已记录的重试原因（按发生顺序）。
         */
        private final List<String> retryReasons = new ArrayList<>();

        /**
         * 已记录的重试时间戳（按发生顺序，ISO-8601 UTC）。
         */
        private final List<String> retryAt = new ArrayList<>();

        /**
         * 开始求解的纳秒时间戳；0 表示尚未进入 RUNNING。
         */
        private volatile long startedNanos;

        private RunningTask(long taskId, UUID tenantId, EngineRoute route, SolveConfig config) {
            this.taskId = taskId;
            this.tenantId = tenantId;
            this.route = route;
            this.config = config;
        }

        private boolean shouldStop() {
            return cancelled.get() || timedOut.get();
        }
    }
}
