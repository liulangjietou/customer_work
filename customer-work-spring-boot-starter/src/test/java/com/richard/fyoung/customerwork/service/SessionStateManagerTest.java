package com.richard.fyoung.customerwork.service;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.session.InMemorySession;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话状态编排器单测：用 SessionManager 把记忆状态整体 save→load 往返恢复，并验证存在/删除。
 * @author owlzhangfq@gmail.com
 */
class SessionStateManagerTest {

    @Test
    void saveThenLoad_shouldRestoreMemoryState() {
        SessionStateManager manager = new SessionStateManager(new InMemorySession());
        String sessionId = "state:" + UUID.randomUUID();

        InMemoryMemory memory = new InMemoryMemory();
        memory.addMessage(Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text("我的会员等级是黄金").build()).build());

        manager.save(sessionId, memory);
        assertTrue(manager.exists(sessionId), "保存后会话状态应存在");

        // 用一个全新的空记忆恢复
        InMemoryMemory restored = new InMemoryMemory();
        assertTrue(manager.loadIfExists(sessionId, restored), "应能恢复已保存状态");
        assertTrue(restored.getMessages().stream()
                .anyMatch(m -> m.getTextContent().contains("黄金")),
            "恢复后的记忆应包含原消息");

        assertTrue(manager.delete(sessionId), "删除应成功");
        assertFalse(manager.exists(sessionId), "删除后状态不应存在");
    }

    @Test
    void loadIfExists_shouldReturnFalse_forUnknownSession() {
        SessionStateManager manager = new SessionStateManager(new InMemorySession());
        assertFalse(manager.loadIfExists("never-saved", new InMemoryMemory()));
    }
}
