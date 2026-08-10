package com.richard.fyoung.customerwork.infra.sqlkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlValidator} 只读校验：SELECT/WITH/带注释放行；写操作/DDL/多语句/注释伪装拦截。
 * @author owlzhangfq@gmail.com
 */
class SqlValidatorTest {

    @Test
    void shouldPass_forSelectAndWith() {
        assertDoesNotThrow(() -> SqlValidator.validateReadOnly("SELECT * FROM t WHERE a = :a"));
        assertDoesNotThrow(() -> SqlValidator.validateReadOnly("  select 1  "));
        assertDoesNotThrow(() -> SqlValidator.validateReadOnly("WITH cte AS (SELECT 1) SELECT * FROM cte"));
    }

    @Test
    void shouldPass_forSelectWithComments_andTrailingSemicolon() {
        assertDoesNotThrow(() -> SqlValidator.validateReadOnly("-- 查询用户\nSELECT * FROM users"));
        assertDoesNotThrow(() -> SqlValidator.validateReadOnly("# 注释\nSELECT 1"));
        assertDoesNotThrow(() -> SqlValidator.validateReadOnly("/* block */ SELECT 1"));
        assertDoesNotThrow(() -> SqlValidator.validateReadOnly("SELECT 1;"));
    }

    @Test
    void shouldReject_writeAndDdlStatements() {
        assertRejectWith("UPDATE t SET x = 1", "仅允许只读");
        assertRejectWith("DELETE FROM t WHERE a = 1", "仅允许只读");
        assertRejectWith("INSERT INTO t(a) VALUES(1)", "仅允许只读");
        assertRejectWith("DROP TABLE t", "仅允许只读");
        assertRejectWith("CREATE TABLE t(a INT)", "仅允许只读");
        assertRejectWith("TRUNCATE TABLE t", "仅允许只读");
    }

    @Test
    void shouldReject_multiStatement() {
        assertRejectWith("SELECT 1; DROP TABLE t", "多语句");
        assertRejectWith("SELECT 1; SELECT 2", "多语句");
    }

    @Test
    void shouldReject_commentDisguisedWrite() {
        assertRejectWith("/*x*/UPDATE t SET a = 1", "仅允许只读");
        assertRejectWith("-- SELECT\nUPDATE t SET a = 1", "仅允许只读");
    }

    @Test
    void shouldReject_emptyOrBlankOrNull() {
        assertRejectWith("", "不能为空");
        assertRejectWith("   ", "不能为空");
        assertRejectWith(null, "不能为空");
        // 去注释后为空，走的是另一条分支
        assertRejectWith("/* only comment */", "去注释后");
    }

    /** starter 侧统一抛 IllegalArgumentException（业务错误码转译由调用方薄壳负责）。 */
    private void assertRejectWith(String sql, String messageFragment) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> SqlValidator.validateReadOnly(sql));
        assertTrue(ex.getMessage().contains(messageFragment),
            "expected message contains '" + messageFragment + "', actual: " + ex.getMessage());
    }
}
