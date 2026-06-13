package com.example.customerwork.service;

import com.example.customerwork.agent.CustomerServiceAgentFactory;
import com.example.customerwork.dto.IntentResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 客服会话服务（对应深度解析一文②"会话恢复与上下文装配"与⑤"记忆写入 / 状态持久化"）。
 *
 * <p>核心职责：按 {@code sessionId} 维护 Agent 实例并做状态持久化，使同一会话的多轮对话
 * 共享上下文，且重启 / 跨请求可恢复。</p>
 *
 * <p><b>持久化机制</b>：进程内用 {@link ConcurrentHashMap} 缓存热 Agent（避免重复装配开销）；
 * 每轮对话结束后调用 {@code agent.saveTo(session, key)} 把记忆与状态写入 {@link Session}；
 * 首次为某会话创建 Agent 时调用 {@code agent.loadIfExists(session, key)} 恢复历史。
 * {@link Session} 的具体实现（内存 / Json / Redis / MySQL）由 {@code SessionConfig} 决定，
 * 本服务对存储无感知。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class CustomerServiceService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceService.class);

    private static final String FALLBACK_REPLY =
        "抱歉，系统繁忙，已为您记录问题，建议稍后再试或转人工坐席。";

    private final CustomerServiceAgentFactory agentFactory;
    private final Session session;

    /** 进程内热 Agent 缓存：sessionId -> Agent。冷数据由 {@link Session} 持久化恢复。 */
    private final ConcurrentHashMap<String, ReActAgent> sessionAgents = new ConcurrentHashMap<>();

    public CustomerServiceService(CustomerServiceAgentFactory agentFactory, Session session) {
        this.agentFactory = agentFactory;
        this.session = session;
    }

    /**
     * 处理一条用户消息，返回完整回复（非流式）。
     *
     * @param sessionId 会话 ID（来自接入层，关联用户与会话）
     * @param userText  用户输入文本
     * @return 助手回复文本（Mono，非阻塞）
     */
    public Mono<String> chat(String sessionId, String userText) {
        log.info("[会话 {}] 收到用户消息: {}", sessionId, userText);
        ReActAgent agent = resolveAgent(sessionId);

        return agent.call(toUserMsg(userText))
            .map(Msg::getTextContent)
            .doOnNext(reply -> log.info("[会话 {}] 助手回复: {}", sessionId, reply))
            .doOnSuccess(reply -> persist(sessionId, agent))
            .onErrorResume(e -> {
                log.error("[会话 {}] 处理失败", sessionId, e);
                return Mono.just(FALLBACK_REPLY);
            });
    }

    /**
     * 处理一条用户消息，流式返回增量文本（对应⑤ streamEvents 逐 token 渲染）。
     *
     * <p>订阅 Agent 的类型化事件流，提取推理（文本）增量片段下发。会话状态在流结束后持久化。</p>
     *
     * @return 增量文本片段流（Flux，非阻塞）
     */
    public Flux<String> chatStream(String sessionId, String userText) {
        log.info("[会话 {}] 收到用户消息(流式): {}", sessionId, userText);
        ReActAgent agent = resolveAgent(sessionId);

        StreamOptions options = StreamOptions.builder()
            .eventTypes(EventType.REASONING, EventType.AGENT_RESULT)
            .incremental(true)
            .includeReasoningChunk(true)
            .build();

        return agent.stream(toUserMsg(userText), options)
            .map(event -> event.getMessage() == null ? "" : event.getMessage().getTextContent())
            .filter(text -> text != null && !text.isEmpty())
            .doOnComplete(() -> persist(sessionId, agent))
            .onErrorResume(e -> {
                log.error("[会话 {}] 流式处理失败", sessionId, e);
                return Flux.just(FALLBACK_REPLY);
            });
    }

    /**
     * 结构化意图识别（对应深度解析 3.3"结构化输出"）。
     *
     * <p>用 ReActAgent 的结构化输出能力，让模型严格按 {@link IntentResult} 的 Schema 返回，
     * 业务侧直接拿到强类型对象，免去"二次解析 + 格式校验"。</p>
     *
     * @return 结构化意图；解析失败时返回一个标注为 other 的兜底结果
     */
    public Mono<IntentResult> classifyIntent(String sessionId, String userText) {
        log.info("[会话 {}] 结构化意图识别: {}", sessionId, userText);
        ReActAgent agent = resolveAgent(sessionId);

        Msg prompt = toUserMsg("请判断以下用户消息的意图，并按要求结构化输出：" + userText);
        return agent.call(prompt, IntentResult.class)
            .map(msg -> msg.getStructuredData(IntentResult.class))
            .doOnSuccess(result -> persist(sessionId, agent))
            .onErrorResume(e -> {
                log.error("[会话 {}] 意图识别失败", sessionId, e);
                return Mono.just(new IntentResult("other", "", false, "意图识别失败，转人工兜底"));
            });
    }

    /**
     * 安全中断当前会话正在执行的 Agent（对应深度解析 3.1"安全中断 / 实时打断"）。
     *
     * <p>用于任务跑偏、超时或用户主动叫停的场景。框架会保留上下文与工具状态，后续可无缝恢复。</p>
     *
     * @return 是否存在可中断的活跃会话
     */
    public boolean interrupt(String sessionId) {
        ReActAgent agent = sessionAgents.get(sessionId);
        if (agent == null) {
            log.info("[会话 {}] 无活跃 Agent，忽略中断", sessionId);
            return false;
        }
        agent.interrupt();
        log.warn("[会话 {}] 已发出安全中断", sessionId);
        return true;
    }

    /** 主动结束并清理会话：移除热缓存并删除持久化状态。 */
    public void endSession(String sessionId) {
        sessionAgents.remove(sessionId);
        try {
            session.delete(SimpleSessionKey.of(sessionId));
        } catch (Exception e) {
            log.warn("[会话 {}] 删除持久化状态失败（已忽略）: {}", sessionId, e.getMessage());
        }
        log.info("[会话 {}] 已结束并清理", sessionId);
    }

    /** 获取热 Agent；首次创建时尝试从持久化存储恢复历史会话。 */
    private ReActAgent resolveAgent(String sessionId) {
        return sessionAgents.computeIfAbsent(sessionId, id -> {
            ReActAgent agent = agentFactory.createAgent(id);
            try {
                boolean restored = agent.loadIfExists(session, SimpleSessionKey.of(id));
                if (restored) {
                    log.info("[会话 {}] 已从持久化存储恢复历史上下文", id);
                }
            } catch (Exception e) {
                log.warn("[会话 {}] 恢复历史上下文失败（按新会话处理）: {}", id, e.getMessage());
            }
            return agent;
        });
    }

    /** 持久化会话状态。落盘可能涉及 IO，放到 boundedElastic 调度，避免阻塞响应式线程。 */
    private void persist(String sessionId, ReActAgent agent) {
        Schedulers.boundedElastic().schedule(() -> {
            try {
                agent.saveTo(session, SimpleSessionKey.of(sessionId));
            } catch (Exception e) {
                log.warn("[会话 {}] 持久化状态失败（已忽略）: {}", sessionId, e.getMessage());
            }
        });
    }

    private Msg toUserMsg(String userText) {
        return Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(TextBlock.builder().text(userText).build())
            .build();
    }
}
