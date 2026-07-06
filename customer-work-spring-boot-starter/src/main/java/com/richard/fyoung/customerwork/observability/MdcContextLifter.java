package com.richard.fyoung.customerwork.observability;

import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

import java.util.List;

/**
 * Reactor Context → SLF4J MDC 桥接订阅者（线上故障定位的关键一环）。
 *
 * <p>问题背景：WebFlux 是反应式非阻塞栈，一次请求会在多个线程间流转，传统基于 ThreadLocal 的
 * MDC 无法自动跨线程传递。因此仅靠 {@code RequestIdWebFilter} 把 requestId 放进 Reactor Context
 * 还不够——日志框架读的是 MDC（ThreadLocal），拿不到 Context 里的值，logback pattern 的
 * {@code %X{requestId}} 永远是空。</p>
 *
 * <p>本类在每个 Reactor 信号（onNext / onError / onComplete）回调<b>之前</b>，把 Context 中约定的
 * 关联字段（requestId / sessionId）同步到当前线程的 MDC，使该线程上执行的任意日志都能带上关联字段，
 * 从而实现"一个 requestId 串起全链路日志"。通过 {@code Hooks.onEachOperator} 全局织入
 * （见 {@link MdcConfig}），业务代码零侵入。</p>
 *
 * @param <T> 信号元素类型
 * @author owlzhangfq@gmail.com
 */
public class MdcContextLifter<T> implements CoreSubscriber<T> {

    /** Reactor Context / MDC 中 requestId 的键（与 {@code RequestIdWebFilter.CONTEXT_KEY} 一致）。 */
    public static final String REQUEST_ID_KEY = "requestId";
    /** Reactor Context / MDC 中 sessionId 的键。 */
    public static final String SESSION_ID_KEY = "sessionId";
    /** 需要从 Context 同步到 MDC 的字段集合。 */
    public static final List<String> MDC_KEYS = List.of(REQUEST_ID_KEY, SESSION_ID_KEY);

    private final CoreSubscriber<? super T> delegate;

    public MdcContextLifter(CoreSubscriber<? super T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(T item) {
        copyContextToMdc();
        delegate.onNext(item);
    }

    @Override
    public void onError(Throwable throwable) {
        copyContextToMdc();
        delegate.onError(throwable);
    }

    @Override
    public void onComplete() {
        copyContextToMdc();
        delegate.onComplete();
    }

    @Override
    public Context currentContext() {
        return delegate.currentContext();
    }

    /** 把 Context 中约定字段同步到 MDC；Context 无该键时从 MDC 清除，避免线程复用串号。 */
    private void copyContextToMdc() {
        Context ctx = delegate.currentContext();
        for (String key : MDC_KEYS) {
            Object value = ctx.hasKey(key) ? ctx.get(key) : null;
            if (value != null) {
                MDC.put(key, value.toString());
            } else {
                MDC.remove(key);
            }
        }
    }
}
