package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.event.TextBlockDeltaEvent;

/**
 * 改写正文的出站中间件处理「子智能体转发进来的正文」的两条约定。
 *
 * <p>Harness 子智能体经 {@code agent_spawn} 同步执行时，它的细粒度事件只转发进主智能体的事件流、
 * 带 {@code AgentEvent#getSource()}，于是会流经主智能体的 {@code onAgent} 链。消费方（H5 丢弃、
 * admin 画成子智能体卡片）全凭这个标记分辨，所以：</p>
 * <ol>
 *   <li><b>改写出的正文沿用原来源</b>：三参构造的 {@link TextBlockDeltaEvent} 不带 source，
 *       改写一次，子智能体的正文就成了主智能体对用户的答复；</li>
 *   <li><b>缓冲按来源分开</b>：框架给每个文本块的 blockId 都是常量 {@code "text"}，只按 blockId 分，
 *       子智能体中途失败、没发块结束时，主智能体下一块会续进子智能体那份缓冲、顶着它的来源放出去。</li>
 * </ol>
 *
 * @author owlzhangfq@gmail.com
 */
final class ForwardedText {

    private ForwardedText() {
    }

    /** 文本块在本次调用里的缓冲键：主智能体自己的块沿用 blockId，转发进来的块再按来源区分。 */
    static String blockKey(String source, String blockId) {
        return source == null ? blockId : source + "|" + blockId;
    }

    /** 改写出的正文增量，来源与原事件一致。 */
    static TextBlockDeltaEvent delta(String source, String replyId, String blockId, String text) {
        TextBlockDeltaEvent delta = new TextBlockDeltaEvent(replyId, blockId, text);
        delta.withSource(source);
        return delta;
    }
}
