package com.hengce.aps.solver.timefold.bench;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.event.BestSolutionChangedEvent;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.hengce.aps.solver.api.ConstraintJustification;
import com.hengce.aps.solver.api.ScheduleProblem;
import com.hengce.aps.solver.api.ScorePoint;
import com.hengce.aps.solver.timefold.TimefoldModelBuilder;
import com.hengce.aps.solver.timefold.constraint.ApsConstraintProvider;
import com.hengce.aps.solver.timefold.constraint.ConstraintCodes;
import com.hengce.aps.solver.timefold.constraint.ConstraintJustifications;
import com.hengce.aps.solver.timefold.constraint.SoftConstraintWeights;
import com.hengce.aps.solver.timefold.model.ResourcePlan;
import com.hengce.aps.solver.timefold.model.SchedulePlan;
import com.hengce.aps.solver.timefold.model.TaskPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基准跑分入口（类名不以 {@code Test} 结尾，默认 Surefire 不跑）。
 *
 * <p>运行（<b>必须带 {@code -am}</b>；只给 {@code -pl} 会解析到 {@code ~/.m2} 里的旧
 * {@code solver-api} 快照，实测报 {@code NoSuchMethodError: SchedulingOrder.<init>}）：</p>
 * <pre>
 * mvn -o -B -ntp -am -pl aps-solver/solver-timefold -Dtest=SolverBenchRunner \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Daps.bench.caseFilter=L01 \
 *     -Daps.bench.report=target/bench/a0-entry-verify.json test
 * </pre>
 *
 * <p>确定性步数模式（基线回归口径，含 {@code -am}）：</p>
 * <pre>
 * mvn -o -B -ntp -am -pl aps-solver/solver-timefold -Dtest=SolverBenchRunner \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Daps.bench.termination=STEP \
 *     -Daps.bench.stepCounts=SMALL:1000,MEDIUM:10000 \
 *     -Daps.bench.caseExclude=L -Daps.bench.report=target/bench/bench-report-step.json test
 * </pre>
 *
 * <p>MEDIUM 步数预算取 10,000：容量修订后的 M03/M09/M13 在 2,000 步时 hard 仍为负
 * （-3/-6/-13），10,000 步才稳定到 hard=0，详见 {@code docs/perf/bench-baseline.md} §5。</p>
 *
 * <p>规模参考题（{@code category=stress-reference}，5 题后缀 {@code -stress}）保存的是
 * 容量修订前的原始题面，<b>默认不执行</b>（即使不传 {@code caseExclude} 也会跳过），
 * 只在显式传 {@code -Daps.bench.includeStress=true} 时运行：</p>
 * <pre>
 * mvn -o -B -ntp -am -pl aps-solver/solver-timefold -Dtest=SolverBenchRunner \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Daps.bench.includeStress=true \
 *     -Daps.bench.caseFilter=S08-stress,M03-stress,M09-stress,M13-stress,L02-stress \
 *     -Daps.bench.report=target/bench/bench-report-stress.json test
 * </pre>
 * <p>这些题面容量上界被突破，{@code hard<0} 是预期结果；只用于观察“规模到这个量级时的
 * 耗时/超时/负多少”，不能用来证明“能排到 0”。详见 {@code docs/perf/bench-baseline.md}
 * 的「规模参考（stress-reference）」一节。</p>
 *
 * <p>步数模式下以 {@code stepCountLimit} 为主，但<b>强制叠加该题的 {@code timeoutSeconds}</b>
 * 作为时间上限（最小 1s，不允许 0=无上限）：当某一步内部永不返回时，只有时间型终止能打断它，
 * 否则 {@code stepCountLimit} 永远等不到步边界。不叠加
 * {@code unimprovedSecondsSpentLimit}。报告用 {@code terminationOutcome} 区分
 * {@code STEP_COUNT}（步数先到）与 {@code TIME_LIMIT}（时间先到）；后者标
 * {@code stepDeterminismApplicable=false}，不纳入确定性对比。</p>
 *
 * <p><b>单步永不返回的已知案例</b>：{@code S20-infeasible-missing-routing}。根因不在计分内部，
 * 而在 Timefold Local Search 的 {@code LocalSearchDecider.decideNextStep} →
 * {@code UniformRandomUnionMoveIterator.next} → {@code KOptListMoveIterator.createUpcomingSelection}
 * 的 move 遍历：默认 {@code never-ending} 随机选择序下，单步结束依赖
 * {@code forager.isQuitEarly()}，当分数面进入严格局部最优（零接受）时该单步无界；
 * 计分只是每个 move 的一次调用。{@code stepCountLimit} 只在步边界比较，因此永不生效；
 * 时间型终止可在单步内打断。实测 {@code ORIGINAL} 选择序 50 步 114ms 正常，
 * 墙钟下 S20 真实结果 {@code hard=0 / soft=-3380 / 2.05s}。
 * 本 runner 因此不再排除 S20：步数模式下它会以 {@code TIME_LIMIT} 返回终态，
 * 并被标记为“不适用步数口径”。</p>
 *
 * <p><b>口径声明</b>：本入口直接构造 {@code SolverConfig} + {@code BestSolutionChangedEvent} 监听器，
 * 不经过 {@code TimefoldSolverAdapter}。原因是后者不向基准暴露 time-to-best；本入口用监听器
 * 取真实时间线，但因此 <b>不包含</b>适配器的 {@code SOLVING_LOCK}、请求参数解析、
 * 状态映射与归因合并。两种口径的差异写在 {@code docs/perf/bench-baseline.md}，不得混用。</p>
 */
