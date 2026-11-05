package com.hengce.aps.web.dto.rbac;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 创建角色请求（C30 / 25.1.5）。
 *
 * @param code 角色码（大写）
 * @param name 名称
 * @param description 描述
 * @param permissionCodes 权限码
 */
public record RoleCreateRequest(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String code,
                                @NotBlank @Size(max = 64) String name,
                                @Size(max = 255) String description,
                                List<String> permissionCodes) {
}
