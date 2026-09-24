package com.richard.fyoung.customerwork.capability.handoff;

import java.util.function.Consumer;

/**
 * 一个会话上的转人工观察窗口：由 {@link HandoffService#watch} 打开，打开期间该会话每一次
 * {@link HandoffService#create} 都会把它标记为「请求过转人工」。
 *
 * <p><b>为什么记在建单入口，而不是让各来源自己报</b>：转人工的来源有五处（循环守卫、答复闸门、
 * Jev 升级、Jev 退款风险、转人工工具），以后还会有。让每处各自记得「顺手报一声」，漏一处不报错、
 * 只表现为某类答复被悄悄缓存下来——这正是本项目「能力只接在一条路上」反复复发的形状。
 * 所有来源最终都要经过 {@link HandoffService#create}，记在那里新来源自动覆盖。</p>
 *
 * <p><b>记的是「请求过」而不是「建成了」</b>：建单失败、或会话已在人工链路上（工单状态不变）时，
 * 答复里照样是那句「已为您转接」，同样不能原样重放给下一个人。</p>
 *
 * <p>关闭只是从登记处注销，已记下的结论保留——调用方要在 Agent 跑完之后读它。关闭可重复调用。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class HandoffWatch implements AutoCloseable {

    private final String sessionId;
    /** 关闭时的注销动作；游离窗口为 null。 */
    private final Consumer<HandoffWatch> release;
    private volatile boolean requested;

    HandoffWatch(String sessionId, Consumer<HandoffWatch> release) {
        this.sessionId = sessionId;
        this.release = release;
    }

    /**
     * 游离窗口：不登记在任何转人工服务上，恒为未请求。
     *
     * <p>只用于容器里压根没有转人工服务的装配——那时本进程里没有任何来源能建出转人工单。</p>
     */
    public static HandoffWatch detached() {
        return new HandoffWatch(null, null);
    }

    /** 窗口打开以来，该会话是否请求过转人工。 */
    public boolean requested() {
        return requested;
    }

    void markRequested() {
        requested = true;
    }

    String sessionId() {
        return sessionId;
    }

    @Override
    public void close() {
        if (release != null) {
            release.accept(this);
        }
    }

    @Override
    public String toString() {
        return "handoffRequested=" + requested;
    }
}
