package com.richard.fyoung.customeradmin.workspace.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminAgentInstanceFactory#requiresHarness} 纯函数单测：
 * Harness 升级触发条件 = capabilities 含 vibecoding/plan/subagent/skill-learning 任一，
 * 或 compress_trigger_msgs 非空；tasklist 是内层能力，单独勾选不触发升级。
 * @author owlzhangfq@gmail.com
 */
class AdminAgentInstanceFactoryTest {

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
    }

    @Test
    void requiresHarness_shouldBeTrue_whenCompressionConfigured() {
        // 只配置压缩、不勾任何 Harness 能力时也需要升级（compaction 挂在 HarnessAgent 上）
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat"), 100));
        assertTrue(AdminAgentInstanceFactory.requiresHarness(List.of("chat", "tasklist"), 100));
    }
}
