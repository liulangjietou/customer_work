package com.richard.fyoung.customerwork.core.model.tiered;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import java.util.List;

/**
 * 档位判定策略：这次请求该用便宜模型还是主模型。
 *
 * <h3>为什么只看轮数与长度</h3>
 *
 * <p>判定发生在 {@code Model} 层，那里能拿到的只有 {@code messages} 与 {@code tools}——
 * 看不到 sessionId、看不到意图分类结果。而意图分类住在 {@code MultiAgentOrchestrator} 里，
 * 那个类本身持有 {@code Model}，反向依赖会直接成环。</p>
 *
 * <p>所以这里用的是能从入参直接读出、且与"难度"强相关的两个信号：</p>
 * <ul>
 *   <li><b>对话轮数</b>：多轮意味着上下文在累积，往往是复杂问题在推进（投诉、多次澄清）；</li>
 *   <li><b>用户文本长度</b>：长问题信息量大、约束多，便宜模型容易漏掉其中一半要求。</li>
 * </ul>
 *
 * <p><b>这是粗判，而且刻意保守</b>：只有"单轮且简短"才降级，其余一律走标准档。
 * 判错的代价因此是"没省到钱"而不是"答得差"——这个方向的不对称是故意的。
 * 要更精细的判定（如接一个真正的难度分类器），实现自己的 Bean 覆盖即可。</p>
 * @author owlzhangfq@gmail.com
 */
public class ModelTierPolicy {

    private final int maxMessagesForEconomy;
    private final int maxUserTextLengthForEconomy;

    public ModelTierPolicy(int maxMessagesForEconomy, int maxUserTextLengthForEconomy) {
        this.maxMessagesForEconomy = maxMessagesForEconomy;
        this.maxUserTextLengthForEconomy = maxUserTextLengthForEconomy;
    }

    /**
     * 判定档位。
     *
     * @param messages 本次请求的完整消息列表（含系统提示与历史）
     * @return 命中全部"简单"特征才返回 {@link ModelTier#ECONOMY}，否则 {@link ModelTier#STANDARD}
     */
    public ModelTier decide(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return ModelTier.STANDARD;
        }
        if (messages.size() > maxMessagesForEconomy) {
            return ModelTier.STANDARD;
        }
        String userText = lastUserText(messages);
        if (userText == null || userText.length() > maxUserTextLengthForEconomy) {
            return ModelTier.STANDARD;
        }
        return ModelTier.ECONOMY;
    }

    /** 取最后一条用户消息的正文；没有用户消息时返回 null（判为复杂，走标准档）。 */
    private String lastUserText(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                String text = msg.getTextContent();
                return text == null ? null : text.trim();
            }
        }
        return null;
    }
}
