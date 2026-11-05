package com.hengce.aps.web.controller.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 物料与主数据域占位契约（materials 分组）。
 *
 * <p>物料 CRUD（{@code /materials}、{@code /materials/{code}}）由
 * {@code MaterialController} 实现，日历 CRUD（{@code /calendars}、{@code /calendars/{id}}）
 * 由 {@code CalendarController} 实现，资源（{@code /resources}）由 {@code ResourceController}
 * 实现，BOM（{@code /boms}）由 {@code BomController} 实现，工艺路线（{@code /routings}）
 * 由 {@code RoutingController} 实现，MRP 分析（{@code /mrp/analysis}）与采购建议
 * （{@code /mrp/suggestions}）由 {@code MrpController} 实现，Excel 导入
 * （{@code POST /materials/import}）由 {@code ImportExportController} 实现
 * （{@code multipart/form-data}），对应占位方法已删除——此前 {@code /materials/import} 的
 * {@code JsonNode} 占位不声明 consumes，运行期会静默抢路由并导致 415。
 * 其余未实现端点（{@code /calendars/{id}/available}、{@code /resources/{code}/maintenance}）
 * 仍返回 501 / code=50100。字段契约见 {@code docs/api/openapi/materials.yaml}。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "物料与主数据", description = "物料、BOM、资源、日历、工艺路线、MRP（P0 占位契约）")
public class MaterialsContractController {

    /**
     * 可用工时查询（未实现，保留 501 占位）。
     *
     * @param id 日历主键 UUID
     * @param from 起始时间（ISO-8601 UTC）
     * @param to 结束时间（ISO-8601 UTC）
     * @return 占位响应
     */
    @Operation(summary = "可用工时", description = "状态：占位。时间参数为 ISO-8601 UTC。")
    @GetMapping("/calendars/{id}/available")
    public Result<Void> availableWorkingTime(@PathVariable UUID id,
                                             @RequestParam String from,
                                             @RequestParam String to) {
        throw ContractSupport.notImplemented();
    }

    /**
     * 设置资源维护计划。
     *
     * @param code 资源业务编码
     * @param body 维护计划
     * @return 占位响应
     */
    @Operation(summary = "资源维护计划", description = "状态：占位。")
    @RequiresPermission("MD_WRITE")
    @PostMapping("/resources/{code}/maintenance")
    public Result<Void> maintainResource(@PathVariable String code,
                                         @RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }
}
