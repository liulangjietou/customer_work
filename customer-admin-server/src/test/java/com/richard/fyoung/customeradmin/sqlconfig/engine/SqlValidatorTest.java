package com.richard.fyoung.customeradmin.sqlconfig.engine;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertReject("UPDATE t SET x = 1");
        assertReject("DELETE FROM t WHERE a = 1");
        assertReject("INSERT INTO t(a) VALUES(1)");
        assertReject("DROP TABLE t");
        assertReject("CREATE TABLE t(a INT)");
        assertReject("TRUNCATE TABLE t");
    }

    @Test
    void shouldReject_multiStatement() {
        assertReject("SELECT 1; DROP TABLE t");
        assertReject("SELECT 1; SELECT 2");
    }

    @Test
    void shouldReject_commentDisguisedWrite() {
        assertReject("/*x*/UPDATE t SET a = 1");
        assertReject("-- SELECT\nUPDATE t SET a = 1");
    }

    @Test
    void shouldReject_emptyOrBlankOrNull() {
        assertReject("");
        assertReject("   ");
        assertReject(null);
        assertReject("/* only comment */");
    }

    private void assertReject(String sql) {
        BizException ex = assertThrows(BizException.class, () -> SqlValidator.validateReadOnly(sql));
        assertEquals(ResultCode.SQL_NOT_READONLY, ex.getResultCode());
    }
}
