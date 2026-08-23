package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

/** 可靠发布任务在入队时固化的业务意图。 */
public enum RuntimePublishIntent {
    /** 由当前权威配置直接产生的常规发布。 */
    NORMAL,
    /** 仅回退历史提示词与最大迭代次数的安全回滚。 */
    SAFE_ROLLBACK,
    /** 按目标租户当前权威配置重组后的安全灰度。 */
    SAFE_GRAY;

    public boolean requiresRollbackPatch() {
        return this == SAFE_ROLLBACK || this == SAFE_GRAY;
    }
}
