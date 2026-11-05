package com.hengce.aps.web.controller;

import com.hengce.aps.common.api.Result;
import com.hengce.aps.common.constant.ApsConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查端点。
 */
@RestController
@RequestMapping(ApsConstants.API_PREFIX)
@Tag(name = "系统管理", description = "健康检查")
public class HealthController {

    /**
     * 服务健康状态。
     *
     * @return 健康状态
     */
    @Operation(summary = "健康检查", description = "已实现。返回 {code,message,data,traceId}。")
    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of("status", "UP", "service", "aps-admin"));
    }
}
