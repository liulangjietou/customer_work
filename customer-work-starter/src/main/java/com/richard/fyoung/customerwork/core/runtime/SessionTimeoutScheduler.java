package com.richard.fyoung.customerwork.core.runtime;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话超时清理器（P1：会话超时自动清理）。
 *
 * <p>周期性扫描热缓存中的会话，超过 {@code session.idle-timeout-minutes} 未活跃的自动清理
 * （移除热 Agent 缓存 + 删除持久化状态 + 清理会话锁）。防止空闲会话堆积导致内存泄漏。</p>
 *
 * <p>仅在 {@code session.idle-timeout-minutes > 0} 时生效；默认 0 = 禁用。
 * 清理后会话的持久化状态也被删除，用户再次发起对话时将从全新会话开始。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SessionTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionTimeoutScheduler.class);

    private final CustomerWorkProperties properties;
    private final CustomerServiceService customerService;

    public SessionTimeoutScheduler(CustomerWorkProperties properties,
                                    CustomerServiceService customerService) {
        this.properties = properties;
        this.customerService = customerService;
    }

    @Scheduled(fixedDelayString = "${customer-work.runtime.scheduler-fixed-delay-ms:60000}")
    public void cleanupIdleSessions() {
        int timeoutMinutes = properties.getSession().getIdleTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            return;
        }
        int cleaned = customerService.cleanupIdleSessions(timeoutMinutes);
        if (cleaned > 0) {
            log.info("[SessionTimeout] cleaned {} idle sessions (threshold={}min)", cleaned, timeoutMinutes);
        }
    }

    /** 可被单测调用的同步入口。 */
    public void runCleanup() {
        cleanupIdleSessions();
    }
}
