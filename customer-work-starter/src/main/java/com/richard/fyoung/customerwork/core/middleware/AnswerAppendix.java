package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 追加在本轮答复末尾的一段系统说明：循环守卫的去向说明、答复安全闸门的否定澄清。
 *
 * <h3>为什么要补两处</h3>
 * <p>用户端（WS / SSE，以及复用流式内核的同步接口）与 AG-UI 只看文本块增量，拿到过正文就不再看最终结果；
 * {@code call()}（IM 渠道、评测、多专家协作）只看最终结果。两类消费方各看各的事件，说明在两处各出现恰好一次。</p>
 *
 * <h3>流式那一处补在哪</h3>
 * <p>优先补进用户正在读的那个文本块、<b>赶在它结束之前</b>：结束之后再往同一块追加，AG-UI 适配器会照发
 * 已结束消息的内容（协议违规、不报错），外层按块缓冲的敏感词过滤与脱敏也等不到下一次放行；另起一块虽然合法，
 * 但 AG-UI 落库只取最后一条消息，历史里会只剩说明、丢了正文。赶不上块结束时才退而求其次，见 {@link #closingBlock}。</p>
 *
 * <h3>最终结果那一处只换内容</h3>
 * <p>接在最后一个文本块之后，消息身份、元数据（含框架标的结束原因）、用量与其余内容块原样保留。
 * 重建消息会把 {@code MAX_ITERATIONS} 抹成正常结束（H5 据此显示答复状态），也会丢掉挂起的工具调用与消息 id
 * （AG-UI 适配器据此生成审批中断）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class AnswerAppendix {

    private final String text;
    /** 单独成块时所用的块标识：与框架自己的块区分开，外层按块缓冲的中间件不会把它与别的块混在一起。 */
    private final String standaloneBlockId;

    AnswerAppendix(String text, String standaloneBlockId) {
        this.text = text;
        this.standaloneBlockId = standaloneBlockId;
    }

    /** 补进仍未结束的文本块；调用方负责让它排在该块的结束事件之前。 */
    TextBlockDeltaEvent into(String replyId, String blockId) {
        return new TextBlockDeltaEvent(replyId, blockId, text);
    }

    /**
     * 赶不上块结束时的补发。
     *
     * <p>模型中途失败时框架已经发出块开始与部分正文，却再也不会发块结束——说明补进这个悬空的块并替它结束，
     * 另起新块会让它永远不结束。没有悬空块才单独起一个开始 / 增量 / 结束齐全的块。</p>
     *
     * @param dangling 已开始、再也等不到结束的文本块；没有则为 null
     */
    List<AgentEvent> closingBlock(TextBlockStartEvent dangling) {
        if (dangling != null) {
            return List.of(into(dangling.getReplyId(), dangling.getBlockId()),
                new TextBlockEndEvent(dangling.getReplyId(), dangling.getBlockId()));
        }
        String replyId = UUID.randomUUID().toString().replace("-", "");
        return List.of(new TextBlockStartEvent(replyId, standaloneBlockId), into(replyId, standaloneBlockId),
            new TextBlockEndEvent(replyId, standaloneBlockId));
    }

    /** 接到最终结果末尾：只换内容，其余原样保留。 */
    Msg appendTo(Msg msg) {
        if (msg == null) {
            return Msg.builder().role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build()).build();
        }
        List<ContentBlock> content = new ArrayList<>(msg.getContent());
        for (int i = content.size() - 1; i >= 0; i--) {
            if (content.get(i) instanceof TextBlock last) {
                content.set(i, TextBlock.builder().text(last.getText() + text).build());
                return msg.withContent(content);
            }
        }
        content.add(TextBlock.builder().text(text).build());
        return msg.withContent(content);
    }
}
