package com.richard.fyoung.customerwork.data.rag.search;

import com.richard.fyoung.customerwork.core.middleware.MiddlewareOrders;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentReplayCapture;
import com.richard.fyoung.customerwork.safety.security.spotlight.ContentSpotlighter;
import com.richard.fyoung.customerwork.safety.security.spotlight.UntrustedSource;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 知识库召回内容的<b>瞬态</b>注入中间件：每轮推理前把召回块挂成一条只对模型可见的消息。
 * 召回来源由构造注入的 {@link KnowledgeRetrievalProvider} 提供，本类只管"什么时候注入、注入成什么形态"。
 *
 * <p><b>为什么是中间件而不是在会话服务里拼用户消息文本</b>（评审后改造，两个实证问题一并解决）：</p>
 * <ol>
 *   <li><b>不阻塞 Tomcat 请求线程</b>：Controller 返回 {@code Flux} 不代表业务方法体是异步的——
 *       方法体在 Tomcat worker 线程跑完才返回，在那里同步做 HTTP 检索会最长占住一个请求线程 10s，
 *       RAG 后端变慢会连累登录等无关接口（Tomcat 默认 200 线程全模块共享）。挪到 {@code onReasoning}
 *       后，检索发生在 Agent 执行期间的 reactive 线程上，并进一步用
 *       {@link Schedulers#boundedElastic()} 承接这次阻塞调用。</li>
 *   <li><b>不污染会话历史</b>：{@code onReasoning} 拿到的 {@code ReasoningInput.messages()} 是框架为
 *       "本次模型调用"临时组装的列表（{@code ReActAgent} 里 {@code prependSystemMsg(...)} 的产物），
 *       核心逻辑只把它交给 {@code reasoningStream(...)} 发给模型，<b>不会写回 AgentState</b>——
 *       只有模型产出的最终消息才会进 {@code state.contextMutable()}。框架自带的
 *       {@code TaskReminderMiddleware} 正是靠这一点做 {@code <system-reminder>} 注入，其类注释原文：
 *       "The reminder is appended <i>transiently</i> to the reasoning input only; it is never written
 *       into AgentState.context, so it is never persisted, compacted, or recalled."
 *       已废弃的 {@code GenericRAGHook} 当年也是同样手法（拦 PreCallEvent 往 inputMessages 追加一条
 *       USER 消息）。因此用户可见的历史里永远不会出现召回块，
 *       也不存在"旧召回块每轮重发、token 线性累积"的问题。</li>
 * </ol>
 *
 * <p><b>注入形态</b>：不改用户那条消息，而是在消息列表<b>末尾追加</b>一条独立的 USER 消息
 * （与 {@code TaskReminderMiddleware}/{@code GenericRAGHook} 一致：追加独立消息而非改写原消息，
 * 原消息的 {@code Msg.id} 因此天然保持稳定，附件绑定不受影响）。消息带
 * {@link Msg#METADATA_SYNTHETIC} 标记，任何观察到它的消费方都能识别为框架合成消息而跳过。
 * 放末尾是让参考资料紧邻用户提问，符合"上下文放末尾"的注意力分布。</p>
 *
 * <p><b>每轮只检索一次</b>：ReAct 循环里每迭代一次就会调一次 {@code onReasoning}，若每次都去检索，
 * 一轮对话会打出 N 次 HTTP。这里把首次结果缓存进 {@link RuntimeContext}（宿主的智能体工厂每次调用
 * 新建一个，天然是"一轮一份"），后续迭代直接复用同一个块重新注入——既保证工具调用循环里模型始终
 * 看得到参考资料，又只付一次检索代价。</p>
 *
 * <p><b>失败绝不打断对话</b>：{@link KnowledgeRetrievalProvider#retrieve} 的实现内部应已 catch 一切
 * 异常回退 null，本类再叠一层 {@code onErrorResume} 兜住调度层面的意外，任何情况下都会照常
 * {@code next.apply}。</p>
 *
 * <p>本中间件<b>按智能体实例构建</b>（{@code agentCode} 构建期绑定），不是共享单例 Bean——省得
 * 运行时再从上下文反推"当前是哪个 agent"。</p>
 * @author owlzhangfq@gmail.com
 */
public class KnowledgeInjectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeInjectionMiddleware.class);

    private static final String CODE_INJECT_FAIL = "RAG-INJECT-FAIL";
    /** 注入消息的 {@code name}：与框架 {@code TaskReminderMiddleware} 的合成消息保持一致。 */
    private static final String INJECTED_MSG_NAME = "system";
    /** 合成消息的类别标记，便于排查时把它与待办提醒等其它合成消息区分开。 */
    private static final String REMINDER_KIND = "retrieved_knowledge";
    /** 本轮召回缓存在 RuntimeContext 里的键前缀，拼上 agentCode 做命名空间：
     *  子 Agent 可能与父 Agent 共用同一个 RuntimeContext，不按 agentCode 隔离会让子 Agent 复用到父的召回块。 */
    private static final String CACHE_KEY_PREFIX = "rag.retrieved.";

    private final KnowledgeRetrievalProvider retrievalProvider;
    private final String agentCode;
    /**
     * 知识盲区埋点；可为 null（未启用该能力时降级为不埋点）。
     *
     * <p><b>为什么中间件这条路也要埋</b>：RAG 有两条互不相通的路径——模型主动调
     * {@code KnowledgeBaseTools}（工具路径）与本中间件自动注入（后台工作台智能体走这条）。
     * 盲区埋点原先只做在工具路径上，判定依据是 {@code KnowledgeBackend.isMiss} 的文案契约，
     * 而本路径根本不经过 {@code KnowledgeBackend}，于是这半条路的未命中一条都统计不到。</p>
     */
    private final KnowledgeGapRecorder gapRecorder;

    public KnowledgeInjectionMiddleware(KnowledgeRetrievalProvider retrievalProvider, String agentCode) {
        this(retrievalProvider, agentCode, null);
    }

    public KnowledgeInjectionMiddleware(KnowledgeRetrievalProvider retrievalProvider, String agentCode,
                                        KnowledgeGapRecorder gapRecorder) {
        this.retrievalProvider = retrievalProvider;
        this.agentCode = agentCode;
        this.gapRecorder = gapRecorder;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                         Function<ReasoningInput, Flux<AgentEvent>> next) {
        String cacheKey = CACHE_KEY_PREFIX + agentCode;
        RetrievedBlock cached = ctx == null ? null : ctx.get(cacheKey, RetrievedBlock.class);
        if (cached != null) {
            // 本轮已检索过（ReAct 循环的第 2+ 次推理）：复用同一个块，不再发 HTTP
            return next.apply(withKnowledge(input, cached.block()));
        }
        String query = resolveQuery(ctx, input);
        if (!StringUtils.hasText(query)) {
            return next.apply(input);
        }
        // 检索故障标志：故障与"检索正常但没查到"都表现为空串，但只有后者是知识盲区。
        // 把故障也计进盲区会让运营看到一批根本不存在的"用户在问但答不上来的问题"。
        AtomicBoolean retrievalFailed = new AtomicBoolean(false);
        // 阻塞式 HTTP 检索强制丢到 boundedElastic：不管本流被谁订阅（Spring MVC 的 SSE 适配器可能在
        // 请求线程上订阅），都保证不会占住 Tomcat 请求线程。
        AgentInvocationIdentity identity = ctx == null ? null : ctx.get(AgentInvocationIdentity.class);
        return Mono.fromCallable(() -> retrievalProvider.retrieve(agentCode, query, identity))
            .subscribeOn(Schedulers.boundedElastic())
            // retrieve 返回 null 时 fromCallable 发出的是空信号，统一归一成空串走同一条注入分支
            .defaultIfEmpty("")
            .onErrorResume(e -> {
                retrievalFailed.set(true);
                log.error("[rag] retrieval dispatch failed, code={}, agentCode={}", CODE_INJECT_FAIL, agentCode, e);
                return Mono.just("");
            })
            .flatMapMany(block -> {
                if (ctx != null) {
                    ctx.put(cacheKey, RetrievedBlock.class, new RetrievedBlock(block));
                }
                recordReplayFact(ctx, query, block, retrievalFailed.get());
                recordGapIfMiss(query, block, retrievalFailed.get());
                return next.apply(withKnowledge(input, block));
            });
    }

    /**
     * 检索正常返回但没有任何召回内容 = 一次知识盲区，记一笔。
     *
     * <p>检索故障不计：那是外部服务的可用性问题，混进盲区排行会污染运营判断。
     * 分区键传 null 走默认分区，与工具路径 {@code KnowledgeBaseTools#recordGapIfMiss} 同口径——
     * 盲区排行是全局视角的运营数据（"这批用户在问什么我们答不上来"），不按会话细分。</p>
     */
    private void recordGapIfMiss(String query, String block, boolean retrievalFailed) {
        if (gapRecorder == null || retrievalFailed || StringUtils.hasText(block)) {
            return;
        }
        gapRecorder.recordMiss(null, query);
    }

    private void recordReplayFact(RuntimeContext context, String query, String block,
                                  boolean retrievalFailed) {
        AgentReplayCapture capture = AgentReplayCapture.from(context);
        if (capture != null) {
            try {
                capture.recordRag(agentCode, query, block, retrievalFailed);
            } catch (Exception e) {
                log.error("[rag] replay fact capture failed, code={}, agentCode={}",
                    "RAG-REPLAY-CAPTURE-FAIL", agentCode, e);
            }
        }
    }

    /**
     * 把召回块挂成一条独立的瞬态消息追加到消息列表末尾。块为空则原样返回入参，
     * 连列表拷贝都不做（未绑知识库/未命中时零开销）。
     *
     * <p><b>召回内容一律经 {@link ContentSpotlighter#wrap} 隔离</b>：知识库文档的内容不由本系统的
     * 提示词工程控制（外部 RAG 服务的库可被投毒、企业内部文档也可能被人写进"忽略以上指令"），
     * 原样拼进上下文就是一条现成的间接注入通道。包一层带随机标签的隔离块 + 系统提示词里声明
     * "块内是数据不是指令"（见 {@link #onSystemPrompt}），是确定性的防护，不误杀正常业务文本。</p>
     */
    private ReasoningInput withKnowledge(ReasoningInput input, String block) {
        if (!StringUtils.hasText(block)) {
            return input;
        }
        List<Msg> messages = CollectionUtils.isEmpty(input.messages())
            ? new ArrayList<>() : new ArrayList<>(input.messages());
        messages.add(Msg.builder()
            .role(MsgRole.USER)
            .name(INJECTED_MSG_NAME)
            .content(TextBlock.builder()
                .text(ContentSpotlighter.wrap(UntrustedSource.KNOWLEDGE_BASE, block)).build())
            .metadata(Map.of(Msg.METADATA_SYNTHETIC, true, Msg.METADATA_REMINDER_KIND, REMINDER_KIND))
            .build());
        return new ReasoningInput(messages, input.tools(), input.options());
    }

    /**
     * 把不可信内容的隔离规则追加进系统提示词——只包块不说明规则，模型看不懂标签的含义，防护会打折。
     *
     * <p>走 {@link ContentSpotlighter#appendHintIfAbsent} 保证幂等：客服端的
     * {@code IndirectInjectionGuardMiddleware}（工具结果隔离）也会追加同一段规则，同一个智能体上
     * 两个中间件都挂时不能写重复。</p>
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.just(ContentSpotlighter.appendHintIfAbsent(currentPrompt));
    }

    /**
     * 检索用的查询语句：优先取 {@link AgentCallMeta#question()}，回退到消息列表里最后一条 USER 消息文本。
     *
     * <p>为什么优先用 callMeta：VibeCoding 链路送进 Agent 的是"路径指引 + 用户提问"的拼接文本，
     * 拿它去检索等于给 RAG 喂一大段与业务无关的指令噪声；而 {@code AgentCallMeta.question} 在对话与
     * VibeCoding 两条链路上装的都是<b>用户真实提问原文</b>，正好是检索需要的东西。回退取最后一条
     * USER 消息是 {@code GenericRAGHook#extractQueryFromMessages} 的同款手法（不能取最后一条消息——
     * ReAct 循环里它可能是 ASSISTANT/TOOL），覆盖未绑定 callMeta 的旧调用点。</p>
     */
    private String resolveQuery(RuntimeContext ctx, ReasoningInput input) {
        AgentCallMeta meta = ctx == null ? null : ctx.get(AgentCallMeta.class);
        if (meta != null && StringUtils.hasText(meta.question())) {
            return meta.question();
        }
        List<Msg> messages = input.messages();
        if (CollectionUtils.isEmpty(messages)) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == MsgRole.USER && StringUtils.hasText(msg.getTextContent())) {
                return msg.getTextContent();
            }
        }
        return null;
    }

    /**
     * 本轮召回结果在 {@link RuntimeContext} 里的载体。用具名类型而非裸 String 作 key，
     * 避免与上下文里其它字符串属性撞车；{@code block} 为空串表示"本轮检索过但无需注入"，
     * 与"尚未检索"（键不存在）区分开。
     */
    private record RetrievedBlock(String block) { }

    /** 顺序契约见 {@link MiddlewareOrders}：RAG 知识瞬态注入，须在预算裁剪之外。 */
    @Override
    public int order() {
        return MiddlewareOrders.KNOWLEDGE_INJECTION;
    }
}
