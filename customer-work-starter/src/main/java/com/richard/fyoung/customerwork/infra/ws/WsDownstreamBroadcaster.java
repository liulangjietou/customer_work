package com.richard.fyoung.customerwork.infra.ws;

/**
 * WebSocket 下行推送的跨副本广播 SPI。
 *
 * <p><b>要解决的问题</b>：{@code WsSessionRegistry} 的连接注册表是进程内的
 * （{@code ConcurrentHashMap<String, Sinks.Many<String>>}）。多副本部署时，
 * 坐席在 A 副本回复、而用户的 WebSocket 连在 B 副本上——A 找不到那个 sink，
 * 消息<b>发不出去且不报错</b>，用户界面就是一直没有下文。工单状态变更、
 * 满意度邀请这类由后台侧发起的下行同理。</p>
 *
 * <p>项目的水平扩展已经为限流计数与会话锁做了 Redis 实现，下行推送是这条线上缺的一环。</p>
 *
 * <p><b>降级方向与限流一致</b>：广播失败退回"本地投递失败"这个既有语义，
 * 不抛异常打断调用方——下行推送是旁路，Redis 抖动不该让坐席端的回复接口报错。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public interface WsDownstreamBroadcaster {

    /**
     * 把一帧下行消息广播给其他副本。
     *
     * @return 是否成功交付给广播通道；{@code false} 表示这帧只能算没送出去
     */
    boolean broadcast(WsDownstreamTarget target, String id, String frameJson);

    /**
     * 注册本副本的投递回调：收到其他副本的广播时调用它做本地投递。
     *
     * <p>投递回调内<b>不得再广播</b>，否则两个副本会互相转发同一帧直到消息风暴。</p>
     */
    void subscribe(WsDownstreamDelivery delivery);

    /** 本地投递回调。 */
    @FunctionalInterface
    interface WsDownstreamDelivery {
        /** @return 本副本是否真的投递成功（该连接是否在本副本上） */
        boolean deliver(WsDownstreamTarget target, String id, String frameJson);
    }
}
