package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 水平扩展配置。默认全 memory——单实例部署下进程内实现更快也更简单，
 * 多副本部署才需要切 redis，否则限流与成本配额会被放大成实例数倍。
 *
 * <p>Redis 连接复用 {@code customer-work.distributed-lock.redis.*}，不再单独配一套。</p>
 */
@Data
public class DistributedProperties {

    /** 限流与成本熔断的窗口计数实现：{@code memory} 进程内 / {@code redis} 跨实例共享。 */
    private String counterMode = "memory";

    /** Redis 计数键前缀，多套环境共用一个 Redis 时用它隔离。 */
    private String counterKeyPrefix = "cw:counter:";

    /**
     * 会话串行锁实现：{@code memory} 进程内信号量 / {@code redis} 分布式锁。
     *
     * <p>进程内锁要求网关按会话做 sticky 路由才成立；一旦同一会话可能落到不同实例，
     * 必须切 redis，否则同一会话的并发请求会同时进入模型调用，历史被交叉写坏。</p>
     */
    private String sessionLockMode = "memory";

    /** 分布式会话锁的最长等待时间（秒），等不到即按繁忙拒绝，避免请求线程无限堆积。 */
    private int sessionLockWaitSeconds = 10;

    /** 分布式会话锁的持有超时（秒），防止实例崩溃后锁永不释放。 */
    private int sessionLockLeaseSeconds = 120;
}
