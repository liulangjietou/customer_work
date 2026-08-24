package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

/** 可靠发布任务在入队时固化的业务意图。 */
public enum RuntimePublishIntent {
    /** 由当前权威配置直接产生的常规发布。 */
    NORMAL,
    /** 健康状态机改变有效路由候选；跳过外部连通性/Eval 门禁，但保留可靠发布、签名与 ACK。 */
    HEALTH_OVERLAY,
    /** 智能体停用、删除或最后一个启用绑定被移除后发布的不可逆服务撤销快照。 */
    REVOKE,
    /** 仅回退历史提示词与最大迭代次数的安全回滚。 */
    SAFE_ROLLBACK,
    /** 按目标租户当前权威配置重组后的安全灰度。 */
    SAFE_GRAY;

    public boolean requiresRollbackPatch() {
        return this == SAFE_ROLLBACK || this == SAFE_GRAY;
    }

    public boolean isRevocation() {
        return this == REVOKE;
    }

    public boolean bypassesConnectivityGate() {
        return this == REVOKE || this == HEALTH_OVERLAY;
    }

    public boolean bypassesEvalGate() {
        return this == REVOKE || this == HEALTH_OVERLAY;
    }
}
