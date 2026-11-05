package com.hengce.aps.web.controller.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengce.aps.common.api.Result;
import com.hengce.aps.web.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统管理域占位契约（system 分组）。
 *
 * <p>健康检查 {@code GET /api/v1/health} 已实现；认证/用户/审计已由 C30/C31 的
 * {@code AuthController}/{@code UserController}/{@code AuditLogController} 接管，
 * 本类只保留系统配置占位端点。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "系统管理", description = "系统配置（P0 占位契约）")
public class SystemContractController {

    /**
     * 读取系统配置。
     *
     * @return 占位响应
     */
    @Operation(summary = "读取系统配置", description = "状态：占位。")
    @GetMapping("/configs")
    public Result<Void> getConfigs() {
        throw ContractSupport.notImplemented();
    }

    /**
     * 更新系统配置。
     *
     * @param body 配置项
     * @return 占位响应
     */
    @Operation(summary = "更新系统配置", description = "状态：占位。")
    @RequiresPermission("SYS_USER")
    @PutMapping("/configs")
    public Result<Void> updateConfigs(@RequestBody(required = false) JsonNode body) {
        throw ContractSupport.notImplemented();
    }
}
