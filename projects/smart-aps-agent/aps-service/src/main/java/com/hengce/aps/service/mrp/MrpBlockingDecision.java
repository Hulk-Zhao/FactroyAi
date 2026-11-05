package com.hengce.aps.service.mrp;

import java.util.List;

/**
 * 排产侧 MRP 阻塞判定结果，供 W4 在求解装配阶段调用。
 *
 * @param blocked 是否阻塞排产
 * @param status 齐套分级
 * @param shortageMaterialCodes 缺料物料编码（去重，按编码升序）
 * @param message 面向用户的阻塞说明
 */
public record MrpBlockingDecision(boolean blocked, MrpStatus status,
                                  List<String> shortageMaterialCodes, String message) {
}
