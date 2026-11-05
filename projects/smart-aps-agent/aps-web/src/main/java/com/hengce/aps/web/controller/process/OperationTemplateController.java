package com.hengce.aps.web.controller.process;

import com.hengce.aps.common.api.PageQuery;
import com.hengce.aps.common.api.PageResult;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import com.hengce.aps.infrastructure.persistence.process.OperationTemplate;
import com.hengce.aps.service.process.template.OperationTemplateCommand;
import com.hengce.aps.service.process.template.OperationTemplateService;
import com.hengce.aps.web.controller.process.dto.OperationTemplateRequest;
import com.hengce.aps.web.controller.process.dto.OperationTemplateView;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工序模版 API。
 *
 * <p>路径参数语义按契约 C6 使用 UUID；统一返回 {@link Result} / {@link PageResult}。
 * 写接口的幂等键由全局 {@code IdempotencyInterceptor} 处理，无需在方法签名声明。
 * 本组端点不属于 materials/boms/routings/orders 任一静态分组，可在聚合文档
 * {@code /v3/api-docs} 中查看。</p>
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX + "/operation-templates")
@Tag(name = "工序模版", description = "可复用工序模版 CRUD（FR-PR-001）")
public class OperationTemplateController {

    private final OperationTemplateService operationTemplateService;

    /**
     * 构造控制器。
     *
     * @param operationTemplateService 工序模版服务
     */
    public OperationTemplateController(OperationTemplateService operationTemplateService) {
        this.operationTemplateService = operationTemplateService;
    }

    /**
     * 工序模版列表。
     *
     * @param query 分页与排序参数
     * @param code 编码模糊条件
     * @param name 名称模糊条件
     * @param resourceType 主资源类型
     * @return 分页结果
     */
    @Operation(summary = "工序模版列表", description = "已实现。支持 code/name/主资源类型过滤与 page/size/sort。")
    @GetMapping
    public Result<PageResult<OperationTemplateView>> list(@ParameterObject PageQuery query,
                                                          @RequestParam(required = false) String code,
                                                          @RequestParam(required = false) String name,
                                                          @RequestParam(required = false) String resourceType) {
        PageResult<OperationTemplate> page = operationTemplateService.list(query, code, name, resourceType);
        List<OperationTemplateView> items = page.items().stream().map(OperationTemplateView::from).toList();
        return Result.success(new PageResult<>(items, page.page(), page.size(), page.total(), page.totalPages()));
    }

    /**
     * 工序模版详情。
     *
     * @param id 模版主键
     * @return 工序模版
     */
    @Operation(summary = "工序模版详情", description = "已实现。路径参数为 UUID；不存在返回 40400。")
    // 排除通用导出/导入保留段，避免 GET /operation-templates/export 被本方法抢先匹配（C38）
    @GetMapping("/{id:^(?!export$)(?!import$).+}")
    public Result<OperationTemplateView> detail(@PathVariable UUID id) {
        return Result.success(OperationTemplateView.from(operationTemplateService.get(id)));
    }

    /**
     * 新建工序模版。
     *
     * @param request 新建请求
     * @return 新建后的工序模版
     */
    @Operation(summary = "新建工序模版", description = "已实现。编码+业务版本重复返回 40901。")
    @RequiresPermission("MD_WRITE")
    @PostMapping
    public Result<OperationTemplateView> create(@Valid @RequestBody OperationTemplateRequest request) {
        return Result.success(OperationTemplateView.from(operationTemplateService.create(toCommand(request))));
    }

    /**
     * 更新工序模版。
     *
     * @param id 模版主键
     * @param request 更新请求，version 必填
     * @return 更新后的工序模版
     */
    @Operation(summary = "更新工序模版", description = "已实现。version 用于乐观锁，冲突返回 40901。")
    @RequiresPermission("MD_WRITE")
    @PutMapping("/{id}")
    public Result<OperationTemplateView> update(@PathVariable UUID id,
                                                @Valid @RequestBody OperationTemplateRequest request) {
        return Result.success(OperationTemplateView.from(
                operationTemplateService.update(id, toCommand(request))));
    }

    /**
     * 删除工序模版。
     *
     * @param id 模版主键
     * @return 空数据成功响应
     */
    @Operation(summary = "删除工序模版", description = "已实现。逻辑删除；被工艺路线节点引用返回 42200。")
    @RequiresPermission("MD_WRITE")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        operationTemplateService.delete(id);
        return Result.success();
    }

    private static OperationTemplateCommand toCommand(OperationTemplateRequest request) {
        return new OperationTemplateCommand(
                request.code(),
                request.name(),
                request.primaryResourceId(),
                request.primaryResourceGroupId(),
                request.secondaryResourceIds(),
                request.inputMaterialIds(),
                request.outputMaterialId(),
                request.standardSeconds(),
                request.setupSeconds(),
                request.teardownSeconds(),
                request.templateVersion(),
                request.version());
    }
}
