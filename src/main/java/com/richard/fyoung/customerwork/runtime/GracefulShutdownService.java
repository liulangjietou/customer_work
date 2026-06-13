package com.richard.fyoung.customerwork.runtime;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 优雅停机服务（对应「运行时与调度 · 优雅停机」）。
 *
 * <p>把框架的 {@link GracefulShutdownManager} 接入 Spring 生命周期：容器关闭时停止接收新请求、
 * 等待在途 Agent 请求处理完成（带超时），避免扩缩容 / 滚动发布时截断对话、丢失会话状态。</p>
 *
 * <p>{@link SmartLifecycle#getPhase()} 取较大值，确保本 Bean 在 Web 容器之后启动、之前停止。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class GracefulShutdownService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownService.class);

    private final CustomerWorkProperties properties;
    private final GracefulShutdownManager manager;
    private volatile boolean running = false;

    @Autowired
    public GracefulShutdownService(CustomerWorkProperties properties) {
        this(properties, GracefulShutdownManager.getInstance());
    }

    /** 便于测试注入。 */
    GracefulShutdownService(CustomerWorkProperties properties, GracefulShutdownManager manager) {
        this.properties = properties;
        this.manager = manager;
    }

    /** 当前是否还在接收新请求。 */
    public boolean isAcceptingRequests() {
        return manager.isAcceptingRequests();
    }

    /** 当前在途请求数。 */
    public int activeRequests() {
        return manager.getActiveRequestCount();
    }

    @Override
    public void start() {
        running = true;
        log.info("优雅停机已就绪，停机等待超时 {}s", properties.getRuntime().getShutdownTimeoutSeconds());
    }

    @Override
    public void stop() {
        running = false;
        Duration timeout = Duration.ofSeconds(properties.getRuntime().getShutdownTimeoutSeconds());
        log.info("开始优雅停机：停止接收新请求，等待在途请求 {} 个（超时 {}）",
            manager.getActiveRequestCount(), timeout);
        try {
            manager.performGracefulShutdown();
            boolean drained = manager.awaitTermination(timeout);
            log.info("优雅停机完成，在途请求是否全部完成={}", drained);
        } catch (Exception e) {
            log.warn("优雅停机异常（继续关闭）: {}", e.getMessage());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 较大相位：晚启动、早停止，先于业务 Bean 进入停机
        return Integer.MAX_VALUE - 100;
    }
}
