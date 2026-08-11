package com.richard.fyoung.customerwork.infra.lock;

/**
 * 会话锁在等待时限内未获取到：同一会话有请求正在处理中。
 *
 * <p>调用方应转成"请稍候再试"这类业务提示，而不是当系统故障处理——
 * 它恰恰说明串行保护在起作用。</p>
 * @author owlzhangfq@gmail.com
 */
public class SessionLockTimeoutException extends RuntimeException {

    public SessionLockTimeoutException(String sessionId) {
        super("acquire session lock timeout, sessionId=" + sessionId);
    }
}
