package com.richard.fyoung.customeradmin.datascope;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DataScopeTables} 守护测试：白名单是安全边界，误加与漏加都要能被发现。
 *
 * <p>误加一张共享资产表不会报错，只表现为"同租户成员之间数据莫名其妙看不见了"，
 * 比串数据更难排查——所以这里把"哪些表绝不能进白名单"钉死成断言。</p>
 * @author owlzhangfq@gmail.com
 */
class DataScopeTablesTest {

    /** 租户内共享的配置资产：按创建人过滤会让 A 建的智能体 B 用不了，协作能力直接归零。 */
    private static final List<String> MUST_STAY_SHARED = List.of(
        "ai_agent", "ai_knowledge_base", "ai_skill", "ai_mcp", "ai_model_config",
        "ai_channel_robot", "sql_define", "sql_datasource", "sys_user", "sys_role");

    /** 框架自建或无归属人可填的表：加进来只会让链路报错或恒空。 */
    private static final List<String> MUST_NOT_BE_FILTERED = List.of(
        "ai_chat_session_state", "ai_agent_task", "ai_chat_session_owner", "cw_agent_call_log");

    @Test
    void whitelist_shouldCoverPersonalArtifacts() {
        assertEquals("create_by", DataScopeTables.ownerColumnOf("ai_project"));
        assertEquals("create_by", DataScopeTables.ownerColumnOf("ai_chat_attachment"));
        assertEquals("create_by", DataScopeTables.ownerColumnOf("workbench_site"));
        assertEquals("user_id", DataScopeTables.ownerColumnOf("ai_coding_audit_log"));
        assertEquals("user_id", DataScopeTables.ownerColumnOf("sys_operation_log"));
    }

    @Test
    void whitelist_shouldNotContainSharedAssets() {
        MUST_STAY_SHARED.forEach(table ->
            assertNull(DataScopeTables.ownerColumnOf(table), table + " 是租户内共享资产，不能按创建人过滤"));
    }

    @Test
    void whitelist_shouldNotContainFrameworkOrOwnerlessTables() {
        MUST_NOT_BE_FILTERED.forEach(table ->
            assertNull(DataScopeTables.ownerColumnOf(table), table + " 没有可填的归属人，过滤只会让它恒空"));
    }

    /** 表名大小写由 MySQL 服务器配置决定，比对必须不受其影响。 */
    @Test
    void ownerColumnOf_shouldBeCaseInsensitive() {
        assertNotNull(DataScopeTables.ownerColumnOf("AI_PROJECT"));
        assertNotNull(DataScopeTables.ownerColumnOf("  ai_project  "));
    }

    @Test
    void ownerColumnOf_shouldTolerateBlankInput() {
        assertNull(DataScopeTables.ownerColumnOf(null));
        assertNull(DataScopeTables.ownerColumnOf(""));
    }
}
