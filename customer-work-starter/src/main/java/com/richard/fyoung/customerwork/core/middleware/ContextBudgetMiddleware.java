package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.ContextProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
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

        // 按工具调用配对分组后再裁剪：切散 tool_use / tool_result 会让厂商直接返 400
        List<List<Msg>> groups = groupByToolCallPairing(budgeted);
        int tailBudget = maxMessages - keepEarliest;

        int headGroupEnd = 0;
        int headMessages = 0;
        while (headGroupEnd < groups.size() && headMessages < keepEarliest) {
            headMessages += groups.get(headGroupEnd).size();
            headGroupEnd++;
        }

        int tailGroupStart = groups.size();
        int tailMessages = 0;
        while (tailGroupStart > headGroupEnd && tailMessages < tailBudget) {
            tailGroupStart--;
            tailMessages += groups.get(tailGroupStart).size();
        }

        // 首尾两段已经吃满全部分组：中间没有可牺牲的内容，原样返回而不是切出一个残缺序列
        if (tailGroupStart <= headGroupEnd) {
            return messages;
        }

        List<Msg> kept = new ArrayList<>(pinned);
        for (int i = 0; i < headGroupEnd; i++) {
            kept.addAll(groups.get(i));
        }
        for (int i = tailGroupStart; i < groups.size(); i++) {
            kept.addAll(groups.get(i));
        }
        return kept;
    }

    /**
     * 把消息切成不可拆分的单元：含 {@code tool_use} 的消息与紧随其后的 {@code tool_result} 必须同去同留。
     *
     * <p><b>为什么必须分组</b>：此前 {@code trim} 是按位置直接 {@code subList} 首尾切，
     * 完全不看消息内容。一条 assistant 的 {@code tool_use} 与它的 {@code tool_result} 落在切口两侧时，
     * 送给模型的就是一个孤立的 {@code tool_result}——多数厂商对此直接返 400，
     * 也就是说这个中间件一旦按 javadoc 建议的那样在生产开启，长会话会从"上下文超限"
     * 变成"整轮请求失败"。</p>
     *
     * <p>分组规则用「紧随其后」而不是按 id 匹配：ReAct 循环里工具结果总是紧跟在发起调用的那条消息之后，
     * 而 system 与合成消息已经在 {@link #isPinned} 里被摘走、不会插进这段序列。</p>
     */
    private List<List<Msg>> groupByToolCallPairing(List<Msg> messages) {
        List<List<Msg>> groups = new ArrayList<>();
        List<Msg> openGroup = null;
        for (Msg msg : messages) {
            if (openGroup != null && containsBlock(msg, ToolResultBlock.class)) {
                openGroup.add(msg);
                continue;
            }
            List<Msg> group = new ArrayList<>();
            group.add(msg);
            groups.add(group);
            openGroup = containsBlock(msg, ToolUseBlock.class) ? group : null;
        }
        return groups;
    }

    /** 这条消息是否携带指定类型的内容块。 */
    private boolean containsBlock(Msg msg, Class<? extends ContentBlock> type) {
        if (msg == null || msg.getContent() == null) {
            return false;
        }
        for (ContentBlock block : msg.getContent()) {
            if (type.isInstance(block)) {
                return true;
            }
        }
        return false;
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

    /** 顺序契约见 {@link MiddlewareOrders}：必须最内层：所有注入完成后才算得准。 */
    @Override
    public int order() {
        return MiddlewareOrders.CONTEXT_BUDGET;
    }
}
