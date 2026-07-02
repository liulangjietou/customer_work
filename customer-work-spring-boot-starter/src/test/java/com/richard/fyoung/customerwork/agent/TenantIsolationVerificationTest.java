package com.richard.fyoung.customerwork.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多租户会话状态隔离固化探针（对照框架 issue #1681 / #1619 / #1623）。
 *
 * <p>背景：streamEvents / state 可能把会话状态写进 {@code _default} 命名空间，或跨
 * {@code (userId, sessionId)} 泄漏。本项目按 {@code (userId, sessionId)} 做多租户隔离，
 * 若状态落进共享 {@code _default} 或彼此可见，则存在租户间数据泄漏风险。</p>
 *
 * <p>本测试用 {@link JsonFileAgentStateStore} 指向 JUnit {@code @TempDir}（落盘便于检视命名空间），
 * 构造带 stateStore 的 ReActAgent，用两个不同 RuntimeContext（userA/sessionA、userB/sessionB）
 * 各驱动一轮离线 call。随后走存储 API 与落盘目录双重校验：断言 A、B 状态分属各自
 * {@code (userId, sessionId)} 命名空间且互不可见，并显式断言<b>没有</b> {@code _default}/{@code default}
 * 命名空间键被写入。断言"验证到的事实"，测试保持绿；行为变化时失败提醒重新评估隔离假设。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class TenantIsolationVerificationTest {

    private static final String USER_A = "tenant-A";
    private static final String SESSION_A = "session-A";
    private static final String USER_B = "tenant-B";
    private static final String SESSION_B = "session-B";
    private static final String REPLY_A = "回复给租户A";
    private static final String REPLY_B = "回复给租户B";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(20);

    /** 命名空间泄漏黑名单：任何落盘键/目录出现这些片段即视为跨租户共享的默认命名空间。 */
    private static final Set<String> FORBIDDEN_NAMESPACES = Set.of("_default", "default");

    /**
     * 离线 stub Model：返回纯文本、无工具调用，ReAct 循环一轮收敛。
     */
    private static Model stubModel(String fixedReply) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                ContentBlock text = TextBlock.builder().text(fixedReply).build();
                ChatResponse resp = ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(text))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build();
                return Flux.just(resp);
            }

            @Override
            public String getModelName() {
                return "stub-offline-model";
            }
        };
    }

    private ReActAgent agentWithStore(String fixedReply, JsonFileAgentStateStore store) {
        return ReActAgent.builder()
            .name("isolation-probe-agent")
            .sysPrompt("你是离线隔离探针助手")
            .model(stubModel(fixedReply))
            .toolkit(new Toolkit())
            .maxIters(3)
            .stateStore(store)
            .build();
    }

    private RuntimeContext ctx(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    /** 递归收集 root 下所有目录名与文件名，用于命名空间检视。 */
    private Set<String> collectAllNames(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk
                .filter(p -> !p.equals(root))
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());
        }
    }

    /**
     * 两个租户各驱动一轮 call，校验状态按 (userId, sessionId) 隔离且无 _default 泄漏。
     *
     * <p>实测结论（AgentScope 2.0.0-RC4 基线）：状态按 (userId, sessionId) 正确隔离——
     * {@code listSessionIds(userA)} 只含 sessionA、不含 sessionB，反之亦然；落盘目录树未出现
     * {@code _default}/{@code default} 共享命名空间。issue #1681 / #1619 / #1623 未复现。</p>
     */
    @Test
    void twoTenants_shouldIsolateStateByUserAndSession_withoutDefaultNamespace(@TempDir Path stateDir)
            throws IOException {
        JsonFileAgentStateStore store = new JsonFileAgentStateStore(stateDir);

        Msg replyA = agentWithStore(REPLY_A, store).call("你好A", ctx(USER_A, SESSION_A)).block(BLOCK_TIMEOUT);
        Msg replyB = agentWithStore(REPLY_B, store).call("你好B", ctx(USER_B, SESSION_B)).block(BLOCK_TIMEOUT);
        assertTrue(replyA != null && replyA.getTextContent().contains(REPLY_A), "租户A 应拿到自己的回复");
        assertTrue(replyB != null && replyB.getTextContent().contains(REPLY_B), "租户B 应拿到自己的回复");

        // ---- 存储 API 校验：sessionId 严格归属各自 userId，互不可见 ----
        Set<String> sessionsOfA = store.listSessionIds(USER_A);
        Set<String> sessionsOfB = store.listSessionIds(USER_B);
        assertTrue(sessionsOfA.contains(SESSION_A), "租户A 命名空间应含 sessionA");
        assertFalse(sessionsOfA.contains(SESSION_B), "租户A 命名空间不应看见 sessionB（跨租户不可见）");
        assertTrue(sessionsOfB.contains(SESSION_B), "租户B 命名空间应含 sessionB");
        assertFalse(sessionsOfB.contains(SESSION_A), "租户B 命名空间不应看见 sessionA（跨租户不可见）");

        assertTrue(store.exists(USER_A, SESSION_A), "(userA,sessionA) 状态应已落盘");
        assertTrue(store.exists(USER_B, SESSION_B), "(userB,sessionB) 状态应已落盘");
        // 跨租户组合不存在（sessionA 不属于 userB，反之亦然）
        assertFalse(store.exists(USER_B, SESSION_A), "(userB,sessionA) 不应存在——防止跨租户串号");
        assertFalse(store.exists(USER_A, SESSION_B), "(userA,sessionB) 不应存在——防止跨租户串号");

        // ---- 落盘目录检视：显式断言没有 _default / default 共享命名空间被写入 ----
        // 实测落盘布局为 <root>/<userId>/<sessionId>/agent_state.json，userId 即命名空间根。
        Set<String> allNames = collectAllNames(stateDir);
        for (String forbidden : FORBIDDEN_NAMESPACES) {
            assertFalse(allNames.contains(forbidden),
                "落盘目录不应出现共享命名空间 [" + forbidden + "]（issue #1681/#1619/#1623 关注点），实际目录项="
                    + allNames);
        }
        // 正向确认两租户 userId 都以独立命名空间落盘
        assertTrue(allNames.contains(USER_A) && allNames.contains(USER_B),
            "两个租户 userId 都应各自成为落盘命名空间，实际目录项=" + allNames);

        // ---- 固化基线：各租户名下恰好只有自己的 1 个会话 ----
        assertEquals(1, sessionsOfA.size(), "探针固化：租户A 名下恰好 1 个会话");
        assertEquals(1, sessionsOfB.size(), "探针固化：租户B 名下恰好 1 个会话");
    }
}
