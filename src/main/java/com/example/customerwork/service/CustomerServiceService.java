package com.example.customerwork.service;

import com.example.customerwork.agent.CustomerServiceAgentFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 客服会话服务（对应流程图②"会话恢复与上下文装配"与⑤"记忆写入/状态持久化"）。
 *
 * <p>核心职责：按 {@code sessionId} 维护 Agent 实例，使同一会话的多轮对话共享上下文记忆。</p>
 *
 * <p><b>关于会话持久化（重要工程说明）</b>：本示例用进程内 {@link ConcurrentHashMap} 缓存 Agent，
 * 演示"同一 sessionId 多轮连续对话"。但要实现图中"容器重启/扩缩容不丢会话、跨进程恢复"，
 * 需要把会话状态外置——使用 AgentScope 的 Session/State 能力把状态序列化到共享存储
 * （Redis / DB），并在请求到来时按 sessionId 反序列化恢复。这部分依赖你的存储基础设施，
 * 属于生产工程化范畴，此处以方法注释与扩展点形式预留，不内置具体存储实现。</p>
 */
@Service
public class CustomerServiceService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceService.class);

    private final CustomerServiceAgentFactory agentFactory;

    /** 进程内会话缓存：sessionId -> Agent。生产中替换为基于 Session/State 的外置存储恢复。 */
    private final ConcurrentHashMap<String, ReActAgent> sessionAgents = new ConcurrentHashMap<>();

    public CustomerServiceService(CustomerServiceAgentFactory agentFactory) {
        this.agentFactory = agentFactory;
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

        // 按 sessionId 获取/恢复 Agent（多轮共享记忆）
        ReActAgent agent = sessionAgents.computeIfAbsent(sessionId, agentFactory::createAgent);

        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(TextBlock.builder().text(userText).build())
            .build();

        // 非阻塞调用：禁止在此处 .block()
        return agent.call(userMsg)
            .map(Msg::getTextContent)
            .doOnNext(reply -> log.info("[会话 {}] 助手回复: {}", sessionId, reply))
            .onErrorResume(e -> {
                log.error("[会话 {}] 处理失败", sessionId, e);
                return Mono.just("抱歉，系统繁忙，已为您记录问题，建议稍后再试或转人工。");
            });
    }

    /** 主动结束并清理会话（生产中同时落库归档，供⑥数据飞轮复盘）。 */
    public void endSession(String sessionId) {
        sessionAgents.remove(sessionId);
        log.info("[会话 {}] 已结束并清理", sessionId);
    }
}