class SolverBenchRunner {

    private static final String CASES_RESOURCE = "/bench/cases";

    private static final int SCORE_HISTORY_LIMIT = 200;

    private static final List<String> SIZE_ORDER = List.of("SMALL", "MEDIUM", "LARGE");

    private static final String STRESS_CATEGORY = "stress-reference";

    @Test
    void runBench() throws IOException, URISyntaxException {
        Path casesDir = resolveCasesDir();
        List<BenchCase> loaded = BenchCase.loadAll(casesDir);
        boolean includeStress = Boolean.parseBoolean(
                System.getProperty("aps.bench.includeStress", "false"));
        List<String> excludedCases = loaded.stream()
                .filter(item -> isExcluded(item) || (!includeStress && isStressReference(item)))
                .map(BenchCase::caseId)
                .sorted()
                .toList();
        List<BenchCase> cases = loaded.stream()
                .filter(this::matchesFilter)
                .filter(item -> !isExcluded(item))
                .filter(item -> includeStress || !isStressReference(item))
                .sorted(Comparator
                        .comparingInt((BenchCase item) -> SIZE_ORDER.indexOf(item.sizeClass()))
                        .thenComparing(BenchCase::caseId))
                .toList();
        if (cases.isEmpty()) {
            throw new IllegalStateException("没有匹配的基准题: " + casesDir);
        }

        Map<String, Integer> weights = new LinkedHashMap<>();
        SoftConstraintWeights.defaults().forEach(weights::put);
        long runBegin = System.nanoTime();
        List<Map<String, Object>> results = new ArrayList<>();
        for (BenchCase benchCase : cases) {
            System.out.println("RUNNING " + benchCase.caseId() + " " + benchCase.sizeClass());
            System.out.flush();
            CaseResult result = runCase(benchCase);
            results.add(result.toMap());
            printCaseLine(result);
            System.out.flush();
        }
        long totalMs = Duration.ofNanos(System.nanoTime() - runBegin).toMillis();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tool", "SolverBenchRunner");
        report.put("formatVersion", 1);
        report.put("runAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        report.put("headSha", System.getProperty("aps.bench.headSha", System.getenv()
                .getOrDefault("APS_BENCH_HEAD_SHA", "unset")));
        report.put("dirty", Boolean.parseBoolean(System.getProperty("aps.bench.dirty",
                System.getenv().getOrDefault("APS_BENCH_DIRTY", "false"))));
        report.put("machine", machineInfo());
        report.put("softWeights", weights);
        report.put("softWeightsConfigHash", SoftConstraintWeights.configHash(weights));
        report.put("seedPolicy", "baseline seed=0；调参/验证 seed 见 roadmap §2.6.3");
        report.put("terminationRequest", "STEP".equalsIgnoreCase(
                System.getProperty("aps.bench.termination", "WALL")) ? "STEP_COUNT" : "WALL_CLOCK");
        report.put("stepCountsSpec", System.getProperty("aps.bench.stepCounts", ""));
        report.put("timingCaliber", "STEP".equalsIgnoreCase(
                System.getProperty("aps.bench.termination", "WALL"))
                ? "步数终止（stepCountLimit 为主口径，强制叠加该题 timeoutSeconds 作为单步兜底时间上限，"
                        + "不叠加无改进终止）；timeToBest=首个达到最终最好分的 "
                        + "BestSolutionChangedEvent 相对求解开始毫秒数"
                : "SLA 墙钟终止（spentLimit + 可选 unimprovedSecondsSpentLimit）；timeToBest=首个达到最终最好分的 "
                        + "BestSolutionChangedEvent 相对求解开始毫秒数");
        report.put("scoreHistoryCaliber", "来自 BestSolutionChangedEvent 监听器（上限 200 点），非 SolveMetrics.scoreHistory");
        report.put("enginePath", "direct SolverFactory（非 TimefoldSolverAdapter）");
        report.put("casesTotal", cases.size());
        report.put("caseExcludeSpec", System.getProperty("aps.bench.caseExclude", ""));
        report.put("includeStress", includeStress);
        report.put("excludedCases", excludedCases);
        report.put("totalDurationMs", totalMs);
        report.put("sizeClasses", summarize(results));
        report.put("cases", results);

