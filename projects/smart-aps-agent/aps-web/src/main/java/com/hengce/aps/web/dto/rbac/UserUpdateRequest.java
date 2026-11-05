package com.hengce.aps.web.dto.rbac;

import com.hengce.aps.service.rbac.UserStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 修改用户请求（C30 / 25.1.4）；字段为 null 表示不修改，roleCodes 非 null 时全量替换。
 *
 * <p>{@code version} 缺省由服务层判定为 40901（契约要求，不能用参数校验 40000 拦截）。</p>
 *
 * @param version 乐观锁版本
 * @param displayName 显示名
 * @param email 邮箱
 * @param phone 手机号
 * @param status 状态
 * @param roleCodes 角色码
 * @param password 新口令（bcrypt 存储）
 */
public record UserUpdateRequest(Integer version,
                                @Size(max = 128) String displayName,
                                @Email @Size(max = 128) String email,
                                @Size(max = 32) String phone,
                                UserStatus status,
                                List<String> roleCodes,
                                @Size(max = 128) String password) {
}
