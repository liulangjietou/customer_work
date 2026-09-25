package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.capability.handoff.HandoffWatch;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;

import java.util.EnumSet;
import java.util.Set;

/**
 * 一次 Agent 调用怎么收的尾——决定这份答复能不能写进语义缓存。流式与非流式两条对话路径共用这一处判定。
 *
 * <p><b>为什么只收正常收尾的答复</b>：命中缓存时 Agent 根本不会运行——不转人工、不发审批、不续上被打断的回答，
 * 只把上次的文本原样再放一遍。轮次用尽（{@code MAX_ITERATIONS}）的收尾带着「已为您转接人工客服」，
 * 等待审批（{@code PERMISSION_ASKING} / {@code TOOL_SUSPENDED}）的答复停在「请稍候确认」，
 * 被打断（{@code INTERRUPTED} / {@code *_STOP_REQUESTED}）的只有半句。缓存下来，下一次问到同类问题时
 * 用户收到的是一句承诺，而那句承诺背后什么都不会发生。用户端 H5 同样只把 {@code MODEL_STOP} /
 * {@code STRUCTURED_OUTPUT} 显示为「答复已生成」。</p>
 *
 * <p><b>结束原因取自本轮最后一个最终结果</b>，与框架 {@code call()} 取最终结果的口径一致
 * （{@code takeLast(1)}）。框架正常收尾时<b>不写</b>这个元数据键，{@link Msg#getGenerateReason()}
 * 对缺失的键返回 {@code MODEL_STOP}——所以改写最终结果的中间件必须保留原消息的元数据
 * （用 {@link Msg#withContent}），重建消息会把用尽、审批这类收尾抹成正常结束。</p>
 *
 * <p>一直没收到最终结果、或走了兜底（超时 / 调用失败）的，无从确认怎么收的尾，一律不缓存：
 * 少缓存一条只是下次多问一遍模型，缓存错一条则每个问到同类问题的人都会收到它。
 * 降级标记一经置位不再复位，兜底之后迟到的最终结果不能把它翻回来。</p>
 *
 * <p><b>本轮转过人工的也不收</b>，哪怕正常收尾：转人工的五个来源里，只有循环守卫以 {@code MAX_ITERATIONS}
 * 收尾，答复闸门、Jev 升级、Jev 退款风险与转人工工具都是模型正常说完（{@code MODEL_STOP}），
 * 答复里的「已为您转接人工客服」光看结束原因挡不住。本轮转没转过，由 Agent 运行期间打开的
 * {@link HandoffWatch} 回答——它记在建单入口上，覆盖所有来源，流式与非流式看到的是同一份事实。
 * 没挂上观察窗口（Agent 还没开跑就失败了）时无从确认，同样不收。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class ReplyFinish {

    /** 能原样复用的结束原因：模型自己把话说完，或交出了结构化结果。 */
    private static final Set<GenerateReason> REPLAYABLE =
        EnumSet.of(GenerateReason.MODEL_STOP, GenerateReason.STRUCTURED_OUTPUT);

    /** 本轮最后一个最终结果的结束原因；还没收到最终结果时为 null。 */
    private volatile GenerateReason reason;
    private volatile boolean degraded;
    /** Agent 运行期间的转人工观察窗口；Agent 开跑时挂上，之前为 null。 */
    private volatile HandoffWatch handoffs;

    /** 记下最终结果的结束原因；一轮里收到多个时以最后一个为准。 */
    void record(Msg result) {
        reason = result == null ? null : result.getGenerateReason();
    }

    /** Agent 开跑时挂上本轮的转人工观察窗口，原样返回以便调用方负责关闭。 */
    HandoffWatch watchHandoffs(HandoffWatch watch) {
        handoffs = watch;
        return watch;
    }

    /** 走了兜底：答复里是兜底文案（或半截回答接兜底文案），不是本轮真正的答复。 */
    void markDegraded() {
        degraded = true;
    }

    /** 这份答复能否在不运行 Agent 的情况下原样交给下一个人。 */
    boolean replayable() {
        return !degraded && reason != null && REPLAYABLE.contains(reason)
            && handoffs != null && !handoffs.requested();
    }

    @Override
    public String toString() {
        return "finishReason=" + reason + ", degraded=" + degraded + ", "
            + (handoffs == null ? "handoffWatch=none" : handoffs);
    }
}
