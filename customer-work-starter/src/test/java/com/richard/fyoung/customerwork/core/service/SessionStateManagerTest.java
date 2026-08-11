package com.richard.fyoung.customerwork.core.service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话状态运维门面单测（AgentScope 2.0 迁移版）：基于 {@link InMemoryAgentStateStore}
 * 验证按 {@code (userId, sessionId)} 的存在探测、删除与按租户列举会话。
 * @author owlzhangfq@gmail.com
 */
class SessionStateManagerTest {

    private static final String USER = "tenantA";

    @Test
    void existsThenDelete_shouldReflectStoreState() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        SessionStateManager manager = new SessionStateManager(store);
        String sessionId = "state:" + UUID.randomUUID();

        // 写入一条状态（Msg 在 2.0 实现 State）
        store.save(USER, sessionId, "demo-state", Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text("我的会员等级是黄金").build()).build());

        assertTrue(manager.exists(USER, sessionId), "保存后会话状态应存在");
        assertTrue(manager.listSessions(USER).contains(sessionId), "按租户列举应包含该会话");

        manager.delete(USER, sessionId);
        assertFalse(manager.exists(USER, sessionId), "删除后状态不应存在");
    }

    @Test
    void exists_shouldReturnFalse_forUnknownSession() {
        SessionStateManager manager = new SessionStateManager(new InMemoryAgentStateStore());
        assertFalse(manager.exists(USER, "never-saved"));
    }
}
