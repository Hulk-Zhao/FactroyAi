package com.hengce.aps.verify;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hengce.aps.admin.ApsAdminApplication;
import com.hengce.aps.common.tenant.TenantContext;
import com.hengce.aps.infrastructure.persistence.audit.AuditContext;
import com.hengce.aps.infrastructure.persistence.mapper.VerifyMaterialMapper;
import com.hengce.aps.infrastructure.persistence.mapper.VerifyRolePermissionMapper;
import com.hengce.aps.infrastructure.persistence.mapper.VerifyTenantMapper;
import com.hengce.aps.infrastructure.persistence.mapper.VerifyUserRoleMapper;
import com.hengce.aps.verify.entity.VerifyMaterialEntity;
import com.hengce.aps.verify.entity.VerifyTenantEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 本地真库回归（test scope，无 Docker）。
 *
 * <p><b>仅手工运行，不参与自动化测试</b>：类名不匹配 Surefire 默认 include（{@code Test*}/{@code *Test}/
 * {@code *Tests}/{@code *TestCase}），{@code mvn test} 不会触发本类；只能通过
 * {@code scripts/local-verify.ps1} 或显式 {@code java -cp} 手工执行。</p>
 *
 * <p>直接复用生产的 Spring Boot 装配与 MyBatis-Plus 拦截器链，对 aps_local 库逐条验证：
 * 租户拦截（SELECT/UPDATE/DELETE/INSERT）、忽略表、乐观锁、逻辑删除、JSONB 往返、审计填充。
 * 不注册任何测试侧类型处理器，UUID/Serializable 处理器完全来自生产
 * {@code MybatisTypeHandlerConfiguration}。每项输出 PASS/FAIL 与 SQL/JDBC 证据；
 * 存在 FAIL 时进程退出码为 2。</p>
 *
 * <p>运行方式见 docs/deploy/local-verify.md（由 scripts/local-verify.ps1 调用）。</p>
 */
public final class LocalDbRegressionHarness {

    private static final UUID TENANT_DEFAULT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID TENANT_SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final UUID ADMIN_USER = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private static final String RUN_TAG = "VR-" + System.currentTimeMillis();

    private static int passed;

    private static int failed;

    private LocalDbRegressionHarness() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ApsAdminApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(args)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            VerifyMaterialMapper materialMapper = context.getBean(VerifyMaterialMapper.class);
            VerifyTenantMapper tenantMapper = context.getBean(VerifyTenantMapper.class);
            VerifyRolePermissionMapper rolePermissionMapper = context.getBean(VerifyRolePermissionMapper.class);
            VerifyUserRoleMapper userRoleMapper = context.getBean(VerifyUserRoleMapper.class);

            TenantContext.clear();
            AuditContext.setCurrentUserId(ADMIN_USER);

