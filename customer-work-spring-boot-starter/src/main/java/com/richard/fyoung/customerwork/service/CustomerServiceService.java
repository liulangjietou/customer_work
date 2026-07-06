package com.richard.fyoung.customerwork.service;

import com.richard.fyoung.customerwork.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.dto.IntentResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 客服会话服务（AgentScope 2.0 迁移版，对应②"会话恢复与上下文装配"与⑤"状态持久化"）。
 *
 * <p><b>2.0 状态模型</b>：Agent 不再自持会话状态，也不再需要手工 {@code saveTo/loadIfExists}。
 * 会话状态按 {@code (userId, sessionId)} 由框架在 {@code call/stream} 链路自动写入 / 恢复
 * {@link io.agentscope.core.state.AgentStateStore}（见 {@code SessionConfig}）。本服务通过
 * {@link RuntimeContext} 把"租户 + 会话"传入每次调用，单实例即可并发服务多租户多会话。</p>
 *
 * <p>进程内仍保留一个有界 LRU 热 Agent 缓存，仅用于摊薄"按会话装配 Agent"的构建开销
 * （并非状态存储）；缓存未命中时新建的 Agent 会在首次 {@code call} 时自动从 StateStore 恢复历史。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class CustomerServiceService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceService.class);

    /** 对话兜底回复文本（chat 调用失败时返回）。公开以便合成监控据此判定探测是否走了兜底。 */
    public static final String FALLBACK_REPLY =
        "抱歉，系统繁忙，已为您记录问题，建议稍后再试或转人工坐席。";

    /** 进程内热 Agent 缓存上限：超过则按 LRU 淘汰最久未用的会话，避免无界缓存导致 OOM。 */
    private static final int MAX_HOT_AGENTS = 1000;

    /** 意图分类失败计数指标名（缓解框架 #1852/#1699 结构化输出静默失效）。 */
    private static final String M_INTENT_CLASSIFY_ERRORS = "customerwork.intent.classify.errors";
    /** 对话兜底计数指标名（chat 调用失败走 FALLBACK_REPLY）。 */
    private static final String M_CHAT_FALLBACK = "customerwork.chat.fallback";

    private final CustomerServiceAgentFactory agentFactory;
    private final SessionStateManager sessionStateManager;
    private final CustomerWorkProperties properties;
    /** 可为 null：未接入 Micrometer 时降级为无指标（仅日志），不影响主链路。 */
    private MeterRegistry meterRegistry;

    /**
     * 进程内热 Agent 缓存：sessionId -> Agent，有界 LRU（访问序）。
     * 仅为摊薄装配开销；会话状态由 StateStore 持久化，淘汰不丢数据。
     */
    private final Map<String, ReActAgent> sessionAgents;

    /**
     * 会话级并发锁：同一 sessionId 的请求串行执行，防止并发写状态导致状态冲突 / 覆盖。
     * 锁对象在 sessionId 首次使用时懒创建，进程内有效。
     */
    private final ConcurrentHashMap<String, java.util.concurrent.Semaphore> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 会话最后活跃时间戳（用于超时清理）：sessionId -> lastActivityMs。
     */
    private final ConcurrentHashMap<String, Long> sessionActivity = new ConcurrentHashMap<>();

    /** Spring 注入构造：MeterRegistry 经 ObjectProvider 可选注入（actuator 缺席时降级为无指标）。 */
    @Autowired
    public CustomerServiceService(CustomerServiceAgentFactory agentFactory,
                                  SessionStateManager sessionStateManager,
                                  CustomerWorkProperties properties,
                                  ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(agentFactory, sessionStateManager, properties);
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /** 无指标构造（单测 / 未接入 Micrometer 场景）；properties 为空时使用默认配置。 */
    public CustomerServiceService(CustomerServiceAgentFactory agentFactory,
                                  SessionStateManager sessionStateManager) {
        this(agentFactory, sessionStateManager, new CustomerWorkProperties());
    }

    /** 无指标构造（携带配置）。 */
    public CustomerServiceService(CustomerServiceAgentFactory agentFactory,
                                  SessionStateManager sessionStateManager,
                                  CustomerWorkProperties properties) {
        this.agentFactory = agentFactory;
        this.sessionStateManager = sessionStateManager;
        this.properties = properties;
        this.sessionAgents = Collections.synchronizedMap(
            new LinkedHashMap<String, ReActAgent>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ReActAgent> eldest) {
                    return size() > MAX_HOT_AGENTS;
                }
            });
    }

    /** 测试可注入指标注册表（避免暴露 setter 给生产链路误用）。 */
    void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 处理一条用户消息，返回完整回复（非流式）。
     *
     * @param sessionId 会话 ID（来自接入层，可含租户前缀如 tenantA:conv-1）
     * @param userText  用户输入文本
     * @return 助手回复文本（Mono，非阻塞）
     */
    public Mono<String> chat(String sessionId, String userText) {
        log.info("[session {}] received user message: {}", sessionId, userText);
        touchSession(sessionId);
        ReActAgent agent = resolveAgent(sessionId);
        RuntimeContext ctx = agentFactory.contextFor(sessionId);

        return withSessionLock(sessionId, () ->
            agent.call(userText, ctx)
                .map(Msg::getTextContent)
                .doOnNext(reply -> log.info("[session {}] assistant reply: {}", sessionId, reply))
                .onErrorResume(e -> {
                    log.error("[session {}] chat failed, code={}", sessionId, "AGENT_CALL_ERROR", e);
                    incr(M_CHAT_FALLBACK);
                    return Mono.just(FALLBACK_REPLY);
                })
        );
    }

    /**
     * 处理一条用户消息，流式返回增量文本（对应⑤ 逐 token 渲染）。
     *
     * <p>订阅 Agent 的类型化事件流，提取推理增量片段下发。会话状态由框架在流结束后自动持久化。</p>
     *
     * @return 增量文本片段流（Flux，非阻塞）
     */
    public Flux<String> chatStream(String sessionId, String userText) {
        log.info("[session {}] received user message (stream): {}", sessionId, userText);
        touchSession(sessionId);
        ReActAgent agent = resolveAgent(sessionId);
        RuntimeContext ctx = agentFactory.contextFor(sessionId);

        StreamOptions options = StreamOptions.builder()
            .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
            .incremental(true)
            .includeReasoningChunk(true)
            .build();

        Flux<String> flux = withSessionLockFlux(sessionId,
            agent.stream(List.of(toUserMsg(userText)), options, ctx)
                .map(event -> event.getMessage() == null ? "" : event.getMessage().getTextContent())
                .filter(text -> text != null && !text.isEmpty())
        );

        // SSE 空闲超时（框架 #1741 缓解）：相邻元素间隔超过阈值即超时收尾，避免连接泄漏。<=0 禁用。
        long idle = properties.getStream().getIdleTimeoutSeconds();
        if (idle > 0) {
            flux = flux.timeout(Duration.ofSeconds(idle));
        }

        return flux.onErrorResume(e -> {
            if (e instanceof TimeoutException) {
                log.error("[session {}] stream idle timeout after {}s, closing, code={}",
                    sessionId, idle, "STREAM_IDLE_TIMEOUT");
                return Flux.just(FALLBACK_REPLY);
            }
            log.error("[session {}] stream chat failed, code={}", sessionId, "AGENT_STREAM_ERROR", e);
            return Flux.just(FALLBACK_REPLY);
        });
    }

    /**
     * 结构化意图识别（对应 3.3"结构化输出"）。
     *
     * <p>用 ReActAgent 的结构化输出能力，让模型严格按 {@link IntentResult} 的 Schema 返回。
     * 用一次性独立 Agent 与独立 sessionId 做分类，绝不污染真实对话会话的记忆。</p>
     *
     * @return 结构化意图；解析失败时返回一个标注为 other 的兜底结果
     */
    public Mono<IntentResult> classifyIntent(String sessionId, String userText) {
        log.info("[session {}] structured intent classification: {}", sessionId, userText);
        String intentSessionId = "intent:" + sessionId;
        ReActAgent intentAgent = agentFactory.createAgent(intentSessionId);
        RuntimeContext ctx = agentFactory.contextFor(intentSessionId);

        String prompt = "请判断以下用户消息的意图，并按要求结构化输出：" + userText;
        return intentAgent.call(prompt, IntentResult.class, ctx)
            .map(msg -> msg.getStructuredData(IntentResult.class))
            .onErrorResume(e -> {
                log.error("[session {}] intent classification failed, code={}", sessionId,
                    "INTENT_CLASSIFY_ERROR", e);
                incr(M_INTENT_CLASSIFY_ERRORS);
                return Mono.just(new IntentResult("other", "", false, "意图识别失败，转人工兜底"));
            });
    }

    /**
     * 安全中断当前会话正在执行的 Agent（对应 3.1"安全中断 / 实时打断"）。
     *
     * @return 是否存在可中断的活跃会话
     */
    public boolean interrupt(String sessionId) {
        ReActAgent agent = sessionAgents.get(sessionId);
        if (agent == null) {
            log.info("[session {}] no active agent, ignore interrupt", sessionId);
            return false;
        }
        RuntimeContext ctx = agentFactory.contextFor(sessionId);
        agent.interrupt(ctx);
        log.info("[session {}] safe interrupt issued", sessionId);
        return true;
    }

    /** 主动结束并清理会话：移除热缓存、删除持久化状态、清理会话锁。 */
    public void endSession(String sessionId) {
        sessionAgents.remove(sessionId);
        sessionLocks.remove(sessionId);
        sessionActivity.remove(sessionId);
        try {
            RuntimeContext ctx = agentFactory.contextFor(sessionId);
            sessionStateManager.delete(ctx.getUserId(), ctx.getSessionId());
        } catch (Exception e) {
            log.error("[session {}] delete persisted state failed (ignored), code={}", sessionId,
                "SESSION_DELETE_ERROR", e);
        }
        log.info("[session {}] ended and cleaned", sessionId);
    }

    /** 更新会话活跃时间戳。 */
    private void touchSession(String sessionId) {
        sessionActivity.put(sessionId, System.currentTimeMillis());
    }

    /**
     * 清理空闲超时会话（由定时任务调用）：移除超过 timeoutMinutes 未活跃的会话缓存与状态。
     *
     * @param timeoutMinutes 空闲超时（分钟）；<=0 不清理
     * @return 清理的会话数
     */
    public int cleanupIdleSessions(int timeoutMinutes) {
        if (timeoutMinutes <= 0) {
            return 0;
        }
        long threshold = System.currentTimeMillis() - timeoutMinutes * 60_000L;
        int cleaned = 0;
        for (Map.Entry<String, Long> entry : sessionActivity.entrySet()) {
            if (entry.getValue() < threshold) {
                String sessionId = entry.getKey();
                log.info("[session {}] idle timeout ({}min), cleaning up", sessionId, timeoutMinutes);
                endSession(sessionId);
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("[SessionTimeout] cleaned {} idle sessions (threshold={}min)", cleaned, timeoutMinutes);
        }
        return cleaned;
    }

    /** 计数指标 +1（未接入 Micrometer 时降级为 no-op，不影响主链路）。 */
    private void incr(String metric) {
        if (meterRegistry != null) {
            meterRegistry.counter(metric).increment();
        }
    }

    /** 获取热 Agent；未命中时新建（首次 call 自动从 StateStore 恢复历史，无需手工 load）。 */
    private ReActAgent resolveAgent(String sessionId) {
        return sessionAgents.computeIfAbsent(sessionId, agentFactory::createAgent);
    }

    /**
     * 会话级锁包装（Mono）：同一 sessionId 的请求串行执行，防止并发写 StateStore 导致状态覆盖。
     * 在 boundedElastic 上获取锁并执行，不阻塞 Netty 事件循环线程。
     */
    private <T> Mono<T> withSessionLock(String sessionId, Supplier<Mono<T>> supplier) {
        java.util.concurrent.Semaphore lock = sessionLocks.computeIfAbsent(sessionId, k -> new java.util.concurrent.Semaphore(1));
        return Mono.fromCallable(() -> {
                lock.acquire();
                return true;
            })
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .flatMap(ignored -> supplier.get())
            .doFinally(signal -> lock.release());
    }

    /**
     * 会话级锁包装（Flux）：同 {@link #withSessionLock}，用于流式输出。
     */
    private <T> Flux<T> withSessionLockFlux(String sessionId, Flux<T> flux) {
        java.util.concurrent.Semaphore lock = sessionLocks.computeIfAbsent(sessionId, k -> new java.util.concurrent.Semaphore(1));
        return Mono.fromCallable(() -> {
                lock.acquire();
                return true;
            })
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .flatMapMany(ignored -> flux)
            .doFinally(signal -> lock.release());
    }

    private Msg toUserMsg(String userText) {
        return Msg.builder()
            .role(io.agentscope.core.message.MsgRole.USER)
            .name("user")
            .content(io.agentscope.core.message.TextBlock.builder().text(userText).build())
            .build();
    }
}
