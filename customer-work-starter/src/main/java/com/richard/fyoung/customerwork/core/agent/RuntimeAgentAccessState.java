package com.richard.fyoung.customerwork.core.agent;

import org.springframework.stereotype.Component;

/**
 * 当前运行时配置的智能体服务闸门。
 *
 * <p>状态只由已通过摘要校验的运行时配置推进；所有 {@code ReActAgent} 通过
 * {@link AgentGovernanceAssembler} 共享同一实例，因此撤销会同时覆盖 chat、stream、WS、AG-UI、
 * 多 Agent 与 Harness，不依赖逐入口补判断。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class RuntimeAgentAccessState {

    /** 撤销后返回给终端的统一业务话术。 */
    public static final String DISABLED_REPLY = "当前智能体已停用，请联系管理员。";

    private volatile Snapshot snapshot = Snapshot.enabled(null, null, null);

    public boolean isActive() {
        return snapshot.active();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void activate(String targetCode, String revision, String contentHash) {
        snapshot = Snapshot.enabled(targetCode, revision, contentHash);
    }

    public void revoke(String targetCode, String revision, String contentHash) {
        snapshot = Snapshot.revoked(targetCode, revision, contentHash);
    }

    /** 不经 Spring 装配的离线构造默认保持兼容启用态。 */
    public static RuntimeAgentAccessState alwaysActive() {
        return new RuntimeAgentAccessState();
    }

    /** 不可变快照，避免调用链同时读到两次发布的混合字段。 */
    public record Snapshot(boolean active, String targetCode, String revision, String contentHash) {

        private static Snapshot enabled(String targetCode, String revision, String contentHash) {
            return new Snapshot(true, targetCode, revision, contentHash);
        }

        private static Snapshot revoked(String targetCode, String revision, String contentHash) {
            return new Snapshot(false, targetCode, revision, contentHash);
        }
    }
}
