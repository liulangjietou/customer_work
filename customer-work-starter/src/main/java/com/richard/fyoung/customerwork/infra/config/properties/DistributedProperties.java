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
     * WebSocket 下行推送模式：memory（单副本）| redis（跨副本广播）。
     *
     * <p><b>多副本部署必须切 redis</b>：连接注册表是进程内的，坐席在 A 副本回复而用户连在 B 副本时，
     * 消息发不出去<b>且不报错</b>——用户界面就是一直没有下文。工单状态变更、满意度邀请这类
     * 由后台侧发起的下行同理。</p>
     */
    private String wsDownstreamMode = "memory";

    /** 下行推送广播的 Redis 频道名。 */
    private String wsDownstreamTopic = "cw:ws:downstream";

    /**
     * 状态机型定时任务是否加多副本互斥锁。
     *
     * <p><b>多副本部署应当开启</b>：审批超时、转人工 SLA、工单 SLA 这类调度器是
     * 「扫一批处于某状态的行，逐条改状态并触发动作」，没有抢占机制。多副本下每个副本都会
     * 扫到同一批行——超时动作执行两次、SLA 告警重复发。队列型调度（Outbox、死信）
     * 靠表里的 lease_owner 抢占，不受此影响。</p>
     *
     * <p>默认关闭：单副本不需要，而无条件加锁会让所有部署都依赖 Redis 可用性。</p>
     */
    private boolean schedulerLeaseEnabled = false;

    /** 定时任务锁的租期（秒）：应大于任务单轮最长执行时间，否则会在执行中途被别的副本抢走。 */
    private long schedulerLeaseSeconds = 300L;

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
