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
import com.richard.fyoung.customerwork.calllog.AgentCallMeta;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 工作区对话服务：从 {@link AgentInstanceCache} 取（或惰性构建）智能体实例，流式对话。
 *
 * <p>与 {@code CustomerServiceService#chatStream} 同一套"类型化事件流 -&gt; 提取增量文本"手法
 * （见 io.agentscope.core.agent.StreamOptions 用法），区别仅在于 Agent 实例来源：那边是启动期
 * 固定装配的单例，这里是按 agentCode 动态取的缓存实例，且底层可能是 ReActAgent 也可能是
 * HarnessAgent（{@link Agent} 接口统一了 {@code stream(...)} 签名，调用方无感知）。</p>
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
    /** 父 Agent 自身事件在 per-source 状态表里的 key（{@code EventSource == null}）。 */
    private static final String MAIN_AGENT_SOURCE_KEY = "";
    /** 子 Agent 的 {@code EventSource} 既无 path 也无 agentId 时的兜底 key，避免与父 Agent 的 "" 撞车。 */
    private static final String SUBAGENT_FALLBACK_KEY = "subagent";

    private final AgentInstanceCache agentInstanceCache;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final ChatHistoryCache historyCache;
    private final AgentMemorySyncService memorySyncService;
    private final ExecutionModeRegistry executionModeRegistry;
    private final PlanConfirmationService planConfirmationService;
    private final ChatAttachmentService chatAttachmentService;

    public ChatService(AgentInstanceCache agentInstanceCache, AdminAgentInstanceFactory agentInstanceFactory,
                        ChatHistoryCache historyCache, AgentMemorySyncService memorySyncService,
                        ExecutionModeRegistry executionModeRegistry,
                        PlanConfirmationService planConfirmationService,
                        ChatAttachmentService chatAttachmentService) {
        this.agentInstanceCache = agentInstanceCache;
        this.agentInstanceFactory = agentInstanceFactory;
        this.historyCache = historyCache;
        this.memorySyncService = memorySyncService;
        this.executionModeRegistry = executionModeRegistry;
        this.planConfirmationService = planConfirmationService;
        this.chatAttachmentService = chatAttachmentService;
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
     * <p>聚合方式：框架把 usage 挂在事件消息（{@link Msg#getUsage()}）上，同一条消息的增量事件
     * 会重复携带（后到覆盖先到），不同消息各算一次——按 messageId 去重后求和，两种语义都兼容。</p>
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

        // 除了 REASONING/AGENT_RESULT，还订阅 TOOL_RESULT——不然模型调用 MCP 工具等待结果的这段时间
        // （可能好几秒）前端界面上什么都不会动，看起来像"卡住了"。includeActingChunk 让耗时较长的
        // 工具也能流式吐中间结果，而不是等它彻底跑完才一次性出现。
        StreamOptions options = StreamOptions.builder()
            .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
            .incremental(true)
            .includeReasoningChunk(true) // 关键：让 REASONING 事件把思考内容也流出来
            .includeActingChunk(true)
            .build();

        // 增量去重状态（最近提示过的工具名 / 是否已通过 REASONING 流出正文 / 上一段 reasoning 与 answer
        // 全量文本 / 本轮 ReAct 迭代是否已标过"调用大模型"）都收拢进 {@link StreamState}，并按事件来源
        // （父 Agent 用 "" / 子 Agent 用其调用链 path）各存一份：harness spawn 出的子 Agent 事件会经
        // SubagentEventBus 与父 Agent 事件交错推到同一个 sink，若父子共用一份去重状态，累积型 provider
        // 的前缀判断会互相污染（父的全量文本被子的全量文本"顶掉"，反之亦然）。每次 chatStream 各建一份，
        // 不跨请求共享。
        Map<String, StreamState> stateBySource = new ConcurrentHashMap<>();

        // Flux.defer 包一层：像 HarnessAgent.stream(...) 在沙箱资源获取失败时（如 docker 容器创建
        // 超时）是同步抛异常，不是发出错误信号——不包 defer 的话，streamEvents(...) 这行方法调用本身
        // 就会直接向外抛，导致整个 chatStream(...) 方法体同步抛出，下面的 .onErrorResume(...) 根本
        // 没机会接管。届时 Spring MVC 的 SSE 响应式适配器可能已经提交了响应头，连接就会挂起不报错
        // 也不关闭，前端永远卡在"生成中..."。defer 把方法调用推迟到订阅时执行，任何同步异常都会被
        // Reactor 自动转成 Flux.error(...)，从而能被 onErrorResume 正常捕获、优雅降级成兜底话术。
        Map<String, ChatUsage> usageByMessage = new ConcurrentHashMap<>();
        Flux<ChatStreamChunk> body = Flux.defer(() -> streamEvents(agent, List.of(userMsg), options, ctx))
            .doOnNext(event -> collectUsage(usageByMessage, event))
            .concatMap(event -> Flux.fromIterable(toChunks(event, toolSource, stateBySource)));

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
            .doFinally(signal -> usageTotalObserver.accept(totalUsage(usageByMessage)));

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

    /** 收集事件消息上的模型用量：同一 messageId 后到覆盖先到（增量事件重复携带累计值的场景）。 */
    private void collectUsage(Map<String, ChatUsage> usageByMessage, Event event) {
        Msg msg = event.getMessage();
        if (msg != null && msg.getUsage() != null && msg.getId() != null) {
            usageByMessage.put(msg.getId(), msg.getUsage());
        }
    }

    /** 汇总本轮全部消息的用量；一条都没有时返回 null（区分"确实没有用量信息"与"用了 0 token"）。 */
    private ChatUsage totalUsage(Map<String, ChatUsage> usageByMessage) {
        if (usageByMessage.isEmpty()) {
            return null;
        }
        int inputTokens = 0;
        int outputTokens = 0;
        int cachedTokens = 0;
        double time = 0;
        for (ChatUsage usage : usageByMessage.values()) {
            inputTokens += usage.getInputTokens();
            outputTokens += usage.getOutputTokens();
            cachedTokens += usage.getCachedTokens();
            time += usage.getTime();
        }
        return new ChatUsage(inputTokens, outputTokens, cachedTokens, time);
    }

    /**
     * 从一个事件拆出 0~N 个展示片段。
     *
     *  提问： AgentScope 框架层为什么能产生 ThinkingBlock
     * 项目里用的是 AgentScope 2.0.0-RC4（在 customer-admin-server/pom.xml 里引入依赖）。框架内部实现大致是：
     * ReActAgent / HarnessAgent 在调用大模型时，会解析模型返回的 reasoning_content（或等效字段）。
     * 对于支持推理的模型（如 OpenAI o1/o3、DeepSeek-R1、QwQ 等），模型 API 返回里通常有独立的 reasoning 字段。
     * AgentScope 把这部分单独封装成 io.agentscope.core.message.ThinkingBlock，和普通 TextBlock 区分开。
     * 当调用 agent.stream(...) 并设置 includeReasoningChunk(true) 时，框架会通过 EventType.REASONING 事件把这些 ThinkingBlock 增量流出来。
     * <p><b>坑（本方法存在的核心原因）</b>：{@link EventType#AGENT_RESULT} 官方文档写明
     * "Streaming: Not applicable"——它是对话结束时一次性吐出的完整最终文本，不是增量。而真正
     * 会逐字增量流式生成的可见回答文本，其实是通过 {@link EventType#REASONING} 事件里的
     * {@link TextBlock} 内容送出来的（同一条消息 id 下会触发多次事件）；{@link ThinkingBlock}
     * 内容才是真正的"内部思考过程"。早期实现把 REASONING 事件的一切内容统统归进"思考过程"
     * 折叠区，导致真正的可见回答文本被错误地也塞进了折叠区、用户只能通过一次性到达的
     * {@code AGENT_RESULT} 看到最终答案——外在表现就是"思考过程能看到增量，但最终结果一次性蹦出来"。
     * 现在按内容块类型正确分流：{@link ThinkingBlock} → 思考过程；{@link TextBlock} → 正文
     * （增量追加）；{@code AGENT_RESULT} 只在本轮从没通过 REASONING 流出过正文时才当兜底用一次
     * （避免同一段最终答案先增量出现一遍、结束时又整段重复一遍）。</p>
     *
     * <p>TOOL_RESULT 消息装的是 {@link ToolResultBlock}（不在 {@code getTextContent()} 覆盖范围内），
     * 取工具名 + 输出文本拼成一行过程提示。</p>
     *
     * <p><b>坑</b>：{@code incremental(true)} 下框架按原始流式增量吐 {@link ToolUseBlock}，尚未做
     * 跨分片的聚合——同一个工具调用会被拆成好几个片段各触发一个事件，且早期片段的 {@code name} 常是
     * 框架内部占位符（{@code "__fragment__"}/{@code "__pending__"}/任何 {@code "__"} 前缀，真正做
     * 聚合的 {@code ToolCallsAccumulator} 是框架内部类，这层拿不到聚合后的结果）。用
     * {@code lastAnnouncedTool} 记录"最近一次已经提示过的工具名"，同名只提示一次；工具真正返回
     * （TOOL_RESULT）后清空，下次再调用（哪怕是同一个工具）会重新提示一次。</p>
     *
     * <p><b>节点化时间线</b>：{@code modelCallAnnounced} 标记"本轮 ReAct 迭代是否已经发过一条
     * {@link ChatNodeKind#MODEL_CALL}"——TOOL_RESULT 到达后重置为 false，因为工具返回后模型必然
     * 会被重新调用一轮做下一步推理/总结，这样"调用大模型"节点数就等于模型实际被调用的次数。
     * {@code toolSource} 用于把 {@code ToolUseBlock} 按来源分类成
     * {@link ChatNodeKind#TOOL_SKILL}/{@link ChatNodeKind#TOOL_MCP}/{@link ChatNodeKind#TOOL_BUILTIN}。</p>
     */
    private List<ChatStreamChunk> toChunks(Event event, ToolSourceInfo toolSource,
                                             Map<String, StreamState> stateBySource) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return List.of();
        }
        EventSource source = event.getSource();
        if (source == null) {
            // 父 Agent 自身事件：行为与改造前逐字节等价（走 "" 这一份状态），前端拿到的 chunk 依旧
            // source/subagentName 均为 null。
            StreamState state = stateBySource.computeIfAbsent(MAIN_AGENT_SOURCE_KEY, k -> new StreamState());
            return toMainChunks(event, msg, toolSource, state);
        }
        return toSubagentChunks(event, msg, source, stateBySource);
    }

    /**
     * 父 Agent 事件 → 展示片段（原 {@code toChunks} 逻辑原样迁入，只是去重状态从方法局部变量收拢进
     * per-source 的 {@link StreamState}）。REASONING 里 {@link ThinkingBlock} 是真正的思考过程，
     * {@link TextBlock} 是正在增量生成的可见回答正文，两者可能同时出现在同一条消息里，都要各自送出。
     * 本轮迭代第一次收到任何 REASONING 内容（思考/正文/工具调用）先补一条"调用大模型"节点。
     */
    private List<ChatStreamChunk> toMainChunks(Event event, Msg msg, ToolSourceInfo toolSource, StreamState state) {
        if (event.getType() == EventType.TOOL_RESULT) {
            state.lastAnnouncedTool.set(null);
            state.modelCallAnnounced.set(false);
            return msg.getContentBlocks(ToolResultBlock.class).stream()
                .map(block -> new ChatStreamChunk(ChatNodeKind.TOOL_RESULT, describeToolResult(block)))
                .collect(Collectors.toList());
        }

        if (event.getType() == EventType.AGENT_RESULT) {
            if (state.answerStreamed.get()) {
                return List.of();
            }
            String text = msg.getTextContent();
            return StringUtils.hasText(text) ? List.of(new ChatStreamChunk(ChatNodeKind.ANSWER, text)) : List.of();
        }

        List<ChatStreamChunk> chunks = new ArrayList<>();
        for (ThinkingBlock block : msg.getContentBlocks(ThinkingBlock.class)) {
            String delta = extractDelta(state.lastReasoningText, block.getThinking());
            if (StringUtils.hasText(delta)) {
                addModelCallIfNew(chunks, state, null, null);
                chunks.add(new ChatStreamChunk(ChatNodeKind.THINKING, delta));
            }
        }
        String answerDelta = extractDelta(state.lastAnswerText, msg.getTextContent());
        if (StringUtils.hasText(answerDelta)) {
            state.answerStreamed.set(true);
            addModelCallIfNew(chunks, state, null, null);
            chunks.add(new ChatStreamChunk(ChatNodeKind.ANSWER, answerDelta));
        }
        List<ChatStreamChunk> toolChunks = msg.getContentBlocks(ToolUseBlock.class).stream()
            .map(ToolUseBlock::getName)
            .filter(name -> StringUtils.hasText(name) && !name.startsWith("__"))
            .filter(name -> !name.equals(state.lastAnnouncedTool.getAndSet(name)))
            .map(name -> new ChatStreamChunk(classifyToolSource(toolSource, name), "「" + name + "」"))
            .collect(Collectors.toList());
        if (!toolChunks.isEmpty()) {
            addModelCallIfNew(chunks, state, null, null);
            chunks.addAll(toolChunks);
        }
        return chunks;
    }

    /**
     * 子 Agent 事件 → 展示片段。harness spawn 出的子 Agent 用 {@code StreamOptions.defaults()}
     * （全量事件类型）经 {@code SubagentEventBus} 直推父 sink，绕过父流的 eventTypes 过滤，因此这里
     * 可能收到父流本不会出现的类型，只认 REASONING/TOOL_RESULT/AGENT_RESULT，其余（HINT/SUMMARY 等）
     * 直接忽略容错。片段统一带 {@code source}（调用链 path）与 {@code subagentName}（展示名），前端据此
     * 归入独立卡片。
     *
     * <p>与父 Agent 的差异：① 该 source 首次出现时先补一条 {@link ChatNodeKind#SUBAGENT_START}；
     * ② {@code AGENT_RESULT} 走 {@link ChatNodeKind#SUBAGENT_RESULT}（子 Agent 的最终文本），绝不
     * 走父 Agent 的 ANSWER/answerStreamed 链路；③ 工具一律归 {@link ChatNodeKind#TOOL_BUILTIN}
     * （子 Agent 的工具不在父的 {@link ToolSourceInfo} 里，不为此额外查询）；④ 不补"调用大模型"节点，
     * 只复用需求约定的 THINKING/ANSWER/TOOL_BUILTIN/TOOL_RESULT。</p>
     */
    private List<ChatStreamChunk> toSubagentChunks(Event event, Msg msg, EventSource source,
                                                     Map<String, StreamState> stateBySource) {
        String sourceKey = resolveSourceKey(source);
        String subagentName = resolveSubagentName(source);
        boolean firstAppearance = !stateBySource.containsKey(sourceKey);
        StreamState state = stateBySource.computeIfAbsent(sourceKey, k -> new StreamState());

        List<ChatStreamChunk> chunks = new ArrayList<>();
        // 该子 Agent 首次产出事件：补一条 SUBAGENT_START，标出子 Agent 执行轨迹的起点。
        if (firstAppearance) {
            log.info("[chat] subagent stream started: path={} name={}", sourceKey, subagentName);
            chunks.add(new ChatStreamChunk(ChatNodeKind.SUBAGENT_START, subagentName, sourceKey, subagentName));
        }

        EventType type = event.getType();
        if (type == EventType.TOOL_RESULT) {
            state.lastAnnouncedTool.set(null);
            for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
                chunks.add(new ChatStreamChunk(ChatNodeKind.TOOL_RESULT, describeToolResult(block), sourceKey, subagentName));
            }
            return chunks;
        }
        if (type == EventType.AGENT_RESULT) {
            String text = msg.getTextContent();
            if (StringUtils.hasText(text)) {
                chunks.add(new ChatStreamChunk(ChatNodeKind.SUBAGENT_RESULT, text, sourceKey, subagentName));
                log.info("[chat] subagent stream finished: path={} name={}", sourceKey, subagentName);
            }
            return chunks;
        }
        if (type == EventType.REASONING) {
            for (ThinkingBlock block : msg.getContentBlocks(ThinkingBlock.class)) {
                String delta = extractDelta(state.lastReasoningText, block.getThinking());
                if (StringUtils.hasText(delta)) {
                    chunks.add(new ChatStreamChunk(ChatNodeKind.THINKING, delta, sourceKey, subagentName));
                }
            }
            String answerDelta = extractDelta(state.lastAnswerText, msg.getTextContent());
            if (StringUtils.hasText(answerDelta)) {
                chunks.add(new ChatStreamChunk(ChatNodeKind.ANSWER, answerDelta, sourceKey, subagentName));
            }
            msg.getContentBlocks(ToolUseBlock.class).stream()
                .map(ToolUseBlock::getName)
                .filter(name -> StringUtils.hasText(name) && !name.startsWith("__"))
                .filter(name -> !name.equals(state.lastAnnouncedTool.getAndSet(name)))
                .forEach(name -> chunks.add(new ChatStreamChunk(ChatNodeKind.TOOL_BUILTIN, "「" + name + "」", sourceKey, subagentName)));
            return chunks;
        }
        // 其它未知事件类型（HINT/SUMMARY/ALL 等）：直接忽略，只保留可能已补的 SUBAGENT_START。
        return chunks;
    }

    /** 子 Agent 的状态表 key：优先用调用链 path，其次 agentId，都空时用兜底 key（不会与父 Agent 的 "" 撞车）。 */
    private String resolveSourceKey(EventSource source) {
        if (StringUtils.hasText(source.getPath())) {
            return source.getPath();
        }
        if (StringUtils.hasText(source.getAgentId())) {
            return source.getAgentId();
        }
        return SUBAGENT_FALLBACK_KEY;
    }

    /** 子 Agent 展示名：优先 {@code getAgentName()}，为空回退 {@code getAgentId()}，再空回退 path。 */
    private String resolveSubagentName(EventSource source) {
        if (StringUtils.hasText(source.getAgentName())) {
            return source.getAgentName();
        }
        if (StringUtils.hasText(source.getAgentId())) {
            return source.getAgentId();
        }
        return source.getPath();
    }

    /**
     * 提取本次内容相对上次的净增量。框架/模型对"增量"的语义不完全一致——有的 provider 每次给的是
     * 真正独立的分片（delta），有的给的是"从头到现在"的累积全量文本（实测复现于某些 DeepSeek 风格
     * 推理模型的 reasoning_content）；不区分对待、原样转发再让前端 {@code +=} 拼接，遇到累积型
     * provider 就会把重叠部分重复拼接一遍，界面上看到同一句话连续出现两次。用"新值是否以旧值为
     * 前缀"识别累积型场景，命中则只发净增量部分（旧值本身也涵盖了"两次内容完全相同、这次没有
     * 新增"的去重场景，此时截出空串直接不发）；不构成前缀关系（真正的独立分片，或模型侧发生了
     * 非累积性的改写）则原样发一遍，保底不丢内容。
     */
    private String extractDelta(AtomicReference<String> lastFull, String currentFull) {
        if (!StringUtils.hasText(currentFull)) {
            return "";
        }
        String previous = lastFull.getAndSet(currentFull);
        if (!StringUtils.hasText(previous) || !currentFull.startsWith(previous)) {
            return currentFull;
        }
        return currentFull.substring(previous.length());
    }

    /**
     * 本轮迭代还没标过"调用大模型"就补一条，只在真正产出内容（思考/正文/工具调用）的那一刻才补，
     * 避免空跑一轮也占一个节点。仅父 Agent 主流程调用（{@code source}/{@code subagentName} 传 null）。
     */
    private void addModelCallIfNew(List<ChatStreamChunk> chunks, StreamState state, String source, String subagentName) {
        if (!state.modelCallAnnounced.getAndSet(true)) {
            chunks.add(new ChatStreamChunk(ChatNodeKind.MODEL_CALL, "调用大模型", source, subagentName));
        }
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

    private String describeToolResult(ToolResultBlock block) {
        String output = block.getOutput().stream()
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining("\n"));
        return "工具「" + block.getName() + "」返回：" + (StringUtils.hasText(output) ? output : "(无文本结果)");
    }

    /**
     * {@code stream(List, StreamOptions, RuntimeContext)} 只直接声明在 {@link ReActAgent}/
     * {@link HarnessAgent} 各自的类上（2.0.0-RC4 未收敛进共享的 {@code Agent} 接口），
     * 故按运行时具体类型分派——{@link AdminAgentInstanceFactory#build} 只会产出这两种之一。
     */
    private Flux<Event> streamEvents(Agent agent, List<Msg> msgs, StreamOptions options, RuntimeContext ctx) {
        if (agent instanceof ReActAgent reActAgent) {
            return reActAgent.stream(msgs, options, ctx);
        }
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.stream(msgs, options, ctx);
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
     * 单一事件来源（父 Agent 或某个子 Agent）在本轮对话内的增量去重状态。父子交错推流时各持一份，
     * 互不污染（见 {@link #chatStream} 里 {@code stateBySource} 的说明）。字段语义同改造前的方法局部
     * 变量：最近提示过的工具名 / 是否已通过 REASONING 流出正文 / 上一段 reasoning 与 answer 全量文本
     * / 本轮迭代是否已标过"调用大模型"。沿用 {@code Atomic*} 类型是为了让 {@link #extractDelta}
     * 等既有 helper 的 {@code getAndSet} 语义原样复用，主流程行为与改造前逐字节等价。
     */
    private static final class StreamState {
        private final AtomicReference<String> lastAnnouncedTool = new AtomicReference<>();
        private final AtomicReference<String> lastReasoningText = new AtomicReference<>();
        private final AtomicReference<String> lastAnswerText = new AtomicReference<>();
        private final AtomicBoolean answerStreamed = new AtomicBoolean(false);
        private final AtomicBoolean modelCallAnnounced = new AtomicBoolean(false);
    }
}
