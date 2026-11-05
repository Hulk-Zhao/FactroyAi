package com.hengce.aps.web.controller.strategy;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.service.strategy.StrategyExportBundle;
import com.hengce.aps.service.strategy.StrategyImportRequest;
import com.hengce.aps.service.strategy.StrategyImportResult;
import com.hengce.aps.service.strategy.StrategyService;
import com.hengce.aps.service.strategy.StrategyView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 策略接口（C19）：CRUD、克隆、导入导出；8 条软约束权重合计必须为 100。
 *
 * <p>列表与详情均返回 {@code rules} 与 {@code ruleCount}/{@code weightSum}；
 * PUT 为全量替换 rules，版本冲突返回 40901，权重和不为 100 返回 42200。</p>
 */
@RestController
@RequestMapping("/api/v1/strategies")
@Tag(name = "策略", description = "排产策略 CRUD、克隆、导入导出（M2-SE-01）")
public class StrategyController {

    private final StrategyService strategyService;

    public StrategyController(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    /**
     * 策略列表。
     *
     * @param query 分页与排序参数
     * @param name 名称关键字（模糊）
     * @param direction 排产方向（精确）
     * @return 分页结果，每项携带完整 rules 与 ruleCount/weightSum
     */
    @Operation(summary = "策略列表", description = "每项返回 rules 与 ruleCount/weightSum；effectiveOn 按闭区间筛选生效策略。")
    @GetMapping
    public Result<PageResult<StrategyView>> list(
            @ParameterObject PageQuery query,
            @Parameter(description = "策略名称关键字") @RequestParam(required = false) String name,
            @Parameter(description = "FORWARD/BACKWARD/BOTTLENECK") @RequestParam(required = false) String direction,
            @Parameter(description = "生效日期 yyyy-MM-dd") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveOn) {
        return Result.success(strategyService.list(query, name, direction, effectiveOn));
    }

    /**
     * 策略详情。
     *
     * @param id 策略主键
     * @return 策略视图
     */
    @Operation(summary = "策略详情")
    // 排除通用导出/导入保留段，避免 GET /strategies/export 被本方法抢先匹配（C38）
    @GetMapping("/{id:^(?!export$)(?!import$).+}")
    public Result<StrategyView> get(@PathVariable UUID id) {
        return Result.success(strategyService.get(id));
    }

    /**
     * 创建策略。
     *
     * @param request 创建请求
     * @return 创建后的策略
     */
    @Operation(summary = "创建策略", description = "rules 省略时使用 8 条默认权重。")
    @RequiresPermission("SS_WRITE")
    @PostMapping
    public Result<StrategyView> create(@Valid @RequestBody StrategyCreateRequest request) {
        return Result.success(strategyService.create(request.toCommand()));
    }

    /**
     * 更新策略（全量替换 rules）。
     *
     * @param id 策略主键
     * @param request 更新请求
     * @return 更新后的策略
     */
    @Operation(summary = "更新策略", description = "全量替换 rules，需携带 version 乐观锁。")
    @RequiresPermission("SS_WRITE")
    @PutMapping("/{id}")
    public Result<StrategyView> update(@PathVariable UUID id,
                                       @Valid @RequestBody StrategyUpdateRequest request) {
        return Result.success(strategyService.update(id, request.toCommand()));
    }

    /**
     * 删除策略。
     *
     * @param id 策略主键
     * @return 空响应
     */
    @Operation(summary = "删除策略")
    @RequiresPermission("SS_WRITE")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        strategyService.delete(id);
        return Result.success();
    }

    /**
     * 克隆策略。
     *
     * @param id 源策略主键
     * @param request 克隆请求
     * @return 克隆后的策略
     */
    @Operation(summary = "克隆策略", description = "复制源策略全部规则并生成下一个业务版本。")
    @RequiresPermission("SS_WRITE")
    @PostMapping("/{id}/clone")
    public Result<StrategyView> clone(@PathVariable UUID id,
                                      @RequestBody(required = false) StrategyCloneRequest request) {
        return Result.success(strategyService.clone(id, request == null ? null : request.toCommand()));
    }

    /**
     * 导出策略。
     *
     * @param id 策略主键
     * @return {@code data = {strategy, rules, checksum}}；checksum 必出现
     */
    @Operation(summary = "导出策略",
            description = "返回 {strategy, rules, checksum}；checksum 为规范化 {strategy, rules} 的 SHA-256。")
    @GetMapping("/{id}/export")
    public Result<StrategyExportBundle> export(@PathVariable UUID id) {
        return Result.success(strategyService.export(id));
    }

    /**
     * 导入策略。
     *
     * @param request 导入请求 {@code {payload, dryRun}}
     * @return {@code {valid, errors[], strategyId}}；dryRun=true 不落库
     */
    @Operation(summary = "导入策略",
            description = "请求 {payload, dryRun}；dryRun=true 只校验不落库。checksum 缺失/非法/不匹配按 42200 拒绝。")
    @RequiresPermission("SS_WRITE")
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<StrategyImportResult> importStrategy(@RequestBody StrategyImportRequest request) {
        return Result.success(strategyService.importStrategy(request));
    }
}
