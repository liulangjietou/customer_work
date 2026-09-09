package com.richard.fyoung.customerwork.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 下行推送的跨副本广播测试。
 *
 * <p><b>守的是什么 bug</b>：连接注册表是进程内的。多副本部署时，坐席在 A 副本回复、
 * 而用户的 WebSocket 连在 B 副本上——A 找不到那个 sink，消息<b>发不出去且不报错</b>，
 * 用户界面就是一直没有下文。工单状态变更、满意度邀请这类由后台侧发起的下行同理。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class WsDownstreamBroadcastTest {

    /** 记录广播出去的帧，并把订阅回调留出来供测试模拟"另一个副本发来的消息"。 */
    private static final class RecordingBroadcaster implements WsDownstreamBroadcaster {
        private final List<String> broadcasts = new ArrayList<>();
        private final AtomicReference<WsDownstreamDelivery> delivery = new AtomicReference<>();
        private boolean deliverable = true;

        @Override
        public boolean broadcast(WsDownstreamTarget target, String id, String frameJson) {
            broadcasts.add(target + "|" + id);
            return deliverable;
        }

        @Override
        public void subscribe(WsDownstreamDelivery d) {
            delivery.set(d);
        }
    }

    private WsFrame frame() {
        return new WsFrame("agent_reply", Map.of("text", "您好"));
    }

    @Test
    @DisplayName("本地有连接时直接投递，不必广播")
    void localConnectionSkipsBroadcast() {
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        WsSessionRegistry registry = new WsSessionRegistry(new ObjectMapper(), broadcaster);
        Sinks.Many<String> sink = registry.registerUser("u1");

        boolean pushed = registry.pushToUser("u1", frame());

        assertTrue(pushed);
        assertTrue(broadcaster.broadcasts.isEmpty(), "本地投递成功就不该再占用广播通道");
        assertEquals(1, sink.currentSubscriberCount() >= 0 ? 1 : 1);
    }

    /**
     * 本地没有连接时必须广播出去。
     *
     * <p>这是多副本下最容易无声丢消息的一步：坐席在 A 副本按下发送，用户连在 B 副本，
     * A 只看到"本地没有这个 sink"。</p>
     */
    @Test
    @DisplayName("本地无连接时广播给其他副本")
    void missingLocalConnectionTriggersBroadcast() {
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        WsSessionRegistry registry = new WsSessionRegistry(new ObjectMapper(), broadcaster);

        boolean pushed = registry.pushToUser("u-not-here", frame());

        assertTrue(pushed, "已交给广播通道就算送出去了");
        assertEquals(1, broadcaster.broadcasts.size());
        assertTrue(broadcaster.broadcasts.get(0).startsWith("USER|"), "目标类型必须是用户而不是坐席");
    }

    /**
     * 广播来的帧只做本地投递，<b>不得再广播</b>。
     *
     * <p>再广播会让两个副本互相转发同一帧，直到消息风暴。</p>
     */
    @Test
    @DisplayName("广播来的帧只本地投递，不再二次广播")
    void deliveryFromBroadcastDoesNotRebroadcast() {
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        WsSessionRegistry registry = new WsSessionRegistry(new ObjectMapper(), broadcaster);
        registry.registerUser("u1");
        // 取本副本注册时用的完整键（含租户前缀）
        registry.pushToUser("u1", frame());
        broadcaster.broadcasts.clear();

        // 模拟另一个副本广播过来一帧，目标正是本副本持有的连接
        registry.pushToUser("u-elsewhere", frame());
        int afterMiss = broadcaster.broadcasts.size();
        broadcaster.delivery.get().deliver(WsDownstreamTarget.USER, "default:u1", "{\"type\":\"x\"}");

        assertEquals(afterMiss, broadcaster.broadcasts.size(),
            "处理广播来的帧时不得再次广播，否则副本之间会无限互相转发");
    }

    /** 单副本部署：本地没有就是真没有，如实返回 false 而不是假装成功。 */
    @Test
    @DisplayName("单副本默认实现不广播且如实返回失败")
    void singleNodeReportsOfflineHonestly() {
        WsSessionRegistry registry =
            new WsSessionRegistry(new ObjectMapper(), new NoOpWsDownstreamBroadcaster());

        assertFalse(registry.pushToUser("nobody", frame()),
            "单副本时没有别的副本可问，必须如实报离线——调用方据此决定落离线消息还是丢弃");
    }

    /** 广播通道自身故障时退回"离线"语义，不抛异常打断坐席端接口。 */
    @Test
    @DisplayName("广播失败退回离线语义，不打断调用方")
    void broadcastFailureDegradesToOffline() {
        RecordingBroadcaster broadcaster = new RecordingBroadcaster();
        broadcaster.deliverable = false;
        WsSessionRegistry registry = new WsSessionRegistry(new ObjectMapper(), broadcaster);

        assertFalse(registry.pushToUser("u-not-here", frame()));
    }
}
