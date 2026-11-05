package com.hengce.aps.service.rbac;

import java.util.List;

/**
 * 修改用户命令（C30）：字段为 null 表示不修改；{@code roleCodes} 非 null 时全量替换。
 *
 * @param version 当前版本，必须匹配否则 40901
 * @param displayName 显示名
 * @param email 邮箱
 * @param phone 手机号
 * @param status 目标状态
 * @param roleCodes 目标角色码
 * @param password 新口令
 */
public record UserUpdateCommand(Integer version, String displayName, String email, String phone,
                                UserStatus status, List<String> roleCodes, String password) {
}
