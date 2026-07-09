package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
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

        // 除了 REASONING/AGENT_RESULT，还订阅 TOOL_RESULT——不然模型调用 MCP 工具等待结果的这段时间
        // （可能好几秒）前端界面上什么都不会动，看起来像"卡住了"。includeActingChunk 让耗时较长的
        // 工具也能流式吐中间结果，而不是等它彻底跑完才一次性出现。
        StreamOptions options = StreamOptions.builder()
            .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
            .incremental(true)
            .includeReasoningChunk(true)
            .includeActingChunk(true)
            .build();

        // 本轮对话内"最近一次已提示过的工具名"，用来给 ToolUseBlock 增量片段去重（见 toChunks）；
        // "是否已经通过 REASONING 增量流出过正文"，用来给 AGENT_RESULT 兜底去重（见 toChunks）。
        // "上一个 reasoning 和 answer 增量片段"，用来对相邻重复的流式片段去重（某些场景下框架会
        // 把同一句话连续发两次，导致前端显示重复内容）。
        // 都是每次 chatStream 调用各建一份，不跨请求共享。
        AtomicReference<String> lastAnnouncedTool = new AtomicReference<>();
        AtomicReference<String> lastReasoningText = new AtomicReference<>();
        AtomicReference<String> lastAnswerText = new AtomicReference<>();
        AtomicBoolean answerStreamed = new AtomicBoolean(false);

        return streamEvents(agent, List.of(toUserMsg(userText)), options, ctx)
            .concatMap(event -> Flux.fromIterable(toChunks(event, lastAnnouncedTool, lastReasoningText, lastAnswerText, answerStreamed)))
            .onErrorResume(e -> {
                log.error("[workspace] chat stream failed, code={}, agentCode={}", "WORKSPACE_CHAT_ERROR", agentCode, e);
                return Flux.just(new ChatStreamChunk(false, FALLBACK_REPLY));
            })
            .doOnComplete(() -> historyCache.evict(agentCode, sessionId));
    }

    /**
     * 从一个事件拆出 0~N 个展示片段。
     *
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
     */
    private List<ChatStreamChunk> toChunks(Event event, AtomicReference<String> lastAnnouncedTool,
                                             AtomicReference<String> lastReasoningText,
                                             AtomicReference<String> lastAnswerText,
                                             AtomicBoolean answerStreamed) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return List.of();
        }

        if (event.getType() == EventType.TOOL_RESULT) {
            lastAnnouncedTool.set(null);
            return msg.getContentBlocks(ToolResultBlock.class).stream()
                .map(block -> new ChatStreamChunk(true, describeToolResult(block)))
                .collect(Collectors.toList());
        }

        if (event.getType() == EventType.AGENT_RESULT) {
            if (answerStreamed.get()) {
                return List.of();
            }
            String text = msg.getTextContent();
            return StringUtils.hasText(text) ? List.of(new ChatStreamChunk(false, text)) : List.of();
        }

        // REASONING：ThinkingBlock 是真正的思考过程，TextBlock 是正在增量生成的可见回答正文，
        // 两者可能同时出现在同一条消息里，都要各自送出去，不能只留一个。
        List<ChatStreamChunk> chunks = new ArrayList<>();
        for (ThinkingBlock block : msg.getContentBlocks(ThinkingBlock.class)) {
            String thinking = block.getThinking();
            if (StringUtils.hasText(thinking) && !thinking.equals(lastReasoningText.getAndSet(thinking))) {
                chunks.add(new ChatStreamChunk(true, thinking));
            }
        }
        String text = msg.getTextContent();
        if (StringUtils.hasText(text) && !text.equals(lastAnswerText.getAndSet(text))) {
            answerStreamed.set(true);
            chunks.add(new ChatStreamChunk(false, text));
        }
        if (chunks.isEmpty()) {
            // 既没有思考内容也没有正文，大概率是模型决定调用工具（ToolUseBlock）
            msg.getContentBlocks(ToolUseBlock.class).stream()
                .map(ToolUseBlock::getName)
                .filter(name -> StringUtils.hasText(name) && !name.startsWith("__"))
                .filter(name -> !name.equals(lastAnnouncedTool.getAndSet(name)))
                .forEach(name -> chunks.add(new ChatStreamChunk(true, "正在调用工具「" + name + "」...")));
        }
        return chunks;
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
