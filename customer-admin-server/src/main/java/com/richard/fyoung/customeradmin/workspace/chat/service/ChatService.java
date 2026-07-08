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
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

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

        StreamOptions options = StreamOptions.builder()
            .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
            .incremental(true)
            .includeReasoningChunk(true)
            .build();

        return streamEvents(agent, List.of(toUserMsg(userText)), options, ctx)
            .map(event -> new ChatStreamChunk(event.getType() == EventType.REASONING,
                event.getMessage() == null ? "" : event.getMessage().getTextContent()))
            .filter(chunk -> chunk.text() != null && !chunk.text().isEmpty())
            .onErrorResume(e -> {
                log.error("[workspace] chat stream failed, code={}, agentCode={}", "WORKSPACE_CHAT_ERROR", agentCode, e);
                return Flux.just(new ChatStreamChunk(false, FALLBACK_REPLY));
            })
            .doOnComplete(() -> historyCache.evict(agentCode, sessionId));
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
