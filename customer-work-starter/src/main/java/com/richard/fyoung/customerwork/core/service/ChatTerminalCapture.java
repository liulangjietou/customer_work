package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次订阅内的终止元数据采集器。
 *
 * <p>它只存在于 Reactor Context，不进入单例服务字段；同一会话并发请求也不会串 usage。
 * 模型事件可能同时穿过父、子 Agent 的中间件，故按 replyId 去重后再累计。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ChatTerminalCapture {

    public static final String ERROR = "ERROR";
    public static final String CACHE_HIT = "CACHE_HIT";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String MODEL_STOP = "MODEL_STOP";

    private final Map<String, ChatUsageSnapshot> usageByReplyId = new ConcurrentHashMap<>();
    private final AtomicReference<String> finishReason = new AtomicReference<>();

    /** 捕获 AgentScope 事件；同一事件重复经过嵌套中间件时不会重复计量。 */
    public void accept(AgentEvent event) {
        if (event instanceof ModelCallEndEvent modelEnd && modelEnd.getUsage() != null) {
            String key = modelEnd.getReplyId() == null || modelEnd.getReplyId().isBlank()
                ? modelEnd.getId() : modelEnd.getReplyId();
            if (key == null || key.isBlank()) {
                key = "event-" + System.identityHashCode(modelEnd);
            }
            usageByReplyId.putIfAbsent(key, ChatUsageSnapshot.from(modelEnd.getUsage()));
        }
        if (event instanceof AgentResultEvent result) {
            Msg message = result.getResult();
            if (message != null && message.getGenerateReason() != null) {
                finishReason.set(message.getGenerateReason().name());
            }
        }
    }

    public void markError() {
        finishReason.set(ERROR);
    }

    public ChatUsageSnapshot usage() {
        return usageByReplyId.values().stream()
            .reduce(ChatUsageSnapshot.empty(), ChatUsageSnapshot::plus);
    }

    /**
     * 生成终止信封。缓存、配额与故障兜底不经过 Agent 事件流，统一在这里根据最终回复判定。
     */
    public ChatTerminalEnvelope envelope(String messageId, String reply, String traceId) {
        String reason = finishReason.get();
        if (CustomerServiceService.QUOTA_EXCEEDED_REPLY.equals(reply)) {
            reason = QUOTA_EXCEEDED;
        } else if (CustomerServiceService.FALLBACK_REPLY.equals(reply)) {
            reason = ERROR;
        } else if (reason == null) {
            reason = usageByReplyId.isEmpty() ? CACHE_HIT : MODEL_STOP;
        }
        return new ChatTerminalEnvelope(messageId, reason, usage(), traceId);
    }
}
