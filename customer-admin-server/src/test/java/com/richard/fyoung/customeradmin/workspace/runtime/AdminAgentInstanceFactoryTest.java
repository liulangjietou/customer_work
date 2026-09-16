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
 * 透传，是所有会话级功能（stream/files/file-content/rollback）路径解析的输入，越界即可操作他人
 * 会话甚至宿主机任意目录。</p>
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

    // ---------------------- 长任务型智能体默认压缩兜底 ----------------------

    @Test
    void resolveCompressTriggerMsgs_shouldUseConfiguredValue_whenExplicitlySet() {
        assertEquals(100, AdminAgentInstanceFactory.resolveCompressTriggerMsgs(100));
    }

    @Test
    void resolveCompressTriggerMsgs_shouldFallBackToDefault_whenNotConfigured() {
        // 存量/新建的长任务型智能体未显式配置时，不再是"不压缩"，而是套用系统默认值
        assertEquals(40, AdminAgentInstanceFactory.resolveCompressTriggerMsgs(null));
    }

    @Test
    void resolveCompressKeepMsgs_shouldUseConfiguredValue_whenExplicitlySet() {
        assertEquals(50, AdminAgentInstanceFactory.resolveCompressKeepMsgs(50));
    }

    @Test
    void resolveCompressKeepMsgs_shouldFallBackToDefault_whenNotConfigured() {
        assertEquals(10, AdminAgentInstanceFactory.resolveCompressKeepMsgs(null));
    }

    // ---------------------- sessionId 路径穿越防御 ----------------------

}