            try {
                ensureSecondTenant(tenantMapper);
                checkIgnoredTables(tenantMapper, rolePermissionMapper, userRoleMapper);

                VerifyMaterialEntity material = insertDefaultTenantMaterial(materialMapper, jdbc);
                checkInsertTenantAndAuditFill(material, jdbc);
                checkTimestampColumnTypes(jdbc);

                checkSelectTenantIsolation(materialMapper, material);
                VerifyMaterialEntity second = insertSecondTenantMaterial(materialMapper, jdbc);
                checkUpdateTenantFilter(materialMapper, second);
                checkDeleteTenantFilter(materialMapper, second, jdbc);

                checkOptimisticLock(materialMapper, material, jdbc);
                checkLogicDelete(materialMapper, jdbc);
                checkJsonbRoundTrip(materialMapper, jdbc, material);
                checkAuditUpdate(materialMapper, material, jdbc);
            }
            catch (RuntimeException exception) {
                failed++;
                System.out.println("[FAIL] harness 中断：" + exception.getMessage());
                exception.printStackTrace(System.out);
            }
            finally {
                TenantContext.clear();
                AuditContext.clear();
            }
        }

        System.out.println();
        System.out.printf("RESULT passed=%d failed=%d%n", passed, failed);
        System.exit(failed == 0 ? 0 : 2);
    }

    private static void ensureSecondTenant(VerifyTenantMapper tenantMapper) {
        TenantContext.clear();
        if (tenantMapper.selectOne(new QueryWrapper<VerifyTenantEntity>().eq("id", TENANT_SECOND)) == null) {
            VerifyTenantEntity tenant = new VerifyTenantEntity();
            tenant.setId(TENANT_SECOND);
            tenant.setCode("VERIFY-T2");
            tenant.setName("本地回归第二租户");
            tenant.setStatus("ACTIVE");
            tenantMapper.insert(tenant);
        }
        pass("准备第二租户 " + TENANT_SECOND + "（INSERT 未追加 tenant_id 列）");
    }

    private static void checkIgnoredTables(VerifyTenantMapper tenantMapper,
                                           VerifyRolePermissionMapper rolePermissionMapper,
                                           VerifyUserRoleMapper userRoleMapper) {
        TenantContext.setTenantId(TENANT_SECOND);
        long tenants = tenantMapper.selectCount(null);
        long rolePermissions = rolePermissionMapper.selectCount(null);
        long userRoles = userRoleMapper.selectCount(null);

        if (tenants >= 2 && rolePermissions > 0 && userRoles > 0) {
            pass("忽略表 tenant/role_permission/user_role 未被注入 tenant_id 条件"
                    + "（count: tenant=" + tenants + ", role_permission=" + rolePermissions
                    + ", user_role=" + userRoles + "）");
        }
        else {
            fail("忽略表计数异常：tenant=" + tenants + ", role_permission=" + rolePermissions
                    + ", user_role=" + userRoles);
        }
        TenantContext.clear();
    }

    private static VerifyMaterialEntity insertDefaultTenantMaterial(VerifyMaterialMapper mapper, JdbcTemplate jdbc) {
        TenantContext.clear();
        VerifyMaterialEntity material = newMaterial(RUN_TAG + "-T1", "默认租户物料");
        mapper.insert(material);

        UUID tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM material WHERE id = ?", UUID.class, material.getId());
        if (TENANT_DEFAULT.equals(tenantId)) {
            pass("默认租户 INSERT 自动补 tenant_id=" + tenantId + "（未显式赋值）");
        }
        else {
            fail("默认租户 INSERT 的 tenant_id 期望 " + TENANT_DEFAULT + "，实际 " + tenantId);
        }
        return material;
    }

    private static void checkInsertTenantAndAuditFill(VerifyMaterialEntity material, JdbcTemplate jdbc) {
        Boolean createdByOk = jdbc.queryForObject(
                "SELECT created_by = ? FROM material WHERE id = ?", Boolean.class, ADMIN_USER, material.getId());
        Integer notNullAudit = jdbc.queryForObject(
                "SELECT (created_at IS NOT NULL AND updated_at IS NOT NULL)::int FROM material WHERE id = ?",
                Integer.class, material.getId());

        if (Boolean.TRUE.equals(createdByOk) && notNullAudit != null && notNullAudit == 1) {
            pass("审计填充 created_at/updated_at/created_by 生效（created_by=" + ADMIN_USER + "）");
        }
        else {
            fail("审计字段填充不完整：created_by_ok=" + createdByOk + ", audit_not_null=" + notNullAudit);
        }
    }

    private static void checkTimestampColumnTypes(JdbcTemplate jdbc) {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='material' "
                        + "AND column_name IN ('created_at','updated_at') ORDER BY column_name");
        boolean allTz = !columns.isEmpty() && columns.stream()
                .allMatch(row -> "timestamp with time zone".equals(row.get("data_type")));
        if (allTz) {
            pass("created_at/updated_at 为 TIMESTAMPTZ：" + columns);
        }
        else {
            fail("时间列类型不是 timestamptz：" + columns);
        }
    }

    private static void checkSelectTenantIsolation(VerifyMaterialMapper mapper, VerifyMaterialEntity material) {
        TenantContext.setTenantId(TENANT_SECOND);
        VerifyMaterialEntity invisible = findById(mapper, material.getId());
        long count = mapper.selectCount(new QueryWrapper<VerifyMaterialEntity>().eq("code", material.getCode()));

        if (invisible == null && count == 0) {
            pass("SELECT 自动追加 tenant_id：第二租户上下文读不到默认租户物料（count=" + count + "）");
        }
        else {
            fail("SELECT 未按租户隔离：selectById=" + (invisible == null ? "null" : "命中") + ", count=" + count);
        }
        TenantContext.clear();
    }

    private static VerifyMaterialEntity insertSecondTenantMaterial(VerifyMaterialMapper mapper, JdbcTemplate jdbc) {
        TenantContext.setTenantId(TENANT_SECOND);
        VerifyMaterialEntity material = newMaterial(RUN_TAG + "-T2", "第二租户物料");
        mapper.insert(material);

        UUID tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM material WHERE id = ?", UUID.class, material.getId());
        if (TENANT_SECOND.equals(tenantId)) {
            pass("显式租户上下文 INSERT 落库 tenant_id=" + tenantId);
        }
        else {
            fail("第二租户 INSERT 的 tenant_id 期望 " + TENANT_SECOND + "，实际 " + tenantId);
        }
        return material;
    }

    private static void checkUpdateTenantFilter(VerifyMaterialMapper mapper, VerifyMaterialEntity second) {
        VerifyMaterialEntity cross = new VerifyMaterialEntity();
        cross.setId(second.getId());
        cross.setVersion(1);
        cross.setName("跨租户改名");
        TenantContext.clear();
        int crossRows = mapper.updateById(cross);

        VerifyMaterialEntity own = new VerifyMaterialEntity();
        own.setId(second.getId());
        own.setVersion(1);
        own.setName("同租户改名");
        TenantContext.setTenantId(TENANT_SECOND);
        int ownRows = mapper.updateById(own);

        if (crossRows == 0 && ownRows == 1) {
            pass("UPDATE 自动追加 tenant_id：默认租户改第二租户行返回 0，第二租户改自身返回 1");
        }
        else {
            fail("UPDATE 租户过滤异常：跨租户 rows=" + crossRows + ", 同租户 rows=" + ownRows);
        }
        TenantContext.clear();
    }

    private static void checkDeleteTenantFilter(VerifyMaterialMapper mapper, VerifyMaterialEntity second,
                                                JdbcTemplate jdbc) {
        TenantContext.clear();
        int crossRows = mapper.delete(new QueryWrapper<VerifyMaterialEntity>().eq("id", second.getId()));
        Integer stillAlive = jdbc.queryForObject(
                "SELECT count(*) FROM material WHERE id = ? AND deleted = false", Integer.class, second.getId());
        if (crossRows == 0 && stillAlive != null && stillAlive == 1) {
            pass("DELETE 自动追加 tenant_id：跨租户删除返回 0，行仍在（未软删）");
        }
        else {
            fail("DELETE 租户过滤异常：rows=" + crossRows + ", alive=" + stillAlive);
        }
        TenantContext.clear();
    }

    private static void checkOptimisticLock(VerifyMaterialMapper mapper, VerifyMaterialEntity material,
                                            JdbcTemplate jdbc) {
        TenantContext.clear();
        int firstRows = mapper.updateById(versioned(material.getId(), 1, "乐观锁-第一次"));
        Integer versionAfter = jdbc.queryForObject(
                "SELECT version FROM material WHERE id = ?", Integer.class, material.getId());
        int staleRows = mapper.updateById(versioned(material.getId(), 1, "乐观锁-过期版本"));

        if (firstRows == 1 && versionAfter != null && versionAfter == 2 && staleRows == 0) {
            pass("乐观锁：首次 UPDATE rows=1 且 version 1→2；过期 version=1 再更新 rows=0");
        }
        else {
            fail("乐观锁异常：firstRows=" + firstRows + ", versionAfter=" + versionAfter
                    + ", staleRows=" + staleRows);
        }
    }

    private static void checkLogicDelete(VerifyMaterialMapper mapper, JdbcTemplate jdbc) {
        TenantContext.clear();
        VerifyMaterialEntity material = newMaterial(RUN_TAG + "-DEL", "逻辑删除物料");
        mapper.insert(material);

        int deleteRows = mapper.delete(new QueryWrapper<VerifyMaterialEntity>().eq("id", material.getId()));
        VerifyMaterialEntity afterDelete = findById(mapper, material.getId());
        Boolean deletedFlag = jdbc.queryForObject(
                "SELECT deleted FROM material WHERE id = ?", Boolean.class, material.getId());

        VerifyMaterialEntity reuse = newMaterial(material.getCode(), "删除后复用同一 code");
        int reuseRows = mapper.insert(reuse);

        if (deleteRows == 1 && afterDelete == null && Boolean.TRUE.equals(deletedFlag) && reuseRows == 1) {
            pass("逻辑删除：deleteById 置 deleted=true，查询不可见，同 code 可复用（部分唯一索引）");
        }
        else {
            fail("逻辑删除异常：deleteRows=" + deleteRows + ", selectAfter=" + afterDelete
                    + ", deleted=" + deletedFlag + ", reuseRows=" + reuseRows);
        }
    }

    private static void checkJsonbRoundTrip(VerifyMaterialMapper mapper, JdbcTemplate jdbc,
                                            VerifyMaterialEntity material) {
        TenantContext.clear();
        String jsonType = jdbc.queryForObject(
                "SELECT jsonb_typeof(ext_attrs) FROM material WHERE id = ?", String.class, material.getId());
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("color", "black");
        attrs.put("brand", "TI");
        attrs.put("tags", List.of("rohs", "reach"));
        attrs.put("weightKg", new BigDecimal("0.35"));

        VerifyMaterialEntity update = versioned(material.getId(), 2, material.getName());
        update.setExtAttrs(attrs);
        int rows = mapper.updateById(update);
        VerifyMaterialEntity readBack = mapper.selectExtAttrs((UUID) material.getId());

        Map<String, Object> readAttrs = readBack == null ? null : readBack.getExtAttrs();
        boolean roundTrip = readAttrs != null
                && "black".equals(readAttrs.get("color"))
                && "TI".equals(readAttrs.get("brand"))
                && readAttrs.get("tags") instanceof List<?> tags && tags.size() == 2;

        if ("object".equals(jsonType) && rows == 1 && roundTrip) {
            pass("JSONB：写前 jsonb_typeof(ext_attrs)=object；Types.OTHER 写入后可完整读回 " + readAttrs);
        }
        else {
            fail("JSONB 往返异常：typeOf=" + jsonType + ", rows=" + rows + ", readBack=" + readAttrs);
        }
    }

    private static void checkAuditUpdate(VerifyMaterialMapper mapper, VerifyMaterialEntity material,
                                         JdbcTemplate jdbc) {
        TenantContext.clear();
        VerifyMaterialEntity before = findById(mapper, material.getId());
        OffsetDateTime createdAt = before.getCreatedAt();
        OffsetDateTime updatedAtBefore = before.getUpdatedAt();
        int newVersion = before.getVersion();

        int rows = mapper.updateById(versioned(material.getId(), newVersion, "审计刷新"));
        VerifyMaterialEntity after = findById(mapper, material.getId());

        boolean refreshed = after.getUpdatedAt().isAfter(updatedAtBefore);
        boolean createdKept = after.getCreatedAt().equals(createdAt);
        if (rows == 1 && refreshed && createdKept) {
            pass("审计 UPDATE 刷新 updated_at（" + updatedAtBefore + " -> " + after.getUpdatedAt()
                    + "），created_at 不变");
        }
        else {
            fail("审计刷新异常：rows=" + rows + ", updated_at " + updatedAtBefore + " -> "
                    + (after == null ? null : after.getUpdatedAt()));
        }
    }

    private static VerifyMaterialEntity findById(VerifyMaterialMapper mapper, Object id) {
        return mapper.selectOne(new QueryWrapper<VerifyMaterialEntity>().eq("id", id));
    }

    private static VerifyMaterialEntity newMaterial(String code, String name) {
        VerifyMaterialEntity material = new VerifyMaterialEntity();
        material.setId(UUID.randomUUID());
        material.setCode(code);
        material.setName(name);
        material.setType("RAW");
        material.setUnit("PCS");
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("color", "black");
        attrs.put("tags", List.of("rohs", "reach"));
        material.setExtAttrs(attrs);
        return material;
    }

    private static VerifyMaterialEntity versioned(Object id, int version, String name) {
        VerifyMaterialEntity entity = new VerifyMaterialEntity();
        entity.setId((UUID) id);
        entity.setVersion(version);
        entity.setName(name);
        return entity;
    }

    private static void pass(String message) {
        passed++;
        System.out.println("[PASS] " + message);
    }

    private static void fail(String message) {
        failed++;
        System.out.println("[FAIL] " + message);
    }
}
