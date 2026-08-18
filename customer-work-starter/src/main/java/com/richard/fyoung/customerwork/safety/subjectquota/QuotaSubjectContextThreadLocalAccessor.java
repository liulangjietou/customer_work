package com.richard.fyoung.customerwork.safety.subjectquota;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 把 {@link QuotaSubjectContext} 的 ThreadLocal 接入 Reactor 自动上下文传播。
 *
 * <p>与租户上下文同理：请求链会在 boundedElastic 上执行阻塞 IO 与模型调用，线程一换
 * ThreadLocal 就没了，而 token 记账恰恰发生在换过线程之后。没有这个 Accessor，
 * 用量就永远记不到发起请求的那个人头上。</p>
 * @author owlzhangfq@gmail.com
 */
public class QuotaSubjectContextThreadLocalAccessor implements ThreadLocalAccessor<QuotaSubject> {

    /** Reactor Context 中承载主体的键，写入方（WebFilter / WS 分发）与本 Accessor 必须用同一个。 */
    public static final String KEY = "customer-work.quota-subject";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public QuotaSubject getValue() {
        return QuotaSubjectContext.get();
    }

    @Override
    public void setValue(QuotaSubject value) {
        QuotaSubjectContext.set(value);
    }

    @Override
    public void setValue() {
        QuotaSubjectContext.clear();
    }
}
