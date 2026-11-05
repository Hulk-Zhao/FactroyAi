package com.hengce.aps.service.strategy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 约束 DSL 版本视图。
 *
 * @param id 版本主键
 * @param code DSL 标识
 * @param versionNo 版本号
 * @param status 状态：DRAFT / ACTIVE / ROLLBACK
 * @param checksum 规范化 YAML 的 SHA-256
 * @param rollbackOf 回滚来源版本主键
 * @param effectiveFrom 生效时刻
 * @param yamlText 规范化 YAML
 * @param compiledSpec 编译后的约束快照
 * @param createdAt 创建时间
 */
public record ConstraintDslView(
        UUID id,
        String code,
        Integer versionNo,
        String status,
        String checksum,
        UUID rollbackOf,
        OffsetDateTime effectiveFrom,
        String yamlText,
        List<Map<String, Object>> compiledSpec,
        OffsetDateTime createdAt) {
}
