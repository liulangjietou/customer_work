package com.richard.fyoung.customerwork.sensitiveword;

import java.util.function.Consumer;

/**
 * 流式输出的敏感词滑动缓冲过滤器：**每个输出流一个实例**，喂增量、吐可放行的文本。
 *
 * <p><b>为什么需要它、为什么不在中间件里做</b>：框架的 {@code AgentBase.stream(msgs, options)}
 * （接入层在用的旧 API）内部注册 {@code StreamingHook} 直接从模型输出捕获文本推给 sink，
 * <b>这条流绕开了中间件链</b>；中间件产出的 {@code AgentEvent} 是另一条流，两者只在最后的
 * {@code AGENT_RESULT} 汇合，而那条事件恰恰被接入层丢弃（避免与增量重复渲染）。
 * 于是"只改中间件"的出站过滤在流式链路上完全落空——命中日志记着"已打码"，用户屏幕上却是原文。
 * 把缓冲逻辑抽到这里，让 admin 与 8080 两侧的流式出口各挂一道，是当前唯一能同时修好两侧、
 * 又不必重写各自几百行事件处理的做法。</p>
 *
 * <p><b>滑动缓冲</b>：一个词会被拆进相邻增量（"阿根廷" 可能来自三次推送），逐片匹配必漏。
 * 每片与缓冲区拼接后整体过滤，只放行"确定不再是任何词前缀"的前半段，尾部留
 * {@link SensitiveWordFilter#streamRetainLength()} 个字符等下一片；{@link #flush()} 时吐出剩余，
 * 一个字都不吞。</p>
 *
 * <p><b>BLOCK 的固有限制</b>：命中时前面的片段已经发出去了，收不回。这里的处置是"立即停止后续
 * 输出 + 补一条安全话术"，把伤害面收敛到命中点之后。要一个字都不漏只能整段缓冲后再发，
 * 那就没有流式了——需要那种强度的场景应在接入层关闭流式，不该由本类替所有人做这个取舍。</p>
 *
 * <p><b>非线程安全</b>：状态是这一条流的私有上下文，务必每个流新建一个，绝不跨流复用。</p>
 * @author owlzhangfq@gmail.com
 */
public class SensitiveWordStreamGuard {

    private final SensitiveWordFilter filter;
    private final String blockedReply;
    /** 命中回调（可为 null）：中间件用它做审计与命中日志上报，接入层通常不需要。 */
    private final Consumer<SensitiveWordFilterResult> onHit;

    private final StringBuilder buffer = new StringBuilder();
    private boolean blocked;

    /** 本流是否命中过 MASK（供接入层决定是否上报命中，避免每片都报一次）。 */
    private boolean maskReported;

    public SensitiveWordStreamGuard(SensitiveWordFilter filter, String blockedReply) {
        this(filter, blockedReply, null);
    }

    public SensitiveWordStreamGuard(SensitiveWordFilter filter, String blockedReply,
                                    Consumer<SensitiveWordFilterResult> onHit) {
        this.filter = filter;
        this.blockedReply = blockedReply;
        this.onHit = onHit;
    }

    /** 命中回调：回调自身异常绝不能打断输出流，故就地吞掉——上报是旁路，过滤才是主线。 */
    private void notifyHit(SensitiveWordFilterResult result) {
        if (onHit == null) {
            return;
        }
        try {
            onHit.accept(result);
        } catch (RuntimeException ignored) {
            // 调用方自己会记日志；这里静默是为了保证一次上报失败不影响后续片段的过滤与放行
        }
    }

    /**
     * 喂入一段增量，返回本次应当放行的文本。
     *
     * @return 可放行文本；空串表示本片全部被缓冲或已被拦下，接入层跳过发送即可
     */
    public String accept(String delta) {
        if (blocked) {
            // 已拦下：后续增量一律吞掉，避免"拦了一半还在继续吐"
            return "";
        }
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        buffer.append(delta);
        SensitiveWordFilterResult result = filter.check(buffer.toString());
        if (result.decision() == SensitiveWordAction.BLOCK) {
            blocked = true;
            buffer.setLength(0);
            notifyHit(result);
            return blockedReply;
        }
        if (result.decision() == SensitiveWordAction.MASK && !maskReported) {
            // 只在本流首次打码时上报：缓冲区是累积的，逐片上报会把同一处命中报很多次
            maskReported = true;
            notifyHit(result);
        }
        String masked = result.maskedText();
        int retain = Math.min(filter.streamRetainLength(), masked.length());
        String emit = masked.substring(0, masked.length() - retain);
        buffer.setLength(0);
        buffer.append(masked, masked.length() - retain, masked.length());
        return emit;
    }

    /**
     * 流结束：吐出缓冲区里留的尾巴（过滤后）。不调用就会吞掉正文末尾几个字。
     *
     * @return 剩余应放行文本；空串表示无剩余或已被拦下
     */
    public String flush() {
        if (blocked || buffer.length() == 0) {
            buffer.setLength(0);
            return "";
        }
        SensitiveWordFilterResult result = filter.check(buffer.toString());
        buffer.setLength(0);
        if (result.decision() == SensitiveWordAction.BLOCK) {
            blocked = true;
            notifyHit(result);
            return blockedReply;
        }
        if (result.decision() == SensitiveWordAction.MASK && !maskReported) {
            maskReported = true;
            notifyHit(result);
        }
        return result.maskedText();
    }

    /** 本流是否已被拦下（接入层可据此提前结束流）。 */
    public boolean isBlocked() {
        return blocked;
    }

    /** 本流是否发生过打码（供接入层上报命中，避免逐片重复上报）。 */
    public boolean isMasked() {
        return maskReported;
    }
}
