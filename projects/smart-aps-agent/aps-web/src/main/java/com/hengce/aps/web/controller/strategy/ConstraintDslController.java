package com.hengce.aps.web.controller.strategy;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.service.strategy.ConstraintDslCreateCommand;
import com.hengce.aps.service.strategy.ConstraintDslService;
import com.hengce.aps.service.strategy.ConstraintDslView;
import com.hengce.aps.solver.dsl.DslValidationReport;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 约束 DSL 接口（C19）：校验、升版、激活与回滚。
 *
 * <p>校验接口返回结构化 422xx 报告；非法 DSL 写入返回 42200，message 内附带逐条 422xx 明细。</p>
 */
@RestController
@RequestMapping("/api/v1/constraint-dsls")
@Tag(name = "约束 DSL", description = "DSL 校验、版本、激活与回滚（M2-SE-02）")
public class ConstraintDslController {

    private final ConstraintDslService constraintDslService;

    public ConstraintDslController(ConstraintDslService constraintDslService) {
        this.constraintDslService = constraintDslService;
    }

    /**
     * 只校验 DSL，不落库。
     *
     * @param request 校验请求
     * @return 422xx 校验报告
     */
    @Operation(summary = "校验 DSL", description = "返回 valid 与逐条 422xx 问题，不落库。")
    @RequiresPermission("SS_READ")
    @PostMapping("/validate")
    public Result<DslValidationReport> validate(@Valid @RequestBody ConstraintDslValidateRequest request) {
        return Result.success(constraintDslService.validate(request.yamlText()));
    }

    /**
     * 创建 DSL 草稿版本。
     *
     * @param request 创建请求
     * @return 新版本
     */
    @Operation(summary = "创建 DSL 版本", description = "校验通过后写入 DRAFT 版本，YAML 规范化为 constraints 数组。")
    @RequiresPermission("SS_WRITE")
    @PostMapping
    public Result<ConstraintDslView> create(@Valid @RequestBody ConstraintDslCreateRequest request) {
        return Result.success(constraintDslService.create(
                new ConstraintDslCreateCommand(request.code(), request.yamlText())));
    }

    /**
     * 查询某标识的全部版本。
     *
     * @param code DSL 标识
     * @return 版本列表（版本号倒序）
     */
    @Operation(summary = "DSL 版本列表")
    @GetMapping
    public Result<List<ConstraintDslView>> versions(
            @Parameter(description = "DSL 标识") @RequestParam String code) {
        return Result.success(constraintDslService.versions(code));
    }

    /**
     * 查询单个版本。
     *
     * @param id 版本主键
     * @return 版本视图
     */
    @Operation(summary = "DSL 版本详情")
    @GetMapping("/{id}")
    public Result<ConstraintDslView> get(@PathVariable UUID id) {
        return Result.success(constraintDslService.get(id));
    }

    /**
     * 激活版本。
     *
     * @param id 版本主键
     * @return 激活后的版本
     */
    @Operation(summary = "激活 DSL 版本", description = "同一 code 仅一个 ACTIVE；失败自动恢复上一激活版本。")
    @RequiresPermission("SS_WRITE")
    @PostMapping("/{id}/activate")
    public Result<ConstraintDslView> activate(@PathVariable UUID id) {
        return Result.success(constraintDslService.activate(id));
    }

    /**
     * 回滚到历史版本。
     *
     * @param id 目标历史版本主键
     * @return 回滚后的版本
     */
    @Operation(summary = "回滚 DSL 版本", description = "当前 ACTIVE 置 ROLLBACK，目标版本置 ACTIVE 并记录 rollback_of。")
    @RequiresPermission("SS_WRITE")
    @PostMapping("/{id}/rollback")
    public Result<ConstraintDslView> rollback(@PathVariable UUID id) {
        return Result.success(constraintDslService.rollback(id));
    }
}
