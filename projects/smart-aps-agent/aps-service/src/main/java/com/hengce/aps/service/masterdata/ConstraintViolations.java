package com.hengce.aps.service.masterdata;

import java.sql.SQLException;

/**
 * 主数据域数据库约束冲突识别。
 *
 * <p>只在异常链中识别 PostgreSQL 的 SQLSTATE；无法判定为预期约束冲突的异常必须由调用方
 * 原样抛回全局异常处理器，不得把任意数据访问异常翻译成业务错误码。</p>
 */
public final class ConstraintViolations {

    /**
     * 外键约束违反的 SQLSTATE。
     */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /**
     * 唯一约束违反的 SQLSTATE。
     */
    private static final String UNIQUE_VIOLATION = "23505";

    private ConstraintViolations() {
    }

    /**
     * 判断异常链中是否包含外键约束冲突（SQLSTATE 23503）。
     *
     * @param throwable 捕获到的异常
     * @return 是否为外键约束冲突
     */
    public static boolean isForeignKeyViolation(Throwable throwable) {
        return hasSqlState(throwable, FOREIGN_KEY_VIOLATION);
    }

    /**
     * 判断异常链中是否包含唯一约束冲突（SQLSTATE 23505）。
     *
     * @param throwable 捕获到的异常
     * @return 是否为唯一约束冲突
     */
    public static boolean isUniqueViolation(Throwable throwable) {
        return hasSqlState(throwable, UNIQUE_VIOLATION);
    }

    /**
     * 判断异常链消息中是否提到指定约束名，用于区分同一张表上的多个外键。
     *
     * @param throwable 捕获到的异常
     * @param constraintName 数据库约束名
     * @return 消息中是否包含该约束名
     */
    public static boolean mentionsConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasSqlState(Throwable throwable, String sqlState) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && sqlState.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
