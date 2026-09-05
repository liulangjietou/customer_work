package com.richard.fyoung.customerwork.infra.ws;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.UUID;

/**
 * 基于 Redis 发布订阅的跨副本下行推送。
 *
 * <p><b>回环防护</b>：每个副本启动时生成一个进程内唯一的 {@code nodeId}，广播帧带上它，
 * 订阅端遇到自己发的直接丢弃。没有这一层，A 发出的帧会被 A 自己收到并再次尝试本地投递——
 * 单副本时是空转，多副本时若投递失败又触发广播就会形成消息风暴。</p>
 *
 * <p><b>失败退回"本地投递失败"而不是抛异常</b>：下行推送是旁路，Redis 抖动不该让
 * 坐席端的回复接口报错。与限流计数那边"Redis 失败降级进程内"的方向一致——
 * 保护性/辅助性能力不能因为基础设施故障就把主链路拖垮。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class RedissonWsDownstreamBroadcaster implements WsDownstreamBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RedissonWsDownstreamBroadcaster.class);

    /** 本副本标识：用于识别并丢弃自己发出的广播。 */
    private final String nodeId = UUID.randomUUID().toString();

    private final RTopic topic;

    public RedissonWsDownstreamBroadcaster(RedissonClient redisson, String topicName) {
        this.topic = redisson.getTopic(topicName);
        log.info("ws downstream broadcaster enabled, topic={} nodeId={}", topicName, nodeId);
    }

    @Override
    public boolean broadcast(WsDownstreamTarget target, String id, String frameJson) {
        try {
            topic.publish(new WsDownstreamMessage(nodeId, target.name(), id, frameJson));
            return true;
        } catch (Exception e) {
            log.error("ws downstream broadcast failed, code={} target={} id={}",
                "WS-BROADCAST-FAIL", target, id, e);
            return false;
        }
    }

    @Override
    public void subscribe(WsDownstreamDelivery delivery) {
        topic.addListener(WsDownstreamMessage.class, (channel, message) -> {
            if (message == null || nodeId.equals(message.nodeId())) {
                // 自己发出的：本地早就试过了，再投一次没有意义，且可能形成回环
                return;
            }
            try {
                delivery.deliver(WsDownstreamTarget.valueOf(message.target()),
                    message.id(), message.frameJson());
            } catch (Exception e) {
                log.error("ws downstream delivery failed, code={} target={} id={}",
                    "WS-DELIVERY-FAIL", message.target(), message.id(), e);
            }
        });
    }

    /** 广播帧。必须可序列化——Redisson 要把它编码后放进 Redis 频道。 */
    public record WsDownstreamMessage(String nodeId, String target, String id, String frameJson)
        implements Serializable {
    }
}
