package com.hengce.aps.service.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hengce.aps.common.exception.BizException;
import com.hengce.aps.common.exception.ErrorCode;
import com.hengce.aps.infrastructure.persistence.bom.Bom;
import com.hengce.aps.infrastructure.persistence.bom.BomMapper;
import com.hengce.aps.service.masterdata.bom.BomView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BOM 激活并发真库用例（D-02 回归）：用一条未提交的 ACTIVE 行占住
 * {@code uq_bom_one_active_per_parent}，让服务的 updateById 在唯一索引上等待，
 * 提交后触发唯一约束冲突，断言映射为 {@code 40901} 而非 500。
 *
 * <p>为什么能稳定复现：持有连接在独立事务中把另一个同父件 BOM 改为 ACTIVE 但不提交，
 * 服务的“收敛旧 ACTIVE”语句在 READ COMMITTED 下看不到该未提交行（更新 0 行），
 * 随后的 updateById 必然在同一索引键上等待，直到持有事务提交才以 23505 失败。</p>
 */
@EnabledIfEnvironmentVariable(named = "APS_TEST_DB_ENABLED", matches = "(?i)true")
class BomActivationConcurrencyRealDbIntegrationTest extends AbstractRealDbIntegrationTest {

    private static final long BLOCK_WAIT_MILLIS = 10_000L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BomMapper bomMapper;

    @Test
    void concurrentActivationMapsUniqueViolationTo40901() throws Exception {
        String parent = tag("MAT-P-RACE");
        String child = tag("MAT-C-RACE");
        createMaterial(parent);
        createMaterial(child);
        BomView target = createBom(parent, "V1", child);
        BomView blocker = createBom(parent, "V2", child);
        UUID targetId = target.id();
        UUID parentId = materialId(parent);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "UPDATE bom SET status = 'ACTIVE' WHERE id = ?")) {
                statement.setObject(1, blocker.id());
                statement.executeUpdate();
            }

            Future<BomView> future = executor.submit(() -> bomService.activate(targetId));
            awaitBlockedOn("UPDATE bom", holder);
            holder.commit();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(10, TimeUnit.SECONDS));
            Throwable cause = failure.getCause();
            assertInstanceOf(BizException.class, cause,
                    "并发唯一冲突必须以业务异常映射，实际为 " + cause.getClass().getName()
                            + ": " + cause.getMessage());
            BizException bizException = (BizException) cause;
            assertEquals(ErrorCode.STATE_CONFLICT, bizException.getErrorCode());
            assertEquals(40901, bizException.getErrorCode().getCode());
            assertTrue(bizException.getMessage().contains("ACTIVE"), bizException.getMessage());
            evidence("并发激活 BOM " + targetId + " vs blocker " + blocker.id()
                    + " -> " + cause.getClass().getSimpleName() + " / "
                    + bizException.getErrorCode() + " / " + bizException.getMessage());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1L, count("SELECT count(*) FROM bom WHERE parent_material_id = ? "
                + "AND status = 'ACTIVE' AND deleted = false", parentId),
                "并发激活后仍应恰好一个 ACTIVE BOM");
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT status FROM bom WHERE id = ?", String.class, targetId),
                "被唯一索引拒绝的激活应回滚为 DRAFT");
        evidence("SQL=SELECT status FROM bom WHERE parent_material_id='" + parentId
                + "' -> ACTIVE count=" + count("SELECT count(*) FROM bom WHERE parent_material_id = ? "
                + "AND status = 'ACTIVE' AND deleted = false", parentId));

        RuntimeException rawFailure = rawUniqueConflict(targetId);
        assertTrue(describe(rawFailure).contains("SQLState=23505"), describe(rawFailure));
        evidence("同一状态直连 BomMapper.updateById 的底层异常链: " + describe(rawFailure));
    }

    /**
     * 在同一数据库状态下直连 Mapper 复现唯一冲突，用于记录 MyBatis 层的异常类型。
     *
     * @param targetId 仍为 DRAFT 的目标 BOM
     * @return 底层抛出的运行时异常
     */
    private RuntimeException rawUniqueConflict(UUID targetId) {
        Bom target = bomMapper.selectById(targetId);
        target.setStatus("ACTIVE");
        try {
            bomMapper.updateById(target);
        } catch (RuntimeException exception) {
            return exception;
        }
        throw new IllegalStateException("预期的唯一约束冲突未发生");
    }

    private String describe(Throwable throwable) {
        StringBuilder description = new StringBuilder(throwable.getClass().getName());
        Throwable current = throwable.getCause();
        while (current != null) {
            description.append(" <- ").append(current.getClass().getName());
            if (current instanceof SQLException sqlException) {
                description.append("[SQLState=").append(sqlException.getSQLState()).append(']');
            }
            current = current.getCause();
        }
        return description.toString();
    }

    /**
     * 轮询 pg_stat_activity，直到执行线程的指定语句因唯一索引冲突进入锁等待。
     *
     * @param statementPrefix 语句前缀（例如 {@code UPDATE bom}）
     * @param holder 持有未提交事务的连接，用于排除其自身的空闲语句
     */
    private void awaitBlockedOn(String statementPrefix, Connection holder) throws Exception {
        long deadline = System.currentTimeMillis() + BLOCK_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            long blocked = count("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND state = 'active' "
                    + "AND wait_event_type = 'Lock' AND query LIKE ?", "%" + statementPrefix + "%");
            if (blocked > 0) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("10s 内未观察到 " + statementPrefix + " 进入唯一索引锁等待，"
                + "无法构造确定性并发；holder 是否仍持有事务? " + !holder.getAutoCommit());
    }
}
