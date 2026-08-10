package com.richard.fyoung.customerwork.infra.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.AgentStateStore;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis / MySQL 状态持久化测试的共享工具：服务可达性探测与一套通用的"存-取-删"断言
 * （AgentScope 2.0 迁移版，基于 {@link AgentStateStore}）。
 * @author owlzhangfq@gmail.com
 */
final class SessionPersistenceTestSupport {

    private static final String USER_ID = "it-user";

    private SessionPersistenceTestSupport() {
    }

    /** 探测 TCP 端口是否可连接（用于 assumeTrue 跳过未启动服务的环境）。 */
    static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 通用断言：保存一个 Msg 状态（Msg 在 2.0 实现 State）→ 存在 → 取回内容一致 → 删除后不存在。 */
    static void assertSaveGetDelete(AgentStateStore store, String sessionId) {
        try {
            Msg msg = Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(TextBlock.builder().text("持久化往返测试-" + sessionId).build())
                .build();

            store.save(USER_ID, sessionId, "demo-state", msg);
            assertTrue(store.exists(USER_ID, sessionId), "保存后会话应存在");

            Msg restored = store.get(USER_ID, sessionId, "demo-state", Msg.class)
                .orElseThrow(() -> new AssertionError("应能取回已保存的状态"));
            assertEquals("持久化往返测试-" + sessionId, restored.getTextContent(),
                "取回内容应与保存一致");

            store.delete(USER_ID, sessionId);
            assertFalse(store.exists(USER_ID, sessionId), "删除后会话不应存在");
        } finally {
            try {
                store.delete(USER_ID, sessionId);
            } catch (Exception ignored) {
                // 清理兜底
            }
        }
    }
}
