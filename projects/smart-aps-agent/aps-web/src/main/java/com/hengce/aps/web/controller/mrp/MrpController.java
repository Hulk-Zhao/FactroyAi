package com.hengce.aps.web.controller.mrp;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.service.mrp.MrpAnalysisCommand;
import com.hengce.aps.service.mrp.MrpAnalysisResponse;
import com.hengce.aps.service.mrp.MrpAnalysisService;
import com.hengce.aps.service.mrp.MrpSuggestionQuery;
import com.hengce.aps.service.mrp.MrpSuggestionService;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MRP 齐套分析与采购建议接口（M2 BD-01 / C25，M3 W6 / C34）。
 *
 * <p>{@code POST /api/v1/mrp/analysis}：输入 sandboxId、可选 orderNos、includeSuggestions、
 * blocking；输出 FULL / PARTIAL / SHORTAGE、按订单与按物料的缺料明细及 earliestRequiredAt。</p>
 *
 * <p>{@code GET /api/v1/mrp/suggestions}：sandboxId 必填，orderNos/horizonDays 可选；
 * 复用分析与 {@code MrpShortage} 字段，固定按 materialCode 升序分页。</p>
 */
@RestController
@RequestMapping("/api/v1/mrp")
@Tag(name = "MRP", description = "BOM 多级展开、库存/在途齐套、缺料报告与采购建议（BD-01/C34 已实现）")
public class MrpController {

    private final MrpAnalysisService mrpAnalysisService;

    private final MrpSuggestionService mrpSuggestionService;

    /**
     * 构造控制器。
     *
     * @param mrpAnalysisService MRP 分析服务
     * @param mrpSuggestionService MRP 采购建议服务
     */
    public MrpController(MrpAnalysisService mrpAnalysisService, MrpSuggestionService mrpSuggestionService) {
        this.mrpAnalysisService = mrpAnalysisService;
        this.mrpSuggestionService = mrpSuggestionService;
    }

    /**
     * 执行 MRP 齐套分析。
     *
     * @param request 分析请求
     * @return 齐套分析结果
     */
    @RequiresPermission("MD_READ")
    @Operation(summary = "MRP 齐套分析",
            description = "状态：已实现。缺料返回完整明细；blocking=true 时 blocked 标记是否拦截排产。")
    @PostMapping("/analysis")
    public Result<MrpAnalysisResponse> analyze(@Valid @RequestBody MrpAnalysisRequest request) {
        return Result.success(mrpAnalysisService.analyze(request.toCommand()));
    }

    /**
     * 查询采购建议。
     *
     * @param sandboxId 排产场景标识，必填
     * @param orderNos 逗号分隔的订单号，空表示全部可排产订单
     * @param horizonDays 需求时间窗口 1–365 天，默认 30
     * @param pageQuery 分页参数；sort 不开放
     * @return 按 materialCode 升序的分页建议
     */
    @RequiresPermission("MD_READ")
    @Operation(summary = "MRP 采购建议",
            description = "状态：已实现（C34）。字段与 MrpShortage 一致但不含库存明细；"
                    + "固定 materialCode ASC；sort 传值返回 40000。")
    @GetMapping("/suggestions")
    public Result<PageResult<MrpAnalysisResponse.MrpSuggestionView>> suggestions(
            @RequestParam(required = false) String sandboxId,
            @RequestParam(required = false) String orderNos,
            @RequestParam(required = false) Integer horizonDays,
            @ParameterObject PageQuery pageQuery) {
        return Result.success(mrpSuggestionService.suggest(
                new MrpSuggestionQuery(parseSandboxId(sandboxId), splitOrderNos(orderNos), horizonDays),
                pageQuery));
    }

    private UUID parseSandboxId(String sandboxId) {
        if (sandboxId == null || sandboxId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sandboxId 不能为空");
        }
        try {
            return UUID.fromString(sandboxId.trim());
        } catch (IllegalArgumentException exception) {
            throw new BizException(ErrorCode.BAD_REQUEST, "sandboxId 格式不正确");
        }
    }

    private List<String> splitOrderNos(String orderNos) {
        if (orderNos == null || orderNos.isBlank()) {
            return List.of();
        }
        return Arrays.stream(orderNos.split("[,，]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /**
     * MRP 齐套分析请求体。
     *
     * @param sandboxId 排产场景标识
     * @param orderNos 指定订单号，空表示全部可排产订单
     * @param includeSuggestions 是否输出补料建议，默认 true
     * @param blocking 是否按缺料阻塞排产，默认 false
     */
    public record MrpAnalysisRequest(@NotNull UUID sandboxId, List<String> orderNos,
                                     Boolean includeSuggestions, Boolean blocking) {

        /**
         * 转换为服务层命令。
         *
         * @return 分析命令
         */
        MrpAnalysisCommand toCommand() {
            return new MrpAnalysisCommand(sandboxId, orderNos,
                    includeSuggestions == null || includeSuggestions,
                    blocking != null && blocking);
        }
    }
}
