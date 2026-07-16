package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plan Mode 人工确认注册表 + 会话级事件通道（需求 P1-1「流中暂停等确认」的核心）。
 *
 * <h3>挂起机制</h3>
 * <ol>
 *   <li>{@link VibeCodingService#stream} 订阅时先 {@link #openChannel} 建一个会话通道（一个
 *       {@code Sinks.Many}），并把 {@link #events(PlanChannel)} 合并进 SSE 输出流；</li>
 *   <li>{@code PlanConfirmationMiddleware} 在工具执行前命中高风险时调 {@link #submit}：往通道推一条
 *       {@code plan} 事件（经 SSE 到达前端），并登记一个 {@link CompletableFuture} 挂起该工具执行；</li>
 *   <li>{@code POST /plan/confirm} 调 {@link #confirm} 完成对应 future（推一条 {@code plan_result} 事件），
 *       中间件据此恢复（批准）或改写取消（拒绝）该工具调用；</li>
 *   <li>超时由中间件侧对 future 施加，超时后调 {@link #timeout} 补 {@code plan_result=TIMEOUT}。</li>
 * </ol>
 *
 * <h3>单实例语义（简化边界，符合需求 §4.4.3）</h3>
 * 挂起态只存进程内存（{@link #channels} + 每通道的 {@link PlanChannel#pending}），<b>不持久化</b>：
 * 服务重启即全部失效，重启后到来的 {@code /plan/confirm} 找不到挂起项直接 fast fail（返回 false）；
 * 且正在挂起的 SSE 连接本身也会随重启断开。多实例部署需要把注册表外部化（如 Redis），当前不做。
 * @author owlzhangfq@gmail.com
 */
@Service
public class PlanConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(PlanConfirmationService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** {@code agentCode:sessionId -> 会话通道}。同一会话重新 stream 会顶替旧通道（旧的挂起项一并 fast fail）。 */
    private final Map<String, PlanChannel> channels = new ConcurrentHashMap<>();

    /** 打开会话通道（stream 订阅时调用，早于 Agent 产出任何事件，保证中间件挂起时能找到通道）。 */
    public PlanChannel openChannel(String agentCode, String sessionId) {
        String key = channelKey(agentCode, sessionId);
        PlanChannel channel = new PlanChannel(key);
        PlanChannel previous = channels.put(key, channel);
        if (previous != null) {
            // 同一会话上一轮通道未清理（异常残留）：拒绝其残留挂起项并关掉，避免泄漏
            previous.abortAll();
        }
        return channel;
    }

    /** 会话通道的事件流（{@code plan}/{@code plan_result} 片段），供 stream 合并进 SSE 输出。 */
    public Flux<ChatStreamChunk> events(PlanChannel channel) {
        return channel.sink.asFlux();
    }

    /** stream 主流结束时完成通道事件流（让合并流得以正常结束）。 */
    public void completeEvents(PlanChannel channel) {
        // unicast sink 要求发射方外部串行化：complete 与并发的 emit（超时线程/确认接口线程）必须同锁
        synchronized (channel.emitLock) {
            channel.sink.tryEmitComplete();
        }
    }

    /** 关闭并注销通道（stream 生命周期结束/取消时调用）：拒绝残留挂起项 + 摘除注册。 */
    public void closeChannel(PlanChannel channel) {
        if (channel == null) {
            return;
        }
        channels.remove(channel.key, channel);
        channel.abortAll();
        // 同 completeEvents：断连清理与并发超时/确认的发射存在竞态窗口，complete 必须纳入 emitLock
        synchronized (channel.emitLock) {
            channel.sink.tryEmitComplete();
        }
    }

    /**
     * 提交一次高风险确认请求（中间件调用）：往通道推 {@code plan} 事件并登记挂起 future。
     * 通道不存在（无活跃 SSE 承接确认）时返回 {@link Optional#empty()}——调用方据此 fast fail 放行给护栏兜底。
     */
    public Optional<PlanTicket> submit(String agentCode, String sessionId, PlanEvent event) {
        PlanChannel channel = channels.get(channelKey(agentCode, sessionId));
        if (channel == null) {
            log.info("[workspace] plan confirm skipped: no live channel, agentCode={}, sessionId={}", agentCode, sessionId);
            return Optional.empty();
        }
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        channel.pending.put(event.planId(), future);
        emit(channel, ChatNodeKind.PLAN, event, "PLAN_EVENT_JSON_ERROR");
        log.info("[workspace] plan pending confirmation, agentCode={}, sessionId={}, planId={}, actions={}",
            agentCode, sessionId, event.planId(), event.actions().size());
        return Optional.of(new PlanTicket(channel, event.planId(), future));
    }

    /**
     * 确认/拒绝某个挂起计划（{@code /plan/confirm} 调用）：完成对应 future 并推 {@code plan_result} 事件。
     * 会话归属校验隐含在按 {@code (agentCode, sessionId)} 定位通道里——跨会话/已失效的 planId 返回 false。
     *
     * @return 是否命中并解决了一个挂起项；false 表示 planId 不存在或已被处理（过期/超时/重启）
     */
    public boolean confirm(String agentCode, String sessionId, String planId, boolean approved) {
        PlanChannel channel = channels.get(channelKey(agentCode, sessionId));
        if (channel == null) {
            return false;
        }
        CompletableFuture<Boolean> future = channel.pending.remove(planId);
        if (future == null || future.isDone()) {
            return false;
        }
        emit(channel, ChatNodeKind.PLAN_RESULT, new PlanResultEvent(planId,
            approved ? PlanResultEvent.STATUS_APPROVED : PlanResultEvent.STATUS_REJECTED), "PLAN_RESULT_JSON_ERROR");
        future.complete(approved);
        log.info("[workspace] plan confirmed, agentCode={}, sessionId={}, planId={}, approved={}",
            agentCode, sessionId, planId, approved);
        return true;
    }

    /**
     * 超时收尾（中间件侧超时后调用）：推 {@code plan_result=TIMEOUT}，摘除挂起登记。
     * <b>幂等收口</b>：挂起项已被 {@link #confirm} 摘除（超时边界时刻用户抢先确认）时直接返回不 emit——
     * 与 confirm 侧的 {@code isDone} 检查对齐，避免前端先收 APPROVED 又收 TIMEOUT 的矛盾终态。
     */
    public void timeout(PlanTicket ticket) {
        if (ticket.channel().pending.remove(ticket.planId()) == null) {
            return;
        }
        emit(ticket.channel(), ChatNodeKind.PLAN_RESULT,
            new PlanResultEvent(ticket.planId(), PlanResultEvent.STATUS_TIMEOUT), "PLAN_RESULT_JSON_ERROR");
        log.info("[workspace] plan confirm timeout (treated as reject), planId={}", ticket.planId());
    }

    private void emit(PlanChannel channel, ChatNodeKind kind, Object payload, String errorCode) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("[workspace] plan event serialize failed, code={}, kind={}", errorCode, kind, e);
            json = "{}";
        }
        // Sinks 并发发射用 tryEmitNext + 同步兜底：中间件线程与确认接口线程可能并发推事件
        synchronized (channel.emitLock) {
            channel.sink.tryEmitNext(new ChatStreamChunk(kind, json));
        }
    }

    private String channelKey(String agentCode, String sessionId) {
        return agentCode + ":" + (StringUtils.hasText(sessionId) ? sessionId : "default");
    }

    /**
     * 一个会话的确认通道：多播 sink（承载 plan/plan_result 事件）+ 该会话内的挂起 future 表。
     * unicast 缓冲即可（只有 stream 输出流一个订阅者），发射方并发用 {@link #emitLock} 串行化。
     */
    public static final class PlanChannel {
        private final String key;
        private final Sinks.Many<ChatStreamChunk> sink = Sinks.many().unicast().onBackpressureBuffer();
        private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
        private final Object emitLock = new Object();

        private PlanChannel(String key) {
            this.key = key;
        }

        /** 拒绝并清空所有残留挂起项（关闭/顶替通道时的 fast fail）。 */
        private void abortAll() {
            pending.values().forEach(f -> f.complete(false));
            pending.clear();
        }
    }

    /** 一次挂起的凭据：定位通道 + planId + 挂起 future，供中间件 await/超时收尾。 */
    public record PlanTicket(PlanChannel channel, String planId, CompletableFuture<Boolean> future) {
    }
}
