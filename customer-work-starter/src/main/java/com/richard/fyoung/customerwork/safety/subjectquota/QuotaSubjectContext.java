package com.richard.fyoung.customerwork.safety.subjectquota;

import java.util.function.Supplier;

/**
 * 当前请求的限流主体上下文（全链路唯一真源）。
 *
 * <p><b>为什么必须有这么一个上下文</b>：主体身份只在接入层拿得到（JWT / API Key / 远端 IP），
 * 而 token 的真实用量只在模型调用之后才知道，两处相隔整条业务链路，中间的方法签名里
 * 一个"用户"参数都没有。把身份挂在上下文上，是让"记账记到正确的人头上"这件事成立的前提——
 * 否则只能退回按 sessionId 猜用户，猜错就是把 A 的用量算到 B 头上。</p>
 *
 * <p>机制与 {@link com.richard.fyoung.customerwork.safety.tenant.TenantContext} 完全一致：
 * ThreadLocal 承载，跨线程边界由 {@link QuotaSubjectContextThreadLocalAccessor} + Reactor
 * 自动传播还原。用同一套而不是另造一套，是因为它们要在同样的线程切换点上活下来。</p>
 * @author owlzhangfq@gmail.com
 */
public final class QuotaSubjectContext {

    private static final ThreadLocal<QuotaSubject> CURRENT = new ThreadLocal<>();

    private QuotaSubjectContext() {
    }

    /** 写入当前主体；传 null 视为清理，避免半初始化状态被后续记账读到。 */
    public static void set(QuotaSubject subject) {
        if (subject == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(subject);
    }

    /** 读取当前主体，未设置返回 {@code null}（记账方按"无主体则不记"处理，不抛异常）。 */
    public static QuotaSubject get() {
        return CURRENT.get();
    }

    public static boolean isPresent() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 在指定主体下执行一段逻辑，结束后恢复原值（供 WS 等非 HTTP 入口使用）。 */
    public static <T> T callWith(QuotaSubject subject, Supplier<T> action) {
        QuotaSubject previous = CURRENT.get();
        set(subject);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    /** {@link #callWith(QuotaSubject, Supplier)} 的无返回值版本。 */
    public static void runWith(QuotaSubject subject, Runnable action) {
        callWith(subject, () -> {
            action.run();
            return null;
        });
    }
}
