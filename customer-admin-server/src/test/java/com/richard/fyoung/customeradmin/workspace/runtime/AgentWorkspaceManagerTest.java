package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentWorkspaceManager} 的路径穿越防御单测。
 *
 * <p>sessionId 由前端透传，{@link AgentWorkspaceManager#resolveSessionWorkspace} 是所有会话级功能
 * （stream / files / file-content / rollback）解析磁盘路径的<b>唯一公共入口</b>，
 * 校验只在那一处做，所以这组用例是整条链路的安全底线。</p>
 *
 * <p>随实现一起从 {@code AdminAgentInstanceFactoryTest} 迁来——工作区职责搬到哪个类，
 * 守着它的测试就该跟到哪个类，否则下一个改这段逻辑的人不会想到去另一个测试类里找。</p>
 *
 * <p>构造器传 null：路径解析只依赖静态常量与入参，不碰 SessionWorkspaceStorage，
 * 无需拉起任何依赖即可验证防御逻辑。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class AgentWorkspaceManagerTest {

    @Test
    void requireSafeSessionId_shouldPassNormalValues() {
        // 正常 sessionId 是前端生成的 UUID v4，原样返回
        assertEquals("2f6c0f2e-6a2b-4c3d-9e1f-0a1b2c3d4e5f",
            AgentWorkspaceManager.requireSafeSessionId("2f6c0f2e-6a2b-4c3d-9e1f-0a1b2c3d4e5f"));
        // 空值回退 default（与既有行为一致）
        assertEquals("default", AgentWorkspaceManager.requireSafeSessionId(null));
        assertEquals("default", AgentWorkspaceManager.requireSafeSessionId("  "));
    }


    @Test
    void requireSafeSessionId_shouldRejectTraversalVariants() {
        // ../ 相对路径穿越（回滚他人会话 / 穿出 admin-workspace）
        assertParamInvalid(() -> AgentWorkspaceManager.requireSafeSessionId("../other-session"));
        assertParamInvalid(() -> AgentWorkspaceManager.requireSafeSessionId("../../../../tmp/victim-repo"));
        // 裸 ..（解析即上跳一级）
        assertParamInvalid(() -> AgentWorkspaceManager.requireSafeSessionId(".."));
        // 绝对路径
        assertParamInvalid(() -> AgentWorkspaceManager.requireSafeSessionId("/tmp/victim-repo"));
        // 反斜杠（Windows 分隔符）
        assertParamInvalid(() -> AgentWorkspaceManager.requireSafeSessionId("..\\other-session"));
        assertParamInvalid(() -> AgentWorkspaceManager.requireSafeSessionId("a\\b"));
        // 混入子路径（即便不上跳也不允许 sessionId 携带层级）
        assertParamInvalid(() -> AgentWorkspaceManager.requireSafeSessionId("a/b"));
        // 藏在中间的 ..（如 x/../../y 类变体）
        assertParamInvalid(() -> AgentWorkspaceManager.requireSafeSessionId("x..y/z"));
    }


    @Test
    void resolveSessionWorkspace_shouldRejectTraversal_beforeTouchingDisk() {
        AgentWorkspaceManager manager = new AgentWorkspaceManager(null);
        // 公共入口端到端拒绝：抛业务异常，不创建任何目录
        assertParamInvalid(() -> manager.resolveSessionWorkspace("agent-a", "../agent-b-session"));
        assertParamInvalid(() -> manager.resolveSessionWorkspace("agent-a", "/etc"));
        // "." 不含黑名单字符，但 normalize 后等于 sessions 根目录本身，被第二道 startsWith/equals 校验拦下
        assertParamInvalid(() -> manager.resolveSessionWorkspace("agent-a", "."));
    }

    private void assertParamInvalid(org.junit.jupiter.api.function.Executable executable) {
        BizException e = assertThrows(BizException.class, executable);
        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }
}
