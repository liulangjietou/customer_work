package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySyncService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.ToolSourceInfo;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionMode;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionModeRegistry;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService.PlanChannel;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordStreamGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 工作区对话服务：从 {@link AgentInstanceCache} 取（或惰性构建）智能体实例，流式对话。
 *
 * <p>与 {@code CustomerServiceService#chatStream} 同一套"细粒度事件流 -&gt; 展示片段"手法
 * （框架 {@code streamEvents(msgs, ctx)}），区别仅在于 Agent 实例来源：那边是启动期固定装配的
 * 单例，这里是按 agentCode 动态取的缓存实例，且底层可能是 ReActAgent 也可能是 HarnessAgent
 * （两者各自声明 {@code streamEvents}，本类按运行时类型分派，见 {@link #streamEvents}）。</p>
 *
 * <p>一轮流式对话正常结束（含内部异常被兜底成 {@link #FALLBACK_REPLY} 后正常结束的情形）后，主动调用
 * {@link ChatHistoryCache#evict} 让该智能体的历史会话列表缓存与本次会话的消息缓存立即失效——写路径本身
 * 不变（仍是 {@code MysqlAgentStateStore} 同步落库），只是让 {@link ChatHistoryService} 的 30 分钟读
 * 缓存不必等自然过期就能看到最新一轮对话，VibeCoding 复用同一个 {@code chatStream} 天然一并覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String FALLBACK_REPLY = "抱歉，我暂时无法处理这个请求，请稍后重试。";
    /** 父 Agent 自身事件在 per-source 状态表里的 key（{@code AgentEvent#getSource() == null}）。 */
    private static final String MAIN_AGENT_SOURCE_KEY = "";
    /** "调用大模型"节点的展示文案。 */
    private static final String MODEL_CALL_TEXT = "调用大模型";
    /** 子 Agent 调用链 path 的分隔符（框架 {@code AgentSpawnTool#buildSourcePath} 的约定：父会话/子 agentId）。 */
    private static final String SOURCE_PATH_SEPARATOR = "/";

    private final AgentInstanceCache agentInstanceCache;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final ChatHistoryCache historyCache;
    private final AgentMemorySyncService memorySyncService;
    private final ExecutionModeRegistry executionModeRegistry;
    private final PlanConfirmationService planConfirmationService;
    private final ChatAttachmentService chatAttachmentService;
    /** 出站敏感词过滤器；未开启 {@code admin.content-guard.agent-filter-enabled} 时为 null，跳过过滤。 */
    private final SensitiveWordFilter sensitiveWordFilter;
    /** 出站命中 BLOCK 时替换用的安全话术。 */
    private final String outboundSafeReply;

    public ChatService(AgentInstanceCache agentInstanceCache, AdminAgentInstanceFactory agentInstanceFactory,
                        ChatHistoryCache historyCache, AgentMemorySyncService memorySyncService,
                        ExecutionModeRegistry executionModeRegistry,
                        PlanConfirmationService planConfirmationService,
                        ChatAttachmentService chatAttachmentService,
                        ObjectProvider<SensitiveWordFilter> sensitiveWordFilterProvider,
                        ObjectProvider<ContentGuardProperties> contentGuardPropertiesProvider) {
        this.agentInstanceCache = agentInstanceCache;
        this.agentInstanceFactory = agentInstanceFactory;
        this.historyCache = historyCache;
        this.memorySyncService = memorySyncService;
        this.executionModeRegistry = executionModeRegistry;
        this.planConfirmationService = planConfirmationService;
        this.chatAttachmentService = chatAttachmentService;
        // 敏感词关闭时容器里没有该 Bean，此处为 null，出站过滤整体跳过
        this.sensitiveWordFilter = sensitiveWordFilterProvider == null
            ? null : sensitiveWordFilterProvider.getIfAvailable();
        ContentGuardProperties guardProperties = contentGuardPropertiesProvider == null
            ? null : contentGuardPropertiesProvider.getIfAvailable();
        this.outboundSafeReply = guardProperties == null || !StringUtils.hasText(guardProperties.getSafeReply())
            ? CustomerWorkProperties.SensitiveWord.DEFAULT_OUTBOUND_SAFE_REPLY
            : guardProperties.getSafeReply();
    }

    /**
     * 执行模式确认/拒绝（对话链路的 Plan 确认闭环，与 VibeCoding 共用同一套
     * {@link PlanConfirmationService}）：完成对应挂起项，中间件据此恢复或取消该工具调用。
     * planId 不存在/已处理/超时/服务重启后失效均 fast fail。
     */
    public void confirmPlan(String agentCode, String sessionId, String planId, boolean approved) {
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        boolean resolved = planConfirmationService.confirm(agentCode, safeSession, planId, approved);
        if (!resolved) {
            throw new BizException(ResultCode.PLAN_CONFIRM_NOT_FOUND);
        }
        log.info("[workspace] chat plan confirm handled, agentCode={}, sessionId={}, planId={}, approved={}",
            agentCode, safeSession, planId, approved);
    }

    /**
     * 安全中断指定会话正在执行的 Agent（协作式中断：只置一个信号，由 Agent 在推理/工具调用的
     * checkpoint 检查后才真正停下，不保证立即生效）。中断后再次对同一 sessionId 发起
     * {@link #chatStream}，框架会先无缝续跑被打断的挂起工具调用（见
     * {@link AdminAgentInstanceFactory#build} 里的 {@code enablePendingToolRecovery(true)}）。
     *
     * @return 是否成功发出中断信号；智能体运行时类型不支持中断（既非 ReActAgent 也非 HarnessAgent）时返回 false
     */
    public boolean interrupt(String agentCode, String sessionId) {
        Agent agent = agentInstanceCache.getOrBuild(agentCode);
        ReActAgent interruptible = resolveInterruptible(agent);
        if (interruptible == null) {
            log.info("[workspace] interrupt skipped: unsupported agent runtime, agentCode={}", agentCode);
            return false;
        }
        RuntimeContext ctx = agentInstanceFactory.contextFor(agentCode, sessionId);
        interruptible.interrupt(ctx);
        log.info("[workspace] interrupt issued, agentCode={}, sessionId={}", agentCode, sessionId);
        return true;
    }

    /** {@link HarnessAgent} 本身只暴露不带 session 参数的 interrupt()（走错误的 defaultSessionId=agentCode），
     * 真正按 (agentCode, sessionId) 精确路由需要拿到它内部委托的 {@link ReActAgent}。 */
    private ReActAgent resolveInterruptible(Agent agent) {
        if (agent instanceof ReActAgent reActAgent) {
            return reActAgent;
        }
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.getDelegate();
        }
        return null;
    }

    /**
     * 流式对话，返回增量文本片段。智能体不存在/未启用时，{@link AgentInstanceCache#getOrBuild}
     * 同步抛出的 {@code BizException} 会在本方法返回 Flux 之前就传播给调用方（Controller 侧因此在
     * 任何 SSE 头下发之前就能拿到结构化错误响应，而不是半开的失败流）。
     */
    public Flux<ChatStreamChunk> chatStream(String agentCode, String sessionId, String userText) {
        return chatStream(agentCode, sessionId, userText, null, null, usage -> { });
    }

    /** 流式对话（带执行模式，无用量观察者）：保留旧签名，供既有调用点/测试使用。 */
    public Flux<ChatStreamChunk> chatStream(String agentCode, String sessionId, String userText, String mode) {
        return chatStream(agentCode, sessionId, userText, mode, null, usage -> { });
    }

    /** 流式对话（带执行模式 + 调用元数据，无用量观察者）：供对话链路（ChatController）使用，采集耗时统计。 */
    public Flux<ChatStreamChunk> chatStream(String agentCode, String sessionId, String userText, String mode,
                                             AgentCallMeta callMeta) {
        return chatStream(agentCode, sessionId, userText, mode, callMeta, (List<String>) null, usage -> { });
    }

    /**
     * 流式对话（带执行模式 + 调用元数据 + 附件绑定，无用量观察者）：供对话链路（ChatController）使用。
     * {@code attachmentIds} 非空时在请求线程同步段把这些附件绑定到本条用户消息（框架 Msg.id）。
     *
     * <p>方法名末位加 {@code WithAttachments} 而非再重载六参：六参 {@code (…, callMeta, List)} 会与既有
     * {@code (…, callMeta, Consumer)} 在实参传 {@code null} 时产生调用歧义，故用具名方法规避。</p>
     */
    public Flux<ChatStreamChunk> chatStreamWithAttachments(String agentCode, String sessionId, String userText,
                                                            String mode, AgentCallMeta callMeta,
                                                            List<String> attachmentIds) {
        return chatStream(agentCode, sessionId, userText, mode, callMeta, attachmentIds, usage -> { });
    }

    /** 流式对话（带用量观察者，未指定执行模式）：保留旧签名，供既有调用点/测试使用。 */
    public Flux<ChatStreamChunk> chatStream(String agentCode, String sessionId, String userText,
                                             Consumer<ChatUsage> usageTotalObserver) {
        return chatStream(agentCode, sessionId, userText, null, null, usageTotalObserver);
    }

    /** 流式对话（带执行模式 + 用量观察者，未带调用元数据）：保留旧签名，供既有调用点/测试使用。 */
    public Flux<ChatStreamChunk> chatStream(String agentCode, String sessionId, String userText,
                                             String mode, Consumer<ChatUsage> usageTotalObserver) {
        return chatStream(agentCode, sessionId, userText, mode, null, (List<String>) null, usageTotalObserver);
    }

    /** 流式对话（带执行模式 + 调用元数据 + 用量观察者，无附件）：保留旧签名，供 VibeCoding 既有调用点/测试使用。 */
    public Flux<ChatStreamChunk> chatStream(String agentCode, String sessionId, String userText,
                                             String mode, AgentCallMeta callMeta,
                                             Consumer<ChatUsage> usageTotalObserver) {
        return chatStream(agentCode, sessionId, userText, mode, callMeta, (List<String>) null, usageTotalObserver);
    }

    /**
     * 流式对话（带执行模式 + 模型用量观察者，全参核心）：流终止时（完成/错误/取消）把本轮全部模型调用的
     * token 用量汇总后回调一次 {@code usageTotalObserver}——供 VibeCoding 审计记录 token 数（需求文档 §5.3）。
     * 本轮无任何用量信息（框架/模型未返回 usage）时回调 {@code null}。
     *
     * <p>聚合方式：细粒度事件流里每次模型调用结束都会带一条 {@link ModelCallEndEvent}，其
     * {@code usage} 就是这一次调用的用量，{@code replyId} 每次调用各不相同——按 replyId 去重后求和
     * 即本轮全部模型调用（含子 Agent 的）的合计。比旧路径"从事件消息上捞 {@code Msg#getUsage()}
     * 再按 messageId 去重"更贴合语义：那条路上增量事件与最终结果消息携带的口径并不统一。</p>
     *
     * <p><b>执行模式与 Plan 确认闭环</b>：订阅时把 {@code mode}（解析为 {@link ExecutionMode}）登记进
     * {@link ExecutionModeRegistry}（键 {@code agentCode:sessionId}），供 {@code ExecutionModeMiddleware}
     * 运行时读取；并打开一个 {@link PlanChannel} 把 {@code plan}/{@code plan_result} 事件合并进 SSE 输出
     * （对话与 VibeCoding 共用同一套挂起/确认闭环）。{@code doFinally} 时摘除模式登记、关闭通道。
     * mode 未指定/非法（{@link ExecutionMode#parse} 返回 null）→ 不登记，中间件回落全局语义。</p>
     *
     * <p><b>附件绑定</b>：{@code attachmentIds} 非空时，在<b>请求线程同步段</b>（构建 Flux 前、订阅前）把这些
     * 附件绑定到本条用户消息（框架 {@code Msg.id}）——用与 Plan/历史一致的归一 {@code safeSession} 落 session_id，
     * 保证历史接口按同一口径查回。绑定是旁路动作，任何失败只记录、不打断对话主流程（见
     * {@link ChatAttachmentService#bindToMessage}）。</p>
     */
    public Flux<ChatStreamChunk> chatStream(String agentCode, String sessionId, String userText,
                                             String mode, AgentCallMeta callMeta, List<String> attachmentIds,
                                             Consumer<ChatUsage> usageTotalObserver) {
        Agent agent = agentInstanceCache.getOrBuild(agentCode);
        RuntimeContext ctx = agentInstanceFactory.contextFor(agentCode, sessionId);
        // 绑定调用元数据供 AgentCallTimingMiddleware 采集（requestId/username/agentName/sessionType/question）；
        // 缺省（旧调用点/测试）不绑，中间件按 ctx.userId/MDC 降级，不影响主链路。
        if (callMeta != null) {
            ctx.put(AgentCallMeta.class, callMeta);
        }
        // 归一 sessionId：与下方 Plan 通道/执行模式登记、以及历史接口读取口径完全一致（hasText ? 原值 : default）。
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        // 知识库自动检索<b>不在这里做</b>：请求线程同步段做 HTTP 检索会最长占住一个 Tomcat 请求线程 10s
        // （返回 Flux 不等于方法体异步，方法体跑完才返回），RAG 后端变慢会连累登录等无关接口；而且拼进
        // 用户消息文本会让召回块随消息进 AgentState 被持久化，用户在历史里看到 <retrieved_knowledge> 原文、
        // 且每轮重发累积 token。现改由 KnowledgeRetrievalMiddleware 在推理阶段做瞬态注入（挂载点见
        // AdminAgentInstanceFactory#buildInnerReActAgent），本方法只管把用户原文送进去。
        // 本条用户消息提前构建，供附件绑定拿到稳定的 Msg.id（框架 Msg.Builder 构造即生成随机 UUID）。
        Msg userMsg = toUserMsg(userText);
        // 附件绑定：请求线程同步段完成（订阅前），把本条消息 id 与其携带的附件关联，供历史回显。
        if (!CollectionUtils.isEmpty(attachmentIds)) {
            chatAttachmentService.bindToMessage(agentCode, safeSession, userMsg.getId(), attachmentIds);
        }
        ToolSourceInfo toolSource = agentInstanceFactory.toolSourceFor(agentCode);

        // 跨事件的拼装状态（工具结果分片累积 / 子 Agent 正文累积 / 是否已流式过正文）收拢进
        // {@link StreamState}，并按事件来源（父 Agent 用 "" / 子 Agent 用其调用链 path）各存一份：
        // harness spawn 出的子 Agent 事件会与父 Agent 事件交错推到同一条流上（框架给子事件打了
        // {@code AgentEvent#getSource()} 标记），父子共用一份状态会把两股文本互相串进对方的缓冲区。
        // 每次 chatStream 各建一份，不跨请求共享。
        Map<String, StreamState> stateBySource = new ConcurrentHashMap<>();

        // 本轮各次模型调用的用量，流终止时汇总回调一次（完成/取消各有入口，正常只会命中其一）
        Map<String, ChatUsage> usageByModelCall = new ConcurrentHashMap<>();
        AtomicBoolean usageObserved = new AtomicBoolean(false);
        Runnable observeUsage = () -> {
            if (usageObserved.compareAndSet(false, true)) {
                usageTotalObserver.accept(totalUsage(usageByModelCall));
            }
        };
        // 出站敏感词过滤：每次请求一份独立的 guard 集合（有状态，跨请求复用会串内容）。
        // 挂在接入层而不是中间件里的理由见 SensitiveWordStreamGuard 类注释（guard 的滑动缓冲是
        // 每流一份的有状态对象，与 Agent 级共享的中间件 Bean 生命周期不符）。
        Map<String, SensitiveWordStreamGuard> outboundGuards = new ConcurrentHashMap<>();

        // Flux.defer 包一层：像 HarnessAgent 在沙箱资源获取失败时（如 docker 容器创建超时）是
        // 同步抛异常，不是发出错误信号——不包 defer 的话，streamEvents(...) 这行方法调用本身
        // 就会直接向外抛，导致整个 chatStream(...) 方法体同步抛出，下面的 .onErrorResume(...) 根本
        // 没机会接管。届时 Spring MVC 的 SSE 响应式适配器可能已经提交了响应头，连接就会挂起不报错
        // 也不关闭，前端永远卡在"生成中..."。defer 把方法调用推迟到订阅时执行，任何同步异常都会被
        // Reactor 自动转成 Flux.error(...)，从而能被 onErrorResume 正常捕获、优雅降级成兜底话术。
        Flux<ChatStreamChunk> body = Flux.defer(() -> streamEvents(agent, List.of(userMsg), ctx))
            // 旧的 stream(...) 在 AgentBase#createEventStream 末尾自带 publishOn(boundedElastic)，
            // streamEvents 没有：不切走的话下面的事件拼装、敏感词过滤与 SSE 写出全跑在模型 IO 线程上，
            // 拖慢框架侧读取模型 chunk 的速度。
            .publishOn(Schedulers.boundedElastic())
            .doOnNext(event -> collectUsage(usageByModelCall, event))
            .concatMap(event -> Flux.fromIterable(toChunks(event, toolSource, stateBySource)))
            .concatMap(chunk -> guardChunk(chunk, outboundGuards))
            .concatWith(Flux.defer(() -> flushGuards(outboundGuards)));

        Flux<ChatStreamChunk> conversation = Flux.concat(Flux.just(new ChatStreamChunk(ChatNodeKind.THINKING_START, "开始思考")), body)
            .concatWith(Flux.defer(() -> Flux.just(new ChatStreamChunk(ChatNodeKind.THINKING_END, "结束思考"))))
            .onErrorResume(e -> {
                log.error("[workspace] chat stream failed, code={}, agentCode={}", "WORKSPACE_CHAT_ERROR", agentCode, e);
                return Flux.just(
                    new ChatStreamChunk(ChatNodeKind.THINKING_END, "结束思考"),
                    new ChatStreamChunk(ChatNodeKind.ANSWER, FALLBACK_REPLY));
            })
            .doOnComplete(() -> {
                historyCache.evict(agentCode, sessionId);
                // 长期记忆回写：框架的记忆 flush 只落 workspace/MEMORY.md 工作副本，这里把变更同步回
                // 权威存储（默认库表）。非记忆智能体没有 MEMORY.md，方法内直接短路，无额外开销；
                // 同步失败只记日志不打断流（见 AgentMemorySyncService 的兜底约定）
                memorySyncService.persistIfChanged(agentCode, agentInstanceFactory.resolveWorkspace(agentCode));
            })
            // 用量回调必须在终止信号"向下游传播之前"执行，故用 peek 语义的 doOnComplete/doOnCancel
            // 而不是 doFinally：Reactor 的 doFinally 是先把 onComplete 传给下游、回调最后才跑，下游
            // （{@code VibeCodingService} 落审计的那个 doFinally）会抢在本回调之前读到还没写入的用量。
            // 上面的 onErrorResume 已把异常兜底成正常完成，错误路径同样走 onComplete。
            .doOnComplete(observeUsage)
            .doOnCancel(observeUsage);

        // 执行模式登记 + Plan 确认通道：与 RuntimeContext 一致地归一 sessionId，保证中间件按同一键读到模式、
        // 定位到同一通道。Flux.using 在订阅时（早于 Agent 产出任何事件）打开通道并把 plan/plan_result 事件
        // 合并进输出流；主流结束/取消时完成通道事件流并关闭通道。registry 用 doFirst/doFinally 配对登记与摘除，
        // 未指定/非法模式不登记（中间件回落全局语义）。BYPASS/无高风险时通道恒空、零开销。
        ExecutionMode executionMode = ExecutionMode.parse(mode);
        return Flux.using(
                () -> planConfirmationService.openChannel(agentCode, safeSession),
                channel -> Flux.merge(
                    conversation.doFinally(signal -> planConfirmationService.completeEvents(channel)),
                    planConfirmationService.events(channel)),
                planConfirmationService::closeChannel)
            .doFirst(() -> executionModeRegistry.put(agentCode, safeSession, executionMode))
            .doFinally(signal -> executionModeRegistry.remove(agentCode, safeSession));
    }

    /** 收集单次模型调用的用量：按 {@code replyId} 存一份（同一 replyId 只会来一条 MODEL_CALL_END）。 */
    private void collectUsage(Map<String, ChatUsage> usageByModelCall, AgentEvent event) {
        if (event instanceof ModelCallEndEvent modelCallEnd && modelCallEnd.getUsage() != null) {
            usageByModelCall.put(modelCallEnd.getReplyId(), modelCallEnd.getUsage());
        }
    }

    /** 汇总本轮全部模型调用的用量；一条都没有时返回 null（区分"确实没有用量信息"与"用了 0 token"）。 */
    private ChatUsage totalUsage(Map<String, ChatUsage> usageByModelCall) {
        if (usageByModelCall.isEmpty()) {
            return null;
        }
        int inputTokens = 0;
        int outputTokens = 0;
        int cachedTokens = 0;
        double time = 0;
        for (ChatUsage usage : usageByModelCall.values()) {
            inputTokens += usage.getInputTokens();
            outputTokens += usage.getOutputTokens();
            cachedTokens += usage.getCachedTokens();
            time += usage.getTime();
        }
        return new ChatUsage(inputTokens, outputTokens, cachedTokens, time);
    }

    /**
     * 对 ANSWER 片段做出站敏感词过滤；其余节点（思考、工具调用等）原样透传。
     *
     * <p>按 {@code source} 分别持有 guard：主 Agent 与各子 Agent 的正文是并行的两股文本，
     * 共用一个缓冲会把 A 的半个词接到 B 的开头。只过 ANSWER 是因为只有它会直接呈现给用户，
     * 思考过程与工具参数不进对话气泡。</p>
     */
    private Flux<ChatStreamChunk> guardChunk(ChatStreamChunk chunk,
                                             Map<String, SensitiveWordStreamGuard> guards) {
        if (sensitiveWordFilter == null || chunk.kind() != ChatNodeKind.ANSWER
            || !StringUtils.hasText(chunk.text())) {
            return Flux.just(chunk);
        }
        String key = chunk.source() == null ? MAIN_AGENT_SOURCE_KEY : chunk.source();
        SensitiveWordStreamGuard guard = guards.computeIfAbsent(key, k -> newOutboundGuard());
        String emit = guard.accept(chunk.text());
        return emit.isEmpty()
            ? Flux.empty()
            : Flux.just(new ChatStreamChunk(ChatNodeKind.ANSWER, emit, chunk.source(), chunk.subagentName()));
    }

    /**
     * 流末尾把各 guard 缓冲区里留住的尾巴吐出来。
     *
     * <p>不 flush 会吞掉正文最后几个字——那几个字符正是被刻意留住等下一片拼接的。</p>
     */
    private Flux<ChatStreamChunk> flushGuards(Map<String, SensitiveWordStreamGuard> guards) {
        if (guards.isEmpty()) {
            return Flux.empty();
        }
        List<ChatStreamChunk> tails = new ArrayList<>();
        guards.forEach((key, guard) -> {
            String tail = guard.flush();
            if (StringUtils.hasText(tail)) {
                tails.add(MAIN_AGENT_SOURCE_KEY.equals(key)
                    ? new ChatStreamChunk(ChatNodeKind.ANSWER, tail)
                    : new ChatStreamChunk(ChatNodeKind.ANSWER, tail, key, null));
            }
        });
        return Flux.fromIterable(tails);
    }

    /** 为一股输出流建一个 guard（有状态，每股流各一个）。 */
    private SensitiveWordStreamGuard newOutboundGuard() {
        return new SensitiveWordStreamGuard(sensitiveWordFilter, outboundSafeReply);
    }

    /**
     * 从一个框架事件拆出 0~N 个展示片段。
     *
     * <p><b>为什么用 {@code streamEvents} 而不是 {@code stream(msgs, options, ctx)}</b>：后者已标记
     * {@code forRemoval}，且它把"增量"和"汇总"混在同一个 {@code EventType.REASONING} 里靠
     * {@code isLast} 区分，消费侧必须自己按"新值是否以旧值为前缀"猜哪段是净增量；细粒度事件流把两者
     * 拆成了不同事件类型（{@code TEXT_BLOCK_DELTA} 是真增量，{@code AGENT_RESULT} 才是汇总），
     * 那套前缀猜测连同 {@code lastReasoningText}/{@code lastAnswerText}/{@code lastAnnouncedTool}/
     * {@code modelCallAnnounced} 四份去重状态一并删掉了。</p>
     *
     * <p><b>事件 → 节点映射</b>（父 Agent 与子 Agent 共用同一张表，差异见 {@link #toSubagentChunks}）：
     * <ul>
     *   <li>{@code MODEL_CALL_START} → {@link ChatNodeKind#MODEL_CALL}：框架每次真正调模型发一条，
     *       节点数天然等于模型被调用的次数，不用再自己数迭代；</li>
     *   <li>{@code THINKING_BLOCK_DELTA} → {@link ChatNodeKind#THINKING}（内部思考过程）；</li>
     *   <li>{@code TEXT_BLOCK_DELTA} → {@link ChatNodeKind#ANSWER}（可见回答正文增量）；</li>
     *   <li>{@code TOOL_CALL_START} → TOOL_SKILL/TOOL_MCP/TOOL_BUILTIN：框架已按 toolCallId 去重、
     *       且过滤掉了 {@code "__"} 前缀的内部占位名，这层不用再管分片重复；</li>
     *   <li>{@code TOOL_RESULT_TEXT_DELTA} 累积 → {@code TOOL_RESULT_END} 时吐一条
     *       {@link ChatNodeKind#TOOL_RESULT}：流式工具的分片与非流式工具的整段结果，框架都走这一对
     *       事件，累积后一次性成文，{@code TestReportParser} 拿到的是完整输出；</li>
     *   <li>{@code AGENT_RESULT} → 仅当本轮一个正文增量都没出现过（非流式 provider）时补一次全文。</li>
     * </ul>
     * 其余事件类型（block start/end、{@code TOOL_CALL_DELTA}、{@code HINT_BLOCK} 等）不进展示轨迹，
     * 直接忽略。</p>
     */
    private List<ChatStreamChunk> toChunks(AgentEvent event, ToolSourceInfo toolSource,
                                             Map<String, StreamState> stateBySource) {
        String source = event.getSource();
        if (source == null) {
            // 父 Agent 自身事件：前端拿到的 chunk 依旧 source/subagentName 均为 null。
            StreamState state = stateBySource.computeIfAbsent(MAIN_AGENT_SOURCE_KEY, k -> new StreamState());
            return toMainChunks(event, toolSource, state);
        }
        return toSubagentChunks(event, source, stateBySource);
    }

    /** 父 Agent 事件 → 展示片段，映射规则见 {@link #toChunks}。 */
    private List<ChatStreamChunk> toMainChunks(AgentEvent event, ToolSourceInfo toolSource, StreamState state) {
        if (event instanceof ModelCallStartEvent) {
            return List.of(new ChatStreamChunk(ChatNodeKind.MODEL_CALL, MODEL_CALL_TEXT));
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            return StringUtils.hasText(thinking.getDelta())
                ? List.of(new ChatStreamChunk(ChatNodeKind.THINKING, thinking.getDelta()))
                : List.of();
        }
        if (event instanceof TextBlockDeltaEvent text) {
            if (!StringUtils.hasText(text.getDelta())) {
                return List.of();
            }
            state.answerStreamed.set(true);
            return List.of(new ChatStreamChunk(ChatNodeKind.ANSWER, text.getDelta()));
        }
        if (event instanceof ToolCallStartEvent toolCall) {
            return StringUtils.hasText(toolCall.getToolCallName())
                ? List.of(new ChatStreamChunk(classifyToolSource(toolSource, toolCall.getToolCallName()),
                    describeToolCall(toolCall.getToolCallName())))
                : List.of();
        }
        if (event instanceof ToolResultTextDeltaEvent toolResultDelta) {
            state.appendToolResult(toolResultDelta.getToolCallId(), toolResultDelta.getDelta());
            return List.of();
        }
        if (event instanceof ToolResultEndEvent toolResultEnd) {
            return List.of(new ChatStreamChunk(ChatNodeKind.TOOL_RESULT,
                describeToolResult(toolResultEnd.getToolCallName(), state.takeToolResult(toolResultEnd.getToolCallId()))));
        }
        if (event instanceof AgentResultEvent agentResult) {
            // 非流式 provider 兜底：一个正文增量都没出过时，用最终结果补一次全文，避免空回复。
            // 已经逐字流出过就丢弃，否则同一段答案会先增量出现一遍、结束时又整段重复一遍。
            if (state.answerStreamed.get() || agentResult.getResult() == null) {
                return List.of();
            }
            String text = agentResult.getResult().getTextContent();
            return StringUtils.hasText(text) ? List.of(new ChatStreamChunk(ChatNodeKind.ANSWER, text)) : List.of();
        }
        return List.of();
    }

    /**
     * 子 Agent 事件 → 展示片段。harness spawn 出的子 Agent，其细粒度事件由框架的
     * {@code AgentEventEmitter} 转发进父流并打上 {@code AgentEvent#getSource()}（调用链 path），与父
     * Agent 的事件交错到达；片段统一带 {@code source} 与 {@code subagentName}，前端据此归入独立卡片。
     *
     * <p>与父 Agent 的差异：① 该 source 首次出现时先补一条 {@link ChatNodeKind#SUBAGENT_START}
     * （框架在 spawn 点补的 {@code AGENT_START} 一定是该 source 的首个事件）；② 工具一律归
     * {@link ChatNodeKind#TOOL_BUILTIN}（子 Agent 的工具不在父的 {@link ToolSourceInfo} 里，不为此
     * 额外查询）；③ 不补"调用大模型"节点，只复用需求约定的 THINKING/ANSWER/TOOL_BUILTIN/TOOL_RESULT；
     * ④ {@link ChatNodeKind#SUBAGENT_RESULT} 由 {@code AGENT_END} 触发、内容取本股流累积的正文。</p>
     *
     * <p><b>为什么 SUBAGENT_RESULT 不再取 {@code AGENT_RESULT}</b>：新路径上子 Agent 是被
     * {@code AgentSpawnTool} 以 {@code call()} 驱动的，它自己的 {@code AgentResultEvent} 在子流内部
     * 就被 {@code callInternal} 取走当返回值了，到不了父流；父流能看到的只有工具侧补发的
     * {@code AGENT_START}/{@code AGENT_END} 与中间的细粒度事件。故改为在 {@code AGENT_END} 处用累积
     * 正文补一条收尾节点，前端协议（{@link ChatNodeKind}/{@link ChatStreamChunk}）不变。</p>
     */
    private List<ChatStreamChunk> toSubagentChunks(AgentEvent event, String sourceKey,
                                                     Map<String, StreamState> stateBySource) {
        String subagentName = resolveSubagentName(sourceKey);
        boolean firstAppearance = !stateBySource.containsKey(sourceKey);
        StreamState state = stateBySource.computeIfAbsent(sourceKey, k -> new StreamState());

        List<ChatStreamChunk> chunks = new ArrayList<>();
        // 该子 Agent 首次产出事件：补一条 SUBAGENT_START，标出子 Agent 执行轨迹的起点。
        if (firstAppearance) {
            log.info("[chat] subagent stream started: path={} name={}", sourceKey, subagentName);
            chunks.add(new ChatStreamChunk(ChatNodeKind.SUBAGENT_START, subagentName, sourceKey, subagentName));
        }

        if (event instanceof ThinkingBlockDeltaEvent thinking && StringUtils.hasText(thinking.getDelta())) {
            chunks.add(new ChatStreamChunk(ChatNodeKind.THINKING, thinking.getDelta(), sourceKey, subagentName));
        } else if (event instanceof TextBlockDeltaEvent text && StringUtils.hasText(text.getDelta())) {
            state.appendAnswer(text.getDelta());
            chunks.add(new ChatStreamChunk(ChatNodeKind.ANSWER, text.getDelta(), sourceKey, subagentName));
        } else if (event instanceof ToolCallStartEvent toolCall && StringUtils.hasText(toolCall.getToolCallName())) {
            chunks.add(new ChatStreamChunk(ChatNodeKind.TOOL_BUILTIN,
                describeToolCall(toolCall.getToolCallName()), sourceKey, subagentName));
        } else if (event instanceof ToolResultTextDeltaEvent toolResultDelta) {
            state.appendToolResult(toolResultDelta.getToolCallId(), toolResultDelta.getDelta());
        } else if (event instanceof ToolResultEndEvent toolResultEnd) {
            chunks.add(new ChatStreamChunk(ChatNodeKind.TOOL_RESULT,
                describeToolResult(toolResultEnd.getToolCallName(), state.takeToolResult(toolResultEnd.getToolCallId())),
                sourceKey, subagentName));
        } else if (event instanceof AgentEndEvent) {
            String answer = state.takeAnswer();
            if (StringUtils.hasText(answer)) {
                chunks.add(new ChatStreamChunk(ChatNodeKind.SUBAGENT_RESULT, answer, sourceKey, subagentName));
            }
            log.info("[chat] subagent stream finished: path={} name={}", sourceKey, subagentName);
        }
        // 其它事件类型（AGENT_START、block start/end 等）：不进展示轨迹，只保留可能已补的 SUBAGENT_START。
        return chunks;
    }

    /**
     * 子 Agent 展示名：新路径上框架只给一个 path 字符串（{@code 父会话id/子agentId}），取末段即子
     * agentId。旧路径的 {@code EventSource#getAgentName()}（更友好的展示名）在细粒度事件上不存在，
     * 这是本次迁移已知的展示降级——path 无分隔符时整串回退。
     */
    private String resolveSubagentName(String sourceKey) {
        int separator = sourceKey.lastIndexOf(SOURCE_PATH_SEPARATOR);
        String lastSegment = separator < 0 ? sourceKey : sourceKey.substring(separator + SOURCE_PATH_SEPARATOR.length());
        return StringUtils.hasText(lastSegment) ? lastSegment : sourceKey;
    }

    private ChatNodeKind classifyToolSource(ToolSourceInfo toolSource, String toolName) {
        if (toolSource.isSkillTool(toolName)) {
            return ChatNodeKind.TOOL_SKILL;
        }
        if (toolSource.isMcpTool(toolName)) {
            return ChatNodeKind.TOOL_MCP;
        }
        return ChatNodeKind.TOOL_BUILTIN;
    }

    /** 工具调用提示文案。 */
    private String describeToolCall(String toolName) {
        return "「" + toolName + "」";
    }

    /**
     * 工具返回结果的展示文案。格式与改造前逐字节一致——{@code TestReportParser} 靠
     * {@code 工具「execute」返回：} 这个前缀识别沙箱命令执行结果，改格式会静默打断 VibeCoding 的
     * test_report 产出。
     */
    private String describeToolResult(String toolName, String output) {
        return "工具「" + toolName + "」返回：" + (StringUtils.hasText(output) ? output : "(无文本结果)");
    }

    /**
     * {@code streamEvents(List, RuntimeContext)} 只直接声明在 {@link ReActAgent}/{@link HarnessAgent}
     * 各自的类上（2.0.0 未收敛进共享的 {@code Agent} 接口），故按运行时具体类型分派——
     * {@link AdminAgentInstanceFactory#build} 只会产出这两种之一。
     */
    private Flux<AgentEvent> streamEvents(Agent agent, List<Msg> msgs, RuntimeContext ctx) {
        if (agent instanceof ReActAgent reActAgent) {
            return reActAgent.streamEvents(msgs, ctx);
        }
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.streamEvents(msgs, ctx);
        }
        throw new IllegalStateException("unsupported agent runtime type: " + agent.getClass());
    }

    private Msg toUserMsg(String userText) {
        return Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(TextBlock.builder().text(userText).build())
            .build();
    }

    /**
     * 单一事件来源（父 Agent 或某个子 Agent）在本轮对话内的跨事件拼装状态。父子交错推流时各持一份，
     * 互不污染（见 {@link #chatStream} 里 {@code stateBySource} 的说明）。
     *
     * <p>细粒度事件把"增量"和"汇总"拆成了不同事件类型，改造前那四份去重状态（最近提示过的工具名 /
     * 上一段 reasoning 与 answer 全量文本 / 本轮迭代是否已标过"调用大模型"）全部不再需要，这里只剩
     * 三样真正跨事件的拼装缓冲。</p>
     */
    private static final class StreamState {
        /** 本股流是否已经流出过正文增量——决定要不要用 {@code AGENT_RESULT} 补非流式 provider 的全文。 */
        private final AtomicBoolean answerStreamed = new AtomicBoolean(false);
        /** 子 Agent 正文累积，{@code AGENT_END} 时取走拼成 SUBAGENT_RESULT（父 Agent 不用）。 */
        private final StringBuilder answer = new StringBuilder();
        /** 工具结果文本累积：toolCallId → 已到达的分片；{@code TOOL_RESULT_END} 时取走。 */
        private final Map<String, StringBuilder> toolResults = new ConcurrentHashMap<>();

        private void appendAnswer(String delta) {
            synchronized (answer) {
                answer.append(delta);
            }
        }

        private String takeAnswer() {
            synchronized (answer) {
                String text = answer.toString();
                answer.setLength(0);
                return text;
            }
        }

        private void appendToolResult(String toolCallId, String delta) {
            if (toolCallId == null || !StringUtils.hasText(delta)) {
                return;
            }
            toolResults.computeIfAbsent(toolCallId, k -> new StringBuilder()).append(delta);
        }

        /** 取走并清空某次工具调用的累积文本；没有任何分片（如无输出的工具）时返回空串。 */
        private String takeToolResult(String toolCallId) {
            StringBuilder buffer = toolCallId == null ? null : toolResults.remove(toolCallId);
            return buffer == null ? "" : buffer.toString();
        }
    }
}
