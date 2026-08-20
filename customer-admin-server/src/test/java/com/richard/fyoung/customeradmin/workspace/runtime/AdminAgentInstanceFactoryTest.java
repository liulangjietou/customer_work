package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminAgentInstanceFactory#requiresHarness} 纯函数单测：
 * Harness 升级触发条件 = capabilities 含 vibecoding/plan/subagent/skill-learning/dynamic-subagent 任一，
 * 或 compress_trigger_msgs 非空；tasklist 是内层能力，单独勾选不触发升级。
 *
 * <p>另含 {@link AdminAgentInstanceFactory#requireSafeSessionId} 与
 * {@link AdminAgentInstanceFactory#resolveSessionWorkspace} 的路径穿越防御单测——sessionId 由前端
 * 透传，是所有会话级功能（stream/files/file-content/rollback）路径解析的输入，越界即可操作他人
 * 会话甚至宿主机任意目录。</p>
 * @author owlzhangfq@gmail.com
 */
class AdminAgentInstanceFactoryTest {

    /** 构造器纯字段赋值，路径解析只依赖常量，传 null 依赖即可单测 resolveSessionWorkspace 的防御逻辑。 */
    private AdminAgentInstanceFactory newFactoryForPathTest() {
        return new AdminAgentInstanceFactory(null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null);
    }

    @Test
    void requiresHarness_shouldBeFalse_forPlainChat() {
        assertFalse(AdminAgentInstanceFactory.requiresHarness(List.of("chat"), null));
        assertFalse(AdminAgentInstanceFactory.requiresHarness(List.of(), null));
    }

    @Test
    void requiresHarness_shouldBeFalse_forTasklistOnly() {
        // tasklist 走 ReActAgent.Builder#enableTaskList，不依赖 Harness
        assertFalse(AdminAgentInstanceFactory.requiresHarness(List.of("chat", "tasklist"), null));
    }

    @Test
    void requiresHarness_shouldBeTrue_forHarnessCapabilities() {
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("vibecoding"), null));
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat", "plan"), null));
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat", "subagent"), null));
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat", "skill-learning"), null));
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat", "dynamic-subagent"), null));
        // 长期记忆挂在 HarnessAgent 上（MemoryConfig），仅勾 memory 也需要升级
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat", "memory"), null));
    }

    @Test
    void requiresHarness_shouldBeTrue_whenCompressionConfigured() {
        // 只配置压缩、不勾任何 Harness 能力时也需要升级（compaction 挂在 HarnessAgent 上）
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat"), 100));
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat", "tasklist"), 100));
    }

    // ---------------------- sessionId 路径穿越防御 ----------------------

    @Test
    void requireSafeSessionId_shouldPassNormalValues() {
        // 正常 sessionId 是前端生成的 UUID v4，原样返回
        assertEquals("2f6c0f2e-6a2b-4c3d-9e1f-0a1b2c3d4e5f",
            AdminAgentInstanceFactory.requireSafeSessionId("2f6c0f2e-6a2b-4c3d-9e1f-0a1b2c3d4e5f"));
        // 空值回退 default（与既有行为一致）
        assertEquals("default", AdminAgentInstanceFactory.requireSafeSessionId(null));
        assertEquals("default", AdminAgentInstanceFactory.requireSafeSessionId("  "));
    }

    @Test
    void requireSafeSessionId_shouldRejectTraversalVariants() {
        // ../ 相对路径穿越（回滚他人会话 / 穿出 admin-workspace）
        assertParamInvalid(() -> AdminAgentInstanceFactory.requireSafeSessionId("../other-session"));
        assertParamInvalid(() -> AdminAgentInstanceFactory.requireSafeSessionId("../../../../tmp/victim-repo"));
        // 裸 ..（解析即上跳一级）
        assertParamInvalid(() -> AdminAgentInstanceFactory.requireSafeSessionId(".."));
        // 绝对路径
        assertParamInvalid(() -> AdminAgentInstanceFactory.requireSafeSessionId("/tmp/victim-repo"));
        // 反斜杠（Windows 分隔符）
        assertParamInvalid(() -> AdminAgentInstanceFactory.requireSafeSessionId("..\\other-session"));
        assertParamInvalid(() -> AdminAgentInstanceFactory.requireSafeSessionId("a\\b"));
        // 混入子路径（即便不上跳也不允许 sessionId 携带层级）
        assertParamInvalid(() -> AdminAgentInstanceFactory.requireSafeSessionId("a/b"));
        // 藏在中间的 ..（如 x/../../y 类变体）
        assertParamInvalid(() -> AdminAgentInstanceFactory.requireSafeSessionId("x..y/z"));
    }

    @Test
    void resolveSessionWorkspace_shouldRejectTraversal_beforeTouchingDisk() {
        AdminAgentInstanceFactory factory = newFactoryForPathTest();
        // 公共入口端到端拒绝：抛业务异常，不创建任何目录
        assertParamInvalid(() -> factory.resolveSessionWorkspace("agent-a", "../agent-b-session"));
        assertParamInvalid(() -> factory.resolveSessionWorkspace("agent-a", "/etc"));
        // "." 不含黑名单字符，但 normalize 后等于 sessions 根目录本身，被第二道 startsWith/equals 校验拦下
        assertParamInvalid(() -> factory.resolveSessionWorkspace("agent-a", "."));
    }

    private void assertParamInvalid(org.junit.jupiter.api.function.Executable executable) {
        BizException ex = assertThrows(BizException.class, executable);
        assertEquals(ResultCode.PARAM_INVALID, ex.getResultCode());
    }
}
