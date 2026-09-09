package com.richard.fyoung.customerwork.infra.ws;

import com.richard.fyoung.customerwork.infra.counter.WindowCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 入站消息去重：同一条用户消息重发时只处理一次。
 *
 * <h3>它防的是什么</h3>
 * <p>WebSocket 在移动网络下断连是常态，客户端重发是必须的——但服务端此前<b>没有任何去重</b>：
 * 同一句话到两次就是<b>两次完整的对话轮</b>。代价不只是双份 token：客服智能体会调工具，
 * 于是可能提交两张退款工单、发两次通知。用户看到的是同一个问题被回答了两遍，
 * 而账单和业务后端看到的是两次真实操作。</p>
 *
 * <h3>为什么复用 {@link WindowCounter} 而不是新造一套</h3>
 * <p>「一个键在窗口内是不是第一次出现」正是它 {@code increment} 返回值要回答的问题，
 * 而它已经有进程内与 Redis 两种实现——多副本部署时重发落到另一个副本上照样能去重，
 * 这恰恰是最需要去重的场景（断连重连后往往连到了别的副本）。
 * 另造一套进程内 Map 在那个场景下完全失效。</p>
 *
 * <h3>没有 clientMsgId 时放行</h3>
 * <p>老客户端不带这个字段。为此拒收会让升级服务端变成一次前端强制更新，
 * 而去重是<b>增益</b>而非安全边界——放行只是回到今天的行为。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class InboundMessageDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageDeduplicator.class);

    private static final String KEY_PREFIX = "ws:inbound:";

    private final WindowCounter counter;
    private final int windowSeconds;

    public InboundMessageDeduplicator(WindowCounter counter, int windowSeconds) {
        this.counter = counter;
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    /**
     * 判断这条消息是否是重复投递。
     *
     * @param subjectId   消息归属的身份（用户 id）——不同用户各自计数，键不会互相碰撞
     * @param clientMsgId 客户端生成的消息标识；为空表示客户端不支持去重，一律按首次处理
     * @return true=重复，调用方应当忽略这次投递
     */
    public boolean isDuplicate(String subjectId, String clientMsgId) {
        if (!StringUtils.hasText(clientMsgId) || !StringUtils.hasText(subjectId)) {
            return false;
        }
        try {
            long seen = counter.increment(KEY_PREFIX + subjectId + ":" + clientMsgId, 1, windowSeconds);
            if (seen <= 1) {
                return false;
            }
            log.info("duplicate inbound message ignored, subject={}, clientMsgId={}, seen={}",
                subjectId, clientMsgId, seen);
            return true;
        } catch (Exception e) {
            // fail-open：计数器故障时按首次处理。去重失效的代价是重复回答一次，
            // 而在这里 fail-closed 会让计数器一挂全部消息都发不出去——方向反了
            log.error("inbound dedup failed, treating as first delivery, code={}, subject={}",
                "WS-INBOUND-DEDUP-FAIL", subjectId, e);
            return false;
        }
    }
}
