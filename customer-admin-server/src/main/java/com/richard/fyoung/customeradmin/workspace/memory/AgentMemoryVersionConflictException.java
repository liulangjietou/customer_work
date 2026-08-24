package com.richard.fyoung.customeradmin.workspace.memory;

/** 长期记忆工作副本基于旧版本写入；权威副本保持不变，避免静默丢失并发更新。 */
public class AgentMemoryVersionConflictException extends RuntimeException {

    public AgentMemoryVersionConflictException(String agentCode, long expectedVersion) {
        super("agent memory version conflict: agentCode=" + agentCode + ", expectedVersion=" + expectedVersion);
    }
}
