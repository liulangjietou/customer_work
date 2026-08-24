package com.richard.fyoung.customerwork.infra.lock;

/** 分布式会话锁基础设施不可用；强一致入口必须失败关闭，不能静默退化为单机锁。 */
public class SessionLockUnavailableException extends RuntimeException {

    public SessionLockUnavailableException(String sessionId, Throwable cause) {
        super("session lock unavailable: " + sessionId, cause);
    }
}