        Path reportPath = Path.of(System.getProperty("aps.bench.report", "target/bench/bench-report.json"));
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, Json.write(report) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.out.println("BENCH_REPORT " + reportPath.toAbsolutePath());
    }

    /**
     * 便于在测试编译受阻时用 {@code java -cp ... SolverBenchRunner} 直接跑基线。
     *
     * @param args 忽略
     * @throws Exception 运行失败
     */
    public static void main(String[] args) throws Exception {
        new SolverBenchRunner().runBench();
    }

    private CaseResult runCase(BenchCase benchCase) {
        ScheduleProblem problem;
        try {
            problem = BenchProblemFactory.materialize(benchCase);
        } catch (RuntimeException exception) {
            return CaseResult.failed(benchCase, exception);
        }
        int taskCount = BenchProblemFactory.taskCount(problem);
        int orderCount = problem.orders().size();
        String computedClass = BenchProblemFactory.classify(orderCount, taskCount);
        String problemDigest = BenchProblemFactory.problemDigest(benchCase, problem);

        SoftConstraintWeights.activate(SoftConstraintWeights.defaults());
        try {
            SchedulePlan plan;
            try {
                plan = TimefoldModelBuilder.build(problem);
            } catch (RuntimeException exception) {
                return CaseResult.failed(benchCase, exception);
            }
            long stepCount = resolveStepCount(benchCase);
            boolean stepMode = stepCount > 0;
            // 时间上限取该题 timeoutSeconds；步数模式也必须有上限，0 不允许。
            long stepTimeLimitSeconds = stepMode
                    ? Math.max(1L, benchCase.budget().timeoutSeconds())
                    : 0L;
            TerminationConfig termination;
            if (stepMode) {
                // 步数为主口径，但必须叠加时间上限：单步永不返回时只有时间型终止能打断。
                termination = new TerminationConfig()
                        .withStepCountLimit((int) Math.min(Integer.MAX_VALUE, stepCount))
                        .withSpentLimit(Duration.ofSeconds(stepTimeLimitSeconds));
            } else {
                termination = new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(Math.max(1, benchCase.budget().timeoutSeconds())));
                if (benchCase.budget().unimprovedSecondsSpentLimit() > 0) {
                    termination.setUnimprovedSecondsSpentLimit(
                            (long) benchCase.budget().unimprovedSecondsSpentLimit());
                }
            }
            String terminationMode = stepMode ? "STEP_COUNT" : "WALL_CLOCK";
            SolverConfig solverConfig = new SolverConfig()
                    .withSolutionClass(SchedulePlan.class)
                    .withEntityClasses(ResourcePlan.class, TaskPlan.class)
                    .withConstraintProviderClass(ApsConstraintProvider.class)
                    .withRandomSeed(benchCase.seed())
                    .withTerminationConfig(termination);
            Solver<SchedulePlan> solver = SolverFactory.<SchedulePlan>create(solverConfig).buildSolver();

            AtomicLong solveStart = new AtomicLong(System.nanoTime());
            AtomicLong firstBestMs = new AtomicLong(-1L);
            AtomicLong bestMs = new AtomicLong(-1L);
            AtomicLong improvements = new AtomicLong(0L);
            AtomicReference<HardSoftScore> previous = new AtomicReference<>();
            List<ScorePoint> history = new ArrayList<>();
            solver.addEventListener(event -> onBestSolution(
                    event, solveStart, firstBestMs, bestMs, improvements, previous, history));

            SchedulePlan best = solver.solve(plan);
            long durationMs = Duration.ofNanos(System.nanoTime() - solveStart.get()).toMillis();
            if (best == null) {
                return CaseResult.failed(benchCase, new IllegalStateException("求解器返回 null"));
            }
            String terminationOutcome = resolveTerminationOutcome(
                    stepMode, durationMs, stepTimeLimitSeconds);
            HardSoftScore score = best.getScore() == null ? HardSoftScore.ZERO : best.getScore();
            int assigned = (int) best.getTasks().stream()
                    .filter(task -> task.getResourceId() != null && task.getStartTime() != null)
                    .count();
            boolean feasible = score.hardScore() == 0L && assigned == best.getTasks().size();
            List<String> hardCodes = ConstraintJustifications.from(best).stream()
                    .map(ConstraintJustification::constraintCode)
                    .distinct()
                    .toList();
            return CaseResult.ok(benchCase, orderCount, taskCount, computedClass, problemDigest,
                    terminationMode, stepCount, terminationOutcome, stepTimeLimitSeconds,
                    best.getTasks().size(), assigned, score.hardScore(), score.softScore(),
                    feasible, durationMs, firstBestMs.get(), bestMs.get(), improvements.get(),
                    history, hardCodes);
        } catch (RuntimeException exception) {
            // 步数预算小于构造所需步数时，CH 会提前结束并让 LS 抛“uninitialized entities”；
            // 单题失败不应中断整批基线。
            return CaseResult.failed(benchCase, exception);
        } finally {
            SoftConstraintWeights.disable();
        }
    }

    private static void onBestSolution(
            BestSolutionChangedEvent<SchedulePlan> event,
            AtomicLong solveStart,
            AtomicLong firstBestMs,
            AtomicLong bestMs,
            AtomicLong improvements,
            AtomicReference<HardSoftScore> previous,
            List<ScorePoint> history) {
        HardSoftScore score = event.getNewBestSolution().getScore();
        if (score == null) {
            return;
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - solveStart.get()).toMillis();
        if (firstBestMs.get() < 0) {
            firstBestMs.set(elapsedMs);
        }
        HardSoftScore last = previous.getAndSet(score);
        if (last == null || score.compareTo(last) > 0) {
            // 记录“最终最好分首次出现”的时刻；后续同等分数不再刷新。
            bestMs.set(elapsedMs);
            improvements.incrementAndGet();
        }
        if (history.size() < SCORE_HISTORY_LIMIT) {
            history.add(new ScorePoint(elapsedMs, 0L,
                    BigDecimal.valueOf(score.hardScore()), BigDecimal.valueOf(score.softScore())));
        }
    }

    private void printCaseLine(CaseResult result) {
        if (result.error != null) {
            System.out.printf(Locale.ROOT,
                    "CASE %s class=%s status=ERROR error=%s fingerprint=%s%n",
                    result.benchCase.caseId(), result.benchCase.sizeClass(),
                    result.error, result.benchCase.fingerprint());
            return;
        }
        System.out.printf(Locale.ROOT,
                "CASE %s class=%s(%s) term=%s outcome=%s steps=%d timeCap=%ds tasks=%d assigned=%d "
                        + "hard=%d soft=%d ok=%s durationMs=%d "
                        + "timeToBestMs=%d improvements=%d evidence=%s hardMatch=%s recall=%s fingerprint=%s%n",
                result.benchCase.caseId(), result.benchCase.sizeClass(), result.computedClass,
                result.terminationMode, result.terminationOutcome, result.stepCount,
                result.stepTimeLimitSeconds,
                result.taskCount, result.assigned, result.hard, result.soft, result.feasible,
                result.durationMs, result.timeToBestMs, result.improvements, result.hardCodes,
                result.hardMatches, result.recallText, result.benchCase.fingerprint());
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String sizeClass : SIZE_ORDER) {
            List<Map<String, Object>> group = results.stream()
                    .filter(item -> sizeClass.equals(item.get("declaredSizeClass")))
                    .toList();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cases", group.size());
            List<Long> durations = longs(group, "durationMs");
            List<Long> hard = longs(group, "hard");
            List<Long> soft = longs(group, "soft");
            List<Long> timeToBest = longs(group, "timeToBestMs").stream()
                    .filter(value -> value >= 0)
                    .toList();
            item.put("totalDurationMs", durations.stream().mapToLong(Long::longValue).sum());
            item.put("medianDurationMs", median(durations));
            item.put("medianHard", median(hard));
            item.put("medianSoft", median(soft));
            item.put("medianTimeToBestMs", median(timeToBest));
            item.put("hardFeasibleCases", group.stream()
                    .filter(entry -> Boolean.TRUE.equals(entry.get("feasible")))
                    .count());
            item.put("configHash", SoftConstraintWeights.configHash(SoftConstraintWeights.defaults()));
            item.put("terminationModes", group.stream()
                    .map(entry -> String.valueOf(entry.get("terminationMode")))
                    .distinct()
                    .toList());
            item.put("terminationOutcomes", group.stream()
                    .map(entry -> String.valueOf(entry.get("terminationOutcome")))
                    .distinct()
                    .toList());
            item.put("stepTimeoutCases", group.stream()
                    .filter(entry -> "TIME_LIMIT".equals(entry.get("terminationOutcome")))
                    .map(entry -> String.valueOf(entry.get("caseId")))
                    .toList());
            item.put("medianStepCount", median(longs(group, "stepCount")));
            summary.put(sizeClass, item);
        }
        return summary;
    }

    private static List<Long> longs(List<Map<String, Object>> items, String key) {
        List<Long> values = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object raw = item.get(key);
            if (raw instanceof Number number) {
                values.add(number.longValue());
            }
        }
        return values;
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return -1L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static long resolveStepCount(BenchCase benchCase) {
        String requested = System.getProperty("aps.bench.termination", "WALL");
        String spec = System.getProperty("aps.bench.stepCounts", "");
        boolean stepRequested = "STEP".equalsIgnoreCase(requested) || !spec.isBlank();
        if (!stepRequested) {
            return benchCase.budget().maximumStepCount();
        }
        if (!spec.isBlank()) {
            for (String token : spec.split(",")) {
                String[] pair = token.split(":");
                if (pair.length == 2 && pair[0].trim().equalsIgnoreCase(benchCase.sizeClass())) {
                    long value = Long.parseLong(pair[1].trim());
                    if (value > 0) {
                        return value;
                    }
                }
            }
        }
        if (benchCase.budget().maximumStepCount() > 0) {
            return benchCase.budget().maximumStepCount();
        }
        throw new IllegalStateException("步数模式缺少档位步数: " + benchCase.caseId()
                + "，请用 -Daps.bench.stepCounts=SMALL:...,MEDIUM:...,LARGE:...");
    }

    /**
     * 推断实际终止原因。Timefold 2.8 的 {@code Solver} API 不暴露终止原因，
     * 因此用“耗时是否到达步数模式的墙钟上限”来区分；误差窗口 5ms。
     *
     * @param stepMode 是否步数模式
     * @param durationMs 求解耗时
     * @param stepTimeLimitSeconds 步数模式的墙钟上限（秒），非步数模式忽略
     * @return {@code STEP_COUNT} / {@code TIME_LIMIT} / {@code WALL_CLOCK}
     */
    private static String resolveTerminationOutcome(
            boolean stepMode, long durationMs, long stepTimeLimitSeconds) {
        if (!stepMode) {
            return "WALL_CLOCK";
        }
        return durationMs >= stepTimeLimitSeconds * 1000L - 5L
                ? "TIME_LIMIT"
                : "STEP_COUNT";
    }

    private boolean matchesFilter(BenchCase benchCase) {
        String filter = System.getProperty("aps.bench.caseFilter", "");
        if (filter.isBlank()) {
            return true;
        }
        for (String token : filter.split(",")) {
            if (!token.isBlank() && benchCase.caseId().startsWith(token.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 排除题面：{@code -Daps.bench.caseExclude=S20}，按 caseId 前缀匹配。
     *
     * <p>通用排除开关：用于临时剔除某题，例如定位“单步耗时异常、时间先到”的个案。
     * {@code S20} 现已纳入常规口径（步数模式会以 {@code TIME_LIMIT} 返回终态并标记为
     * 不适用步数口径），默认命令只排除 {@code L}（LARGE 仅作墙钟参考）。</p>
     *
     * @param benchCase 题面
     * @return {@code true} 表示排除
     */
    private boolean isExcluded(BenchCase benchCase) {
        String exclude = System.getProperty("aps.bench.caseExclude", "");
        if (exclude.isBlank()) {
            return false;
        }
        for (String token : exclude.split(",")) {
            if (!token.isBlank() && benchCase.caseId().startsWith(token.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 规模参考题（{@code category=stress-reference}）：保存容量修订前的原始题面，
     * 默认不进入回归门禁；只有 {@code -Daps.bench.includeStress=true} 才执行。
     *
     * @param benchCase 题面
     * @return {@code true} 表示规模参考题
     */
    private boolean isStressReference(BenchCase benchCase) {
        return STRESS_CATEGORY.equalsIgnoreCase(benchCase.category());
    }

    private static Path resolveCasesDir() throws URISyntaxException {
        String override = System.getProperty("aps.bench.casesDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        URL resource = SolverBenchRunner.class.getResource(CASES_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("classpath 上找不到题面目录: " + CASES_RESOURCE);
        }
        return Path.of(resource.toURI());
    }

    private static Map<String, Object> machineInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        Runtime runtime = Runtime.getRuntime();
        info.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " / " + System.getProperty("os.arch"));
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("javaVendor", System.getProperty("java.vendor"));
        info.put("availableProcessors", runtime.availableProcessors());
        info.put("jvmMaxHeapBytes", runtime.maxMemory());
        info.put("jvmTotalMemoryBytes", runtime.totalMemory());
        info.put("defaultTimeZone", ZoneOffset.systemDefault().getId());
        info.put("timefoldVersion", "2.8.0 (community)");
        return info;
    }

    /**
     * 单题跑分结果。
     */
    private static final class CaseResult {

        private final BenchCase benchCase;

        private final int orderCount;

        private final int taskCount;

        private final int totalTasks;

        private final int assigned;

        private final String computedClass;

        private final String problemDigest;

        private final String terminationMode;

        private final long stepCount;

        private final String terminationOutcome;

        private final long stepTimeLimitSeconds;

        private final long hard;

        private final long soft;

        private final boolean feasible;

        private final long durationMs;

        private final long firstBestMs;

        private final long timeToBestMs;

        private final long improvements;

        private final List<ScorePoint> history;

        private final List<String> hardCodes;

        private final boolean hardMatches;

        private final String recallText;

        private final boolean assignmentsMatch;

        private final String error;

        private CaseResult(BenchCase benchCase, int orderCount, int taskCount, int totalTasks,
                           int assigned, String computedClass, String problemDigest,
                           String terminationMode, long stepCount,
                           String terminationOutcome, long stepTimeLimitSeconds,
                           long hard, long soft, boolean feasible, long durationMs,
                           long firstBestMs, long timeToBestMs, long improvements,
                           List<ScorePoint> history, List<String> hardCodes,
                           boolean hardMatches, String recallText, boolean assignmentsMatch,
                           String error) {
            this.benchCase = benchCase;
            this.orderCount = orderCount;
            this.taskCount = taskCount;
            this.totalTasks = totalTasks;
            this.assigned = assigned;
            this.computedClass = computedClass;
            this.problemDigest = problemDigest;
            this.terminationMode = terminationMode;
            this.stepCount = stepCount;
            this.terminationOutcome = terminationOutcome;
            this.stepTimeLimitSeconds = stepTimeLimitSeconds;
            this.hard = hard;
            this.soft = soft;
            this.feasible = feasible;
            this.durationMs = durationMs;
            this.firstBestMs = firstBestMs;
            this.timeToBestMs = timeToBestMs;
            this.improvements = improvements;
            this.history = history;
            this.hardCodes = hardCodes;
            this.hardMatches = hardMatches;
            this.recallText = recallText;
            this.assignmentsMatch = assignmentsMatch;
            this.error = error;
        }

        private static CaseResult failed(BenchCase benchCase, RuntimeException exception) {
            return new CaseResult(benchCase, 0, 0, 0, 0, "UNKNOWN", "",
                    "WALL_CLOCK", 0L, "UNKNOWN", 0L, 0L, 0L, false, 0L,
                    -1L, -1L, 0L, List.of(), List.of(), false,
                    String.valueOf(exception.getMessage()), false,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }

        private static CaseResult ok(BenchCase benchCase, int orderCount, int taskCount,
                                     String computedClass, String problemDigest,
                                     String terminationMode, long stepCount,
                                     String terminationOutcome, long stepTimeLimitSeconds,
                                     int totalTasks, int assigned, long hard, long soft,
                                     boolean feasible, long durationMs, long firstBestMs,
                                     long timeToBestMs, long improvements,
                                     List<ScorePoint> history, List<String> hardCodes) {
            List<String> expectedCodes = Json.stringList(benchCase.expectation(), "expectedHardCodes", List.of());
            LinkedHashSet<String> matched = new LinkedHashSet<>(hardCodes);
            matched.retainAll(expectedCodes);
            boolean hardMatches = expectationHardMatches(benchCase, hard, assigned, totalTasks);
            boolean assignmentsMatch = expectationAssignmentsMatch(benchCase, assigned);
            String recall = expectedCodes.isEmpty()
                    ? "n/a"
                    : String.format(Locale.ROOT, "%d/%d", matched.size(), expectedCodes.size());
            return new CaseResult(benchCase, orderCount, taskCount, totalTasks, assigned,
                    computedClass, problemDigest, terminationMode, stepCount,
                    terminationOutcome, stepTimeLimitSeconds,
                    hard, soft, feasible, durationMs, firstBestMs,
                    timeToBestMs, improvements, history, hardCodes,
                    hardMatches, recall, assignmentsMatch, null);
        }

        private static boolean expectationHardMatches(BenchCase benchCase, long hard,
                                                      int assigned, int totalTasks) {
            Map<String, Object> expectation = benchCase.expectation();
            Object exact = expectation.get("hardScore");
            if (exact instanceof Number number) {
                return hard == number.longValue() && assigned == totalTasks;
            }
            Object atMost = expectation.get("hardScoreAtMost");
            if (atMost instanceof Number number) {
                return hard <= number.longValue();
            }
            return true;
        }

        private static boolean expectationAssignmentsMatch(BenchCase benchCase, int assigned) {
            Object expected = benchCase.expectation().get("assignedTasks");
            return !(expected instanceof Number number) || assigned == number.intValue();
        }

        private Map<String, Object> toMap() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("caseId", benchCase.caseId());
            item.put("kind", benchCase.kind());
            item.put("declaredSizeClass", benchCase.sizeClass());
            item.put("computedSizeClass", computedClass);
            item.put("tags", benchCase.tags());
            item.put("seed", benchCase.seed());
            item.put("budget", Map.of(
                    "timeoutSeconds", benchCase.budget().timeoutSeconds(),
                    "unimprovedSecondsSpentLimit", benchCase.budget().unimprovedSecondsSpentLimit(),
                    "maximumStepCount", benchCase.budget().maximumStepCount()));
            item.put("terminationMode", terminationMode);
            item.put("terminationOutcome", terminationOutcome);
            item.put("stepCount", stepCount);
            item.put("stepTimeLimitSeconds", stepTimeLimitSeconds);
            item.put("stepDeterminismApplicable",
                    !(stepTimeLimitSeconds > 0 && "TIME_LIMIT".equals(terminationOutcome)));
            item.put("fingerprint", benchCase.fingerprint());
            item.put("caseFileSha256", benchCase.fileSha256());
            item.put("problemDigest", problemDigest);
            item.put("orderCount", orderCount);
            item.put("taskCount", taskCount);
            item.put("assignedTaskCount", assigned);
            item.put("hard", hard);
            item.put("soft", soft);
            item.put("feasible", feasible);
            item.put("durationMs", durationMs);
            item.put("firstBestMs", firstBestMs);
            item.put("timeToBestMs", timeToBestMs);
            item.put("improvements", improvements);
            item.put("hardViolationCodes", hardCodes);
            item.put("expectedHardCodes", Json.stringList(benchCase.expectation(), "expectedHardCodes", List.of()));
            item.put("evidenceRecall", recallText);
            item.put("hardMatchesExpectation", hardMatches);
            item.put("assignmentsMatchExpectation", assignmentsMatch);
            item.put("sizeClassMatchesRule", computedClass.equals(benchCase.sizeClass()));
            List<Object> points = new ArrayList<>();
            for (ScorePoint point : history) {
                points.add(Map.of(
                        "elapsedMs", point.elapsedMs(),
                        "step", point.step(),
                        "hard", point.hardScore(),
                        "soft", point.softScore()));
            }
            item.put("scoreHistory", points);
            if (error != null) {
                item.put("error", error);
            }
            return item;
        }
    }
}
