package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.dto.IntentResult;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.infra.lock.InMemorySessionLock;
import com.richard.fyoung.customerwork.infra.lock.SessionLock;
import com.richard.fyoung.customerwork.capability.csat.CsatService;
import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheService;
import com.richard.fyoung.customerwork.safety.security.spotlight.AttachmentTextSpotlighter;
import com.richard.fyoung.customerwork.safety.quota.InMemoryTenantQuotaStore;
import com.richard.fyoung.customerwork.safety.quota.QuotaDecision;
import com.richard.fyoung.customerwork.safety.quota.TenantQuotaGuard;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordStreamGuard;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
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

    /**
     * 配额超限时的回复文本。
     *
     * <p>与 {@link #FALLBACK_REPLY} 分开：前者是"系统故障"，用户重试有意义；
     * 配额超限重试无用，措辞必须让人知道该去找谁。</p>
     */
    public static final String QUOTA_EXCEEDED_REPLY =
        "本期服务额度已用尽，请联系管理员提升额度后再试。";

    /** 进程内热 Agent 缓存上限：超过则按 LRU 淘汰最久未用的会话，避免无界缓存导致 OOM。 */
    private static final int MAX_HOT_AGENTS = 1000;

    /** 意图分类失败计数指标名（缓解框架 #1852/#1699 结构化输出静默失效）。 */
    private static final String M_INTENT_CLASSIFY_ERRORS = "customerwork.intent.classify.errors";
    /** 对话兜底计数指标名（chat 调用失败走 FALLBACK_REPLY）。 */
    private static final String M_CHAT_FALLBACK = "customerwork.chat.fallback";
    /** 语义缓存命中计数指标名：省下的每一次模型调用都记在这里，用来算这个功能到底值不值。 */
    private static final String M_CACHE_HIT = "customerwork.chat.cache.hit";

    /**
     * 命中缓存后下发的切片长度（字符）。
     *
     * <p>只为让前端行为与真实流式一致，不掺人为延迟——取值大小只影响气泡撑开的观感，
     * 不影响任何正确性。</p>
     */
    private static final int CACHED_ANSWER_CHUNK_SIZE = 24;

    private final CustomerServiceAgentFactory agentFactory;
    private final SessionStateManager sessionStateManager;
    private final CustomerWorkProperties properties;
    /** 可为 null：未接入 Micrometer 时降级为无指标（仅日志），不影响主链路。 */
    private MeterRegistry meterRegistry;

    /**
     * 出站敏感词过滤器；敏感词功能关闭时容器里没有该 Bean，此处为 null，流式链路不做任何过滤。
     *
     * <p><b>为什么流式过滤在这里而不在中间件</b>：本服务与 admin 侧的 {@code ChatService} 都已迁到
     * {@code streamEvents(...)}，下发的就是经过 {@code onAgent} 链的那条事件流本身，中间件的改写直接生效
     * （旧的 {@code stream(...)} 下中间件虽也执行，但文本由 {@code StreamingHook} 旁路捕获、改写落不到
     * 用户屏幕上，那个"技术上做不到"的理由已不成立）。下沉到中间件现在可行，拦住它的是改造量——事件对象
     * 不可变、子 Agent 事件同流、BLOCK 要改成插事件，属于独立重构，详见 {@link SensitiveWordStreamGuard}
     * 类注释。</p>
     */
    private SensitiveWordFilter sensitiveWordFilter;

    /**
     * 进程内热 Agent 缓存：sessionId -> Agent，有界 LRU（访问序）。
     * 仅为摊薄装配开销；会话状态由 StateStore 持久化，淘汰不丢数据。
     */
    private final Map<String, ReActAgent> sessionAgents;

    /**
     * 会话级并发锁：同一 sessionId 的请求串行执行，防止并发写状态导致状态冲突 / 覆盖。
     *
     * <p>默认进程内（要求网关按会话 sticky 路由）；多副本部署把
     * {@code customer-work.distributed.session-lock-mode} 切成 {@code redis} 即跨实例互斥。</p>
     */
    private SessionLock sessionLock = new InMemorySessionLock(0);

    /**
     * 租户配额判定；未装配时是一个恒放行的实例（配额默认关闭，行为与引入配额前一致）。
     *
     * <p>判定放在入口而不是 token 采集中间件里：那个中间件的既定原则是"只读透传、绝不打断主链路"，
     * 而配额拦截恰恰要打断。记账则复用中间件里 token 的唯一落点，两边各就各位。</p>
     */
    private TenantQuotaGuard quotaGuard =
        new TenantQuotaGuard(new InMemoryTenantQuotaStore(), new InMemoryWindowCounter(), false);

    /**
     * 会话最后活跃时间戳（用于超时清理）：sessionId -> lastActivityMs。
     */
    private final ConcurrentHashMap<String, Long> sessionActivity = new ConcurrentHashMap<>();

    /**
     * 语义缓存；未装配（或未开启）时为 {@code null}，主链路走原路径、零额外开销。
     *
     * <p>命中时省掉的是整条链路——Agent 装配、知识检索、模型调用全都不用发生，
     * 这也是它比任何别的省钱手段都直接的原因。</p>
     */
    private SemanticCacheService semanticCache;

    /** 会话级满意度；未装配时为 {@code null}，会话结束不发邀请。 */
    private CsatService csatService;

    /** Spring 注入构造：MeterRegistry 经 ObjectProvider 可选注入（actuator 缺席时降级为无指标）。 */
    @Autowired
    public CustomerServiceService(CustomerServiceAgentFactory agentFactory,
                                  SessionStateManager sessionStateManager,
                                  CustomerWorkProperties properties,
                                  ObjectProvider<MeterRegistry> meterRegistryProvider,
                                  ObjectProvider<SensitiveWordFilter> sensitiveWordFilterProvider,
                                  ObjectProvider<SessionLock> sessionLockProvider,
                                  ObjectProvider<TenantQuotaGuard> quotaGuardProvider,
                                  ObjectProvider<SemanticCacheService> semanticCacheProvider,
                                  ObjectProvider<CsatService> csatServiceProvider) {
        this(agentFactory, sessionStateManager, properties);
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.sensitiveWordFilter = sensitiveWordFilterProvider.getIfAvailable();
        this.semanticCache = semanticCacheProvider == null ? null : semanticCacheProvider.getIfAvailable();
        this.csatService = csatServiceProvider == null ? null : csatServiceProvider.getIfAvailable();
        SessionLock provided = sessionLockProvider == null ? null : sessionLockProvider.getIfAvailable();
        if (provided != null) {
            this.sessionLock = provided;
        }
        TenantQuotaGuard guard = quotaGuardProvider == null ? null : quotaGuardProvider.getIfAvailable();
        if (guard != null) {
            this.quotaGuard = guard;
        }
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

    /** 测试可注入出站敏感词过滤器（生产走构造注入）。 */
    void setSensitiveWordFilter(SensitiveWordFilter sensitiveWordFilter) {
        this.sensitiveWordFilter = sensitiveWordFilter;
    }

    /**
     * 为一次流式输出建一个 guard；敏感词关闭、或只配了入站方向时返回 null（调用方跳过过滤）。
     * 每次订阅都要新建——guard 有缓冲状态，跨流复用会把上一条会话的尾巴接到下一条开头。
     */
    private SensitiveWordStreamGuard newOutboundGuard() {
        if (sensitiveWordFilter == null || !properties.getSensitiveWord().outboundEnabled()) {
            return null;
        }
        return new SensitiveWordStreamGuard(sensitiveWordFilter,
            properties.getSensitiveWord().getOutboundSafeReply());
    }

    /**
     * 把 guard 挂到文本流上：逐片过滤（可能整片被缓冲而不发），流末尾 flush 出留住的尾巴。
     *
     * <p>不 flush 会吞掉正文最后几个字——尾部那 {@code 最长词长-1} 个字符正是被刻意留住等下一片的。</p>
     */
    private Flux<String> applyOutboundGuard(Flux<String> source, SensitiveWordStreamGuard guard) {
        if (guard == null) {
            return source;
        }
        return source
            .mapNotNull(text -> {
                String emit = guard.accept(text);
                return emit.isEmpty() ? null : emit;
            })
            .concatWith(Flux.defer(() -> {
                String tail = guard.flush();
                return tail.isEmpty() ? Flux.empty() : Flux.just(tail);
            }));
    }

    /**
     * 把用户消息里的附件解析文本包进隔离块后再交给模型。
     *
     * <p>附件常常不是用户自己写的（转发的文档、别处收到的截图），内容由第三方控制，
     * 而前端会把解析结果直接拼进消息正文——不隔离的话，一张写着"忽略以上指令"的图片
     * 就是一条现成的间接注入通道。</p>
     *
     * <p><b>只包给模型看的那一份，不改缓存与埋点用的原文</b>：隔离块带 {@code SecureRandom} 生成的
     * 随机 nonce，拿它当语义缓存的 key 会让缓存永远不命中。两条对话路径都必须调用本方法。</p>
     */
    private String spotlightAttachments(String userText) {
        return AttachmentTextSpotlighter.wrapAttachments(userText);
    }

    /**
     * 整段文本的出站过滤（非流式路径用）。
     *
     * <p>与 {@link #applyOutboundGuard(Flux, SensitiveWordStreamGuard)} 是同一道闸门的两种形态：
     * 流式逐片喂、非流式一次喂完再 flush 出尾巴。<b>两条路径必须都过</b>——
     * 此前只有流式做了，非流式命中缓存时原文直出。</p>
     */
    private String applyOutboundGuard(String text) {
        SensitiveWordStreamGuard guard = newOutboundGuard();
        if (guard == null || text == null) {
            return text;
        }
        return guard.accept(text) + guard.flush();
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
        QuotaDecision quota = quotaGuard.check(null);
        if (quota.shouldBlock()) {
            return Mono.just(QUOTA_EXCEEDED_REPLY);
        }
        touchSession(sessionId);
        if (semanticCache == null) {
            return invokeAgent(sessionId, userText);
        }
        // 查缓存要调 Embedding（阻塞 HTTP），必须挪到弹性线程池，不能占用调用线程
        return Mono.fromCallable(() -> semanticCache.lookup(sessionId, userText))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(cached -> cached
                .map(answer -> {
                    incr(M_CACHE_HIT);
                    // 命中的内容仍要过一遍出站敏感词过滤：写入时合规不代表现在合规，词库是会变的。
                    // 口径与流式命中路径 streamCachedAnswer 完全一致，两条路径不得再分叉。
                    return Mono.just(applyOutboundGuard(answer));
                })
                .orElseGet(() -> invokeAgent(sessionId, userText)
                    .doOnNext(reply -> cacheReply(sessionId, userText, reply))));
    }

    /** 真正走一遍 Agent（装配 + 知识检索 + 模型调用）——缓存未命中时才发生。 */
    private Mono<String> invokeAgent(String sessionId, String userText) {
        ReActAgent agent = resolveAgent(sessionId);
        RuntimeContext ctx = agentFactory.contextFor(sessionId);
        bindCallMeta(ctx, agent, userText);

        return withSessionLock(sessionId, () ->
            agent.call(spotlightAttachments(userText), ctx)
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
     * 异步写缓存。
     *
     * <p>写入同样要调 Embedding，放在响应链里会把这次的延迟凭空加上去——而用户此刻已经拿到答案了。
     * 兜底回复不写缓存：把"服务开小差"缓存起来，后面每个问到同类问题的人都会收到它。</p>
     */
    private void cacheReply(String sessionId, String userText, String reply) {
        if (FALLBACK_REPLY.equals(reply) || QUOTA_EXCEEDED_REPLY.equals(reply)) {
            return;
        }
        Mono.fromRunnable(() -> semanticCache.put(sessionId, userText, reply))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    /**
     * 处理一条用户消息，流式返回增量文本（对应⑤ 逐 token 渲染）。
     *
     * <p>订阅框架的细粒度事件流 {@code streamEvents(...)}，只取正文增量 {@code TEXT_BLOCK_DELTA} 下发。
     * 会话状态由框架在流结束后自动持久化。</p>
     *
     * <p><b>为什么不用 {@code stream(msgs, options, ctx)}</b>：那组重载已标记 {@code forRemoval}，且它按
     * {@code isLast=true} 回放"每轮推理整段 + 最终 AGENT_RESULT 全文"，消费侧必须自己做两级去重才不会让
     * 用户看到同一段话重复 2-3 遍。细粒度事件把"增量"和"汇总"拆成了不同事件类型，去重逻辑随之消失。</p>
     *
     * @return 增量文本片段流（Flux，非阻塞）
     */
    public Flux<String> chatStream(String sessionId, String userText) {
        log.info("[session {}] received user message (stream): {}", sessionId, userText);
        QuotaDecision quota = quotaGuard.check(null);
        if (quota.shouldBlock()) {
            return Flux.just(QUOTA_EXCEEDED_REPLY);
        }
        touchSession(sessionId);
        if (semanticCache == null) {
            return streamFromAgent(sessionId, userText, new AtomicBoolean(false));
        }
        // 查缓存要调 Embedding（阻塞 HTTP），必须挪到弹性线程池，理由同非流式路径
        return Mono.fromCallable(() -> semanticCache.lookup(sessionId, userText))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(cached -> cached
                .map(this::streamCachedAnswer)
                .orElseGet(() -> streamFromAgentAndCache(sessionId, userText)));
    }

    /**
     * 命中缓存后的下发：切片推出，<b>不</b>人为加延迟。
     *
     * <p>切片是为了让前端行为与真实流式一致（一个超长 chunk 会让消息气泡突然撑开），
     * 而不是为了模拟打字效果——缓存的价值就是快，把省下来的时间再睡回去是自欺。</p>
     *
     * <p><b>命中的内容仍要过一遍出站敏感词过滤</b>：写入时合规不代表现在合规，词库是会变的。
     * 过滤器对已经干净的文本是幂等的，这一遍纯内存操作，成本可以忽略。</p>
     */
    private Flux<String> streamCachedAnswer(String answer) {
        incr(M_CACHE_HIT);
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < answer.length(); i += CACHED_ANSWER_CHUNK_SIZE) {
            chunks.add(answer.substring(i, Math.min(i + CACHED_ANSWER_CHUNK_SIZE, answer.length())));
        }
        return applyOutboundGuard(Flux.fromIterable(chunks), newOutboundGuard());
    }

    /**
     * 走 Agent 并在流正常结束后异步写缓存。
     *
     * <p>累积的是<b>过滤后</b>的文本——缓存的必须是真正发给用户的那一份，
     * 否则下次命中会把未过滤内容直接吐出去。</p>
     *
     * <p>用 {@code doOnComplete} 而不是 {@code doFinally}：后者在错误路径上也会跑，
     * 而中途失败的流里累积的是"半截回答 + 兜底文案"，把它缓存下来，之后每个问到同类问题的人
     * 都会收到这段残缺的回复。降级标志由 {@link #streamFromAgent} 在兜底时置位。</p>
     */
    private Flux<String> streamFromAgentAndCache(String sessionId, String userText) {
        StringBuilder accumulated = new StringBuilder();
        AtomicBoolean degraded = new AtomicBoolean(false);
        return streamFromAgent(sessionId, userText, degraded)
            .doOnNext(accumulated::append)
            .doOnComplete(() -> {
                if (!degraded.get()) {
                    cacheReply(sessionId, userText, accumulated.toString());
                }
            });
    }

    /**
     * 真正走一遍 Agent 的流式链路——缓存未命中时才发生。
     *
     * @param degraded 出参：走了兜底（超时 / 调用失败）时置位，调用方据此决定不写缓存
     */
    private Flux<String> streamFromAgent(String sessionId, String userText, AtomicBoolean degraded) {
        ReActAgent agent = resolveAgent(sessionId);
        RuntimeContext ctx = agentFactory.contextFor(sessionId);
        bindCallMeta(ctx, agent, userText);

        Flux<String> flux = withSessionLockFlux(sessionId, Flux.defer(() -> {
            // 兜底标记（每次订阅独立）：正常流式下模型逐块吐 TEXT_BLOCK_DELTA，最终的 AGENT_RESULT
            // 只是同一段文本的汇总，不再下发；仅当一个增量都没出现时（非流式 provider）才用它补全文
            AtomicBoolean deltaSeen = new AtomicBoolean(false);
            // 出站敏感词过滤：每次订阅一个独立 guard（有状态，跨流复用会串内容）
            SensitiveWordStreamGuard guard = newOutboundGuard();
            return applyOutboundGuard(agent.streamEvents(List.of(toUserMsg(spotlightAttachments(userText))), ctx)
                // 旧的 stream(...) 在末尾自带 publishOn(boundedElastic)，streamEvents 没有：不切走
                // 就会在模型 IO 线程上跑下游的敏感词过滤与 SSE 写出，拖慢模型侧的 chunk 读取
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .mapNotNull(event -> {
                    // 正文增量：只认 TextBlock，思考过程（THINKING_BLOCK_DELTA）不下发给用户
                    if (event instanceof TextBlockDeltaEvent delta) {
                        String text = delta.getDelta();
                        if (text == null || text.isEmpty()) {
                            return null;
                        }
                        deltaSeen.set(true);
                        return text;
                    }
                    // 非流式模型兜底：一个增量都没出过时，用最终结果补一次全文，避免空回复
                    if (event instanceof AgentResultEvent result && !deltaSeen.get()) {
                        String text = result.getResult() == null ? null : result.getResult().getTextContent();
                        return text == null || text.isEmpty() ? null : text;
                    }
                    return null;
                }), guard);
        }));

        // SSE 空闲超时（框架 #1741 缓解）：相邻元素间隔超过阈值即超时收尾，避免连接泄漏。<=0 禁用。
        long idle = properties.getStream().getIdleTimeoutSeconds();
        if (idle > 0) {
            flux = flux.timeout(Duration.ofSeconds(idle));
        }

        return flux.onErrorResume(e -> {
            degraded.set(true);
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
     * <p>结构化输出走的是框架 fallback 工具路径（{@code generate_response} 被当作普通工具塞给模型，
     * 框架本身不会用 {@code tool_choice} 强制调用，见 agentscope-java #1852/#1699）：模型若这一轮
     * 没有主动选择调用该工具，{@link Msg#hasStructuredData()} 就会是 false，这不是异常情况，是该
     * 路径本身"不保证命中"的已知限制。提示词里显式要求模型必须调用该工具，只是提高命中率，不能
     * 保证 100% 生效；未命中时走 other 兜底，交由人工处理，不影响主链路可用性。</p>
     *
     * @return 结构化意图；模型未产出结构化数据或调用异常时，返回一个标注为 other 的兜底结果
     */
    public Mono<IntentResult> classifyIntent(String sessionId, String userText) {
        // agent.call() 内部的 seedSystemMsg 只要注册了任意覆写 onSystemPrompt 的 MiddlewareBase
        // （本项目里 TenantContextMiddleware/DialogStageMiddleware 都覆写了），就会同步 block()
        // 一条 Mono 流水线（ReActAgent.applySystemPromptMiddlewares）。chat()/chatStream() 都经由
        // withSessionLock(Flux) 先 subscribeOn(boundedElastic) 再执行 agent 调用，唯独这里此前直接
        // 把 Mono 链交给调用方在 WebFlux 请求线程（reactor-http-nio-*）上订阅，会命中 Reactor 的
        // "不允许在非阻塞线程上 block()" 检测而抛 IllegalStateException。用 Mono.defer + subscribeOn
        // 把 agent 创建与调用整体挪到 boundedElastic 线程上执行，与 chat()/chatStream() 保持一致。
        return Mono.defer(() -> {
                log.info("[session {}] structured intent classification: {}", sessionId, userText);
                String intentSessionId = "intent:" + sessionId;
                ReActAgent intentAgent = agentFactory.createAgent(intentSessionId);
                RuntimeContext ctx = agentFactory.contextFor(intentSessionId);

                String prompt = "请判断以下用户消息的意图，并调用工具输出结构化结果，不要直接用文本回答。"
                    + "必须调用一次生成结构化响应的工具，且 intent 字段只能是"
                    + " presale/consult/order/refund/complaint/other 之一。"
                    + "待分类的用户消息：" + userText;
                return intentAgent.call(prompt, IntentResult.class, ctx)
                    .map(msg -> msg.hasStructuredData()
                        ? msg.getStructuredData(IntentResult.class)
                        : fallbackIntent(sessionId, "model did not produce structured output"));
            })
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .onErrorResume(e -> {
                log.error("[session {}] intent classification failed, code={}", sessionId,
                    "INTENT_CLASSIFY_ERROR", e);
                return Mono.just(fallbackIntent(sessionId, e.getMessage()));
            });
    }

    /** 意图识别兜底结果：结构化输出未命中（框架已知限制）或调用异常时统一走此分支。 */
    private IntentResult fallbackIntent(String sessionId, String reason) {
        log.info("[session {}] intent classify fallback to other, code={}, reason={}",
            sessionId, "INTENT_CLASSIFY_FALLBACK", reason);
        incr(M_INTENT_CLASSIFY_ERRORS);
        return new IntentResult("other", "", false, "意图识别未命中，转人工兜底");
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

    /**
     * 主动结束并清理会话：移除热缓存、删除持久化状态。
     *
     * <p>不再显式清理会话锁——锁对象由 {@link SessionLock} 实现自行回收
     * （进程内实现按使用者计数摘除，分布式实现靠 lease 过期），
     * 这里若强行清理反而可能把正在使用中的锁摘掉。</p>
     */
    public void endSession(String sessionId) {
        discardSession(sessionId);
        inviteCsat(sessionId);
        log.info("[session {}] ended and cleaned", sessionId);
    }

    /**
     * 清理会话但<b>不</b>发满意度邀请：给评测、合成监控这类"背后没有真人"的会话用。
     *
     * <p>它们同样需要清理会话资源，但计进 CSAT 就是在污染指标——机器人自问自答永远不会有人评分，
     * 每跑一轮评测就往回收率的分母里灌一批空邀请，把真实用户的满意度稀释成一个越来越难看的数字。
     * 实测本机库里 10 条 CSAT 记录全部来自评测，没有一条来自真实会话。</p>
     */
    public void discardSession(String sessionId) {
        sessionAgents.remove(sessionId);
        sessionActivity.remove(sessionId);
        try {
            RuntimeContext ctx = agentFactory.contextFor(sessionId);
            sessionStateManager.delete(ctx.getUserId(), ctx.getSessionId());
        } catch (Exception e) {
            log.error("[session {}] delete persisted state failed (ignored), code={}", sessionId,
                "SESSION_DELETE_ERROR", e);
        }
    }

    /**
     * 会话结束时发出满意度邀请。
     *
     * <p>用户端真正的结束动作走工单状态机（关单 / 确认解决），那条链路的邀请由
     * {@code CsatTicketInviteListener} 负责；这里覆盖的是"用户默默离开、会话空闲超时"那一类。</p>
     *
     * <p>邀请必须在这里发而不是等用户想起来评：不记邀请就<b>算不出回收率</b>，
     * 而回收率低时那个漂亮的 CSAT 只代表愿意评价的一小撮人。{@code invite} 自身幂等，
     * 会话被多次结束（超时清理 + 用户主动关闭）不会把分母灌水。</p>
     *
     * <p>失败只记日志：满意度是旁路指标，不该让会话清理因此中断。</p>
     */
    private void inviteCsat(String sessionId) {
        if (csatService == null || !properties.getCsat().isInviteOnSessionEnd()) {
            return;
        }
        try {
            csatService.invite(sessionId);
        } catch (Exception e) {
            log.error("[session {}] csat invite failed (ignored), code={}", sessionId,
                "CSAT_INVITE_ERROR", e);
        }
    }

    /**
     * 热配置刷新：清空进程内热 Agent 缓存，使下一次会话请求按最新配置（提示词/MCP/maxIters）重建 Agent。
     *
     * <p>只清热缓存，<b>不动</b> {@code AgentStateStore}（会话短期状态）与 {@code SessionLock}（会话锁）：
     * "淘汰即重建、状态从 StateStore 恢复"是既有 LRU 淘汰路径已验证的行为，热更新复用同一路径即可，不会
     * 丢失任何进行中会话的上下文。模型链的热替换走 {@code MutableDelegatingModel#swap}，与本方法互补
     * （模型链是共享单例，无需重建 Agent 即生效；提示词/MCP/maxIters 绑定在 Agent 上，需重建）。</p>
     */
    public void flushHotAgents() {
        int before = sessionAgents.size();
        sessionAgents.clear();
        log.info("[hot-config] flushed hot agents, evicted={}, state preserved in store", before);
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
     * 绑定本次调用的元数据到 RuntimeContext，供 {@code AgentCallTimingMiddleware} 采集分段耗时。
     *
     * <p>8080 客服链路：username 取会话解析出的租户（{@code ctx.getUserId()}），agentCode/agentName 取
     * Agent 名称，会话类型固定 CHAT，question 为本轮用户输入；requestId 缺省交由中间件回退 MDC。绑定异常
     * 不影响主对话链路（采集为旁路，防御式兜底最终收敛在中间件）。</p>
     */
    private void bindCallMeta(RuntimeContext ctx, ReActAgent agent, String userText) {
        try {
            String agentName = agent == null ? null : agent.getName();
            ctx.put(AgentCallMeta.class, new AgentCallMeta(
                null, ctx.getUserId(), agentName, agentName, AgentCallSessionType.CHAT, userText));
        } catch (Exception e) {
            log.error("bind agent call meta failed, code={}", "CALLLOG-META-BIND-FAIL", e);
        }
    }

    /**
     * 会话级锁包装（Mono）：同一 sessionId 的请求串行执行，防止并发写 StateStore 导致状态覆盖。
     * 在 boundedElastic 上获取锁并执行，不阻塞 Netty 事件循环线程。
     */
    private <T> Mono<T> withSessionLock(String sessionId, Supplier<Mono<T>> supplier) {
        return Mono.fromCallable(() -> sessionLock.acquire(sessionId))
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .flatMap(releasable -> supplier.get()
                // 释放挂在内层：doFinally 只有拿到锁之后才注册，避免获取失败时误释放别人的锁
                .doFinally(signal -> releasable.release()));
    }

    /**
     * 会话级锁包装（Flux）：同 {@link #withSessionLock}，用于流式输出。
     */
    private <T> Flux<T> withSessionLockFlux(String sessionId, Flux<T> flux) {
        return Mono.fromCallable(() -> sessionLock.acquire(sessionId))
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .flatMapMany(releasable -> flux.doFinally(signal -> releasable.release()));
    }

    private Msg toUserMsg(String userText) {
        return Msg.builder()
            .role(io.agentscope.core.message.MsgRole.USER)
            .name("user")
            .content(io.agentscope.core.message.TextBlock.builder().text(userText).build())
            .build();
    }
}
