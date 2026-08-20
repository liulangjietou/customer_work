package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.ContextProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 上下文预算中间件：给每次模型调用的消息列表设一个确定性上限。
 *
 * <p><b>要解决的问题</b>：主对话链路的 {@code ReActAgent} 此前没有任何上下文裁剪。
 * 项目里唯一的收敛手段是 Harness 的 {@code CompactionConfig}，但那是
 * {@code io.agentscope.harness} 的能力，框架层面挂不到 {@code ReActAgent} 上，
 * 而 Harness 默认关闭、用户也不走那条路。与此同时长期记忆召回与 RAG 注入都默认开着——
 * <b>膨胀上下文的能力默认开，唯一收敛上下文的能力接在用户不走的路径上</b>。
 * 长会话叠加多轮工具结果会一路涨到模型报 context length exceeded 为止。</p>
 *
 * <p><b>为什么是确定性裁剪而不是模型压缩</b>：压缩要额外调一次模型，在"上下文已经很长"这个
 * 前提下那一次调用本身就是最贵的。确定性裁剪零成本、可预测、可测试；代价是丢掉的历史无法恢复，
 * 所以默认关闭，由使用方按业务对长程记忆的依赖程度决定。两者可以并存：
 * Harness 链路继续用压缩，主链路用本中间件兜底上限。</p>
 *
 * <p><b>丢中间、保两头</b>：最早的几条常含用户诉求的关键背景（"我买的是 A 商品"），
 * 最近的几条是当前话题，中间部分最适合牺牲。system 消息与合成的瞬态消息（RAG 召回、
 * 待办提醒）一律保留——它们本来就是每轮重建的，裁掉只会让模型失去当轮的参考资料。</p>
 *
 * <p><b>只改本次模型调用的输入，不动持久化状态</b>：{@code onReasoning} 拿到的
 * {@code ReasoningInput.messages()} 是框架为这一次调用临时组装的列表，不会写回 AgentState
 * （与 {@code KnowledgeInjectionMiddleware} 的瞬态注入同理）。因此裁剪不会真的删掉用户的历史，
 * 只是这一次不发给模型——下一轮若消息数回落，历史仍在。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ContextBudgetMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ContextBudgetMiddleware.class);

    private final boolean enabled;
    private final int maxMessages;
    private final int keepEarliest;

    @Autowired
    public ContextBudgetMiddleware(CustomerWorkProperties properties) {
        ContextProperties cfg = properties.getContext();
        this.enabled = cfg.isBudgetEnabled();
        this.maxMessages = Math.max(2, cfg.getBudgetMaxMessages());
        this.keepEarliest = Math.max(0, Math.min(cfg.getBudgetKeepEarliest(), this.maxMessages - 1));
    }

    /** 参数化构造：供已排除 starter 自动装配的模块显式装配，以及单测直接构造。 */
    public ContextBudgetMiddleware(boolean enabled, int maxMessages, int keepEarliest) {
        this.enabled = enabled;
        this.maxMessages = Math.max(2, maxMessages);
        this.keepEarliest = Math.max(0, Math.min(keepEarliest, this.maxMessages - 1));
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!enabled || input == null || input.messages() == null) {
            return next.apply(input);
        }
        List<Msg> original = input.messages();
        List<Msg> trimmed = trim(original);
        if (trimmed.size() == original.size()) {
            return next.apply(input);
        }
        log.info("context budget applied: {} -> {} messages (max={})",
            original.size(), trimmed.size(), maxMessages);
        return next.apply(new ReasoningInput(trimmed, input.tools(), input.options()));
    }

    /**
     * 按预算裁剪消息列表。
     *
     * <p>system 消息与合成消息（RAG 召回块、待办提醒）不计入预算也不被裁——
     * 它们每轮重建，裁掉等于让模型这一轮失去参考资料。</p>
     */
    List<Msg> trim(List<Msg> messages) {
        List<Msg> pinned = new ArrayList<>();
        List<Msg> budgeted = new ArrayList<>();
        for (Msg msg : messages) {
            if (isPinned(msg)) {
                pinned.add(msg);
            } else {
                budgeted.add(msg);
            }
        }
        if (budgeted.size() <= maxMessages) {
            return messages;
        }
        int tailCount = maxMessages - keepEarliest;
        List<Msg> kept = new ArrayList<>(pinned);
        kept.addAll(budgeted.subList(0, keepEarliest));
        kept.addAll(budgeted.subList(budgeted.size() - tailCount, budgeted.size()));
        return kept;
    }

    /** system 消息与框架合成的瞬态消息不参与预算。 */
    private boolean isPinned(Msg msg) {
        if (msg == null) {
            return false;
        }
        if (msg.getRole() == MsgRole.SYSTEM) {
            return true;
        }
        return msg.getMetadata() != null && Boolean.TRUE.equals(msg.getMetadata().get(Msg.METADATA_SYNTHETIC));
    }
}
