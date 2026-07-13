package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.ToolSourceInfo;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

    private final AgentInstanceCache agentInstanceCache;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final ChatHistoryCache historyCache;

    public ChatService(AgentInstanceCache agentInstanceCache, AdminAgentInstanceFactory agentInstanceFactory,
                        ChatHistoryCache historyCache) {
        this.agentInstanceCache = agentInstanceCache;
        this.agentInstanceFactory = agentInstanceFactory;
        this.historyCache = historyCache;
    }

    /**
     * 流式对话，返回增量文本片段。智能体不存在/未启用时，{@link AgentInstanceCache#getOrBuild}
     * 同步抛出的 {@code BizException} 会在本方法返回 Flux 之前就传播给调用方（Controller 侧因此在
     * 任何 SSE 头下发之前就能拿到结构化错误响应，而不是半开的失败流）。
     */
    public Flux<ChatStreamChunk> chatStream(String agentCode, String sessionId, String userText) {
        Agent agent = agentInstanceCache.getOrBuild(agentCode);
        RuntimeContext ctx = agentInstanceFactory.contextFor(agentCode, sessionId);
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

        // 本轮对话内"最近一次已提示过的工具名"，用来给 ToolUseBlock 增量片段去重（见 toChunks）；
        // "是否已经通过 REASONING 增量流出过正文"，用来给 AGENT_RESULT 兜底去重（见 toChunks）。
        // "上一个 reasoning 和 answer 增量片段"，用来对相邻重复的流式片段去重（某些场景下框架会
        // 把同一句话连续发两次，导致前端显示重复内容）。
        // "本轮 ReAct 迭代是否已经标过一次调用大模型"，TOOL_RESULT 后重置——每次工具返回后模型都要
        // 被重新调用一次做下一轮推理/总结，借此让"调用大模型"节点真实反映实际调用次数。
        // 都是每次 chatStream 调用各建一份，不跨请求共享。
        AtomicReference<String> lastAnnouncedTool = new AtomicReference<>();
        AtomicReference<String> lastReasoningText = new AtomicReference<>();
        AtomicReference<String> lastAnswerText = new AtomicReference<>();
        AtomicBoolean answerStreamed = new AtomicBoolean(false);
        AtomicBoolean modelCallAnnounced = new AtomicBoolean(false);

        // Flux.defer 包一层：像 HarnessAgent.stream(...) 在沙箱资源获取失败时（如 docker 容器创建
        // 超时）是同步抛异常，不是发出错误信号——不包 defer 的话，streamEvents(...) 这行方法调用本身
        // 就会直接向外抛，导致整个 chatStream(...) 方法体同步抛出，下面的 .onErrorResume(...) 根本
        // 没机会接管。届时 Spring MVC 的 SSE 响应式适配器可能已经提交了响应头，连接就会挂起不报错
        // 也不关闭，前端永远卡在"生成中..."。defer 把方法调用推迟到订阅时执行，任何同步异常都会被
        // Reactor 自动转成 Flux.error(...)，从而能被 onErrorResume 正常捕获、优雅降级成兜底话术。
        Flux<ChatStreamChunk> body = Flux.defer(() -> streamEvents(agent, List.of(toUserMsg(userText)), options, ctx))
            .concatMap(event -> Flux.fromIterable(toChunks(event, toolSource, lastAnnouncedTool,
                lastReasoningText, lastAnswerText, answerStreamed, modelCallAnnounced)));

        return Flux.concat(Flux.just(new ChatStreamChunk(ChatNodeKind.THINKING_START, "开始思考")), body)
            .concatWith(Flux.defer(() -> Flux.just(new ChatStreamChunk(ChatNodeKind.THINKING_END, "结束思考"))))
            .onErrorResume(e -> {
                log.error("[workspace] chat stream failed, code={}, agentCode={}", "WORKSPACE_CHAT_ERROR", agentCode, e);
                return Flux.just(
                    new ChatStreamChunk(ChatNodeKind.THINKING_END, "结束思考"),
                    new ChatStreamChunk(ChatNodeKind.ANSWER, FALLBACK_REPLY));
            })
            .doOnComplete(() -> historyCache.evict(agentCode, sessionId));
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
                                             AtomicReference<String> lastAnnouncedTool,
                                             AtomicReference<String> lastReasoningText,
                                             AtomicReference<String> lastAnswerText,
                                             AtomicBoolean answerStreamed,
                                             AtomicBoolean modelCallAnnounced) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return List.of();
        }

        if (event.getType() == EventType.TOOL_RESULT) {
            lastAnnouncedTool.set(null);
            modelCallAnnounced.set(false);
            return msg.getContentBlocks(ToolResultBlock.class).stream()
                .map(block -> new ChatStreamChunk(ChatNodeKind.TOOL_RESULT, describeToolResult(block)))
                .collect(Collectors.toList());
        }

        if (event.getType() == EventType.AGENT_RESULT) {
            if (answerStreamed.get()) {
                return List.of();
            }
            String text = msg.getTextContent();
            return StringUtils.hasText(text) ? List.of(new ChatStreamChunk(ChatNodeKind.ANSWER, text)) : List.of();
        }

        // REASONING：ThinkingBlock 是真正的思考过程，TextBlock 是正在增量生成的可见回答正文，
        // 两者可能同时出现在同一条消息里，都要各自送出去，不能只留一个。本轮迭代第一次收到任何
        // REASONING 内容（思考/正文/工具调用），先补一条"调用大模型"节点，标出这轮模型调用的起点。
        List<ChatStreamChunk> chunks = new ArrayList<>();
        for (ThinkingBlock block : msg.getContentBlocks(ThinkingBlock.class)) {
            String delta = extractDelta(lastReasoningText, block.getThinking());
            if (StringUtils.hasText(delta)) {
                addModelCallIfNew(chunks, modelCallAnnounced);
                chunks.add(new ChatStreamChunk(ChatNodeKind.THINKING, delta));
            }
        }
        String answerDelta = extractDelta(lastAnswerText, msg.getTextContent());
        if (StringUtils.hasText(answerDelta)) {
            answerStreamed.set(true);
            addModelCallIfNew(chunks, modelCallAnnounced);
            chunks.add(new ChatStreamChunk(ChatNodeKind.ANSWER, answerDelta));
        }
        List<ChatStreamChunk> toolChunks = msg.getContentBlocks(ToolUseBlock.class).stream()
            .map(ToolUseBlock::getName)
            .filter(name -> StringUtils.hasText(name) && !name.startsWith("__"))
            .filter(name -> !name.equals(lastAnnouncedTool.getAndSet(name)))
            .map(name -> new ChatStreamChunk(classifyToolSource(toolSource, name), "「" + name + "」"))
            .collect(Collectors.toList());
        if (!toolChunks.isEmpty()) {
            addModelCallIfNew(chunks, modelCallAnnounced);
            chunks.addAll(toolChunks);
        }
        return chunks;
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

    /** 本轮迭代还没标过"调用大模型"就补一条，只在真正产出内容（思考/正文/工具调用）的那一刻才补，避免空跑一轮也占一个节点。 */
    private void addModelCallIfNew(List<ChatStreamChunk> chunks, AtomicBoolean modelCallAnnounced) {
        if (!modelCallAnnounced.getAndSet(true)) {
            chunks.add(new ChatStreamChunk(ChatNodeKind.MODEL_CALL, "调用大模型"));
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
}
