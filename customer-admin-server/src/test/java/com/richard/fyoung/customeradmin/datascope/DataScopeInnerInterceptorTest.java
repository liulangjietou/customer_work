package com.richard.fyoung.customeradmin.datascope;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataScopeInnerInterceptor} 单测：直接断言改写后的 SQL 文本。
 *
 * <p>验证 SQL 改写只能看最终 SQL——断言"调用了某个方法"证明不了条件真的拼上去了，
 * 而拼错一个括号就会让整个过滤静默失效。</p>
 * @author owlzhangfq@gmail.com
 */
class DataScopeInnerInterceptorTest {

    private DataScopeInnerInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new DataScopeInnerInterceptor();
        DataScopeContext.set(DataScope.SELF, 7L);
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }

    @Test
    void select_shouldAppendOwnerConditionForWhitelistedTable() throws JSQLParserException {
        String sql = rewriteSelect("SELECT * FROM ai_project WHERE status = 1");

        assertTrue(sql.contains("(create_by = 7 OR create_by IS NULL)"), sql);
        assertTrue(sql.contains("status = 1"), sql);
    }

    /** 没有 WHERE 的查询同样要被限制，否则"查全部"反而是最容易漏的那条路径。 */
    @Test
    void select_shouldAppendOwnerConditionWhenNoWhereClause() throws JSQLParserException {
        String sql = rewriteSelect("SELECT * FROM ai_project");

        assertTrue(sql.contains("(create_by = 7 OR create_by IS NULL)"), sql);
    }

    /** 租户内共享的配置资产不参与用户维度过滤，否则同租户成员之间无法协作。 */
    @Test
    void select_shouldLeaveSharedTableUntouched() throws JSQLParserException {
        String original = "SELECT * FROM ai_agent WHERE status = 1";
        assertEquals(normalize(original), normalize(rewriteSelect(original)));
    }

    /** 归属列不都叫 create_by，流水表用建表时就有的 user_id。 */
    @Test
    void select_shouldUseUserIdColumnForAuditTables() throws JSQLParserException {
        String sql = rewriteSelect("SELECT * FROM ai_coding_audit_log");

        assertTrue(sql.contains("(user_id = 7 OR user_id IS NULL)"), sql);
    }

    @Test
    void select_shouldQualifyColumnWithTableAlias() throws JSQLParserException {
        String sql = rewriteSelect("SELECT p.* FROM ai_project p WHERE p.status = 1");

        assertTrue(sql.contains("(p.create_by = 7 OR p.create_by IS NULL)"), sql);
    }

    /** JOIN 里只限制白名单那一侧，不该波及共享表。 */
    @Test
    void select_shouldOnlyRestrictWhitelistedSideOfJoin() throws JSQLParserException {
        String sql = rewriteSelect(
            "SELECT p.* FROM ai_project p LEFT JOIN ai_agent a ON a.agent_code = p.agent_code");

        assertTrue(sql.contains("p.create_by = 7"), sql);
        assertTrue(!sql.contains("a.create_by"), sql);
    }

    @Test
    void select_shouldNotRestrictWhenScopeIsNotSelf() throws JSQLParserException {
        DataScopeContext.set(DataScope.TENANT, 7L);
        String original = "SELECT * FROM ai_project WHERE status = 1";

        assertEquals(normalize(original), normalize(rewriteSelect(original)));
    }

    /** 没有上下文的链路（调度线程、异步回调）不过滤，否则后台任务会整体打挂。 */
    @Test
    void select_shouldNotRestrictWithoutContext() throws JSQLParserException {
        DataScopeContext.clear();
        String original = "SELECT * FROM ai_project WHERE status = 1";

        assertEquals(normalize(original), normalize(rewriteSelect(original)));
    }

    /**
     * 越权写才是真正的风险：列表看不到别人的行，但 updateById 只按主键定位，
     * 不加条件就能改掉别人的数据。
     */
    @Test
    void update_shouldAppendOwnerCondition() throws JSQLParserException {
        Update update = (Update) CCJSqlParserUtil.parse("UPDATE ai_project SET name = 'x' WHERE id = 3");
        interceptor.processUpdate(update, 0, "", "");

        assertTrue(update.toString().contains("(create_by = 7 OR create_by IS NULL)"), update.toString());
    }

    @Test
    void delete_shouldAppendOwnerCondition() throws JSQLParserException {
        Delete delete = (Delete) CCJSqlParserUtil.parse("DELETE FROM workbench_site WHERE id = 3");
        interceptor.processDelete(delete, 0, "", "");

        assertTrue(delete.toString().contains("(create_by = 7 OR create_by IS NULL)"), delete.toString());
    }

    @Test
    void update_shouldLeaveSharedTableUntouched() throws JSQLParserException {
        Update update = (Update) CCJSqlParserUtil.parse("UPDATE ai_agent SET status = 0 WHERE id = 3");
        String before = update.toString();
        interceptor.processUpdate(update, 0, "", "");

        assertEquals(normalize(before), normalize(update.toString()));
    }

    private String rewriteSelect(String sql) throws JSQLParserException {
        Select select = (Select) CCJSqlParserUtil.parse(sql);
        interceptor.processSelect(select, 0, sql, "");
        return select.toString();
    }

    /** JSqlParser 重新输出的 SQL 在空白与大小写上与原文不必一致，比对前统一归一。 */
    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase();
    }
}
