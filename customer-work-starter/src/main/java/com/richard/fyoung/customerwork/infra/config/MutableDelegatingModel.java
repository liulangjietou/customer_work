package com.richard.fyoung.customerwork.infra.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可热替换的模型委托（对应「admin 改配置 → 8080 模型热生效」）。
 *
 * <p>{@code chatModel} Bean 以本类形式暴露给全部消费方（AgentFactory / 编排 / 调度 / 记忆等 4 处），
 * 内部委托到一个 {@link AtomicReference} 持有的真实模型链（primary→fallback→retry）。运营侧在 admin
 * 改动模型配置并下发后，{@code RuntimeConfigApplier} 调用 {@link #swap(Model)} 原子替换内部链，所有
 * 已持有本引用的消费方<b>下一次调用即用上新链</b>，无需重启、无需重新注入。</p>
 *
 * <p>swap 与 stream 之间无锁：{@link AtomicReference#get()} 与 {@link AtomicReference#set(Object)} 各自
 * 原子，单次 {@code stream} 调用在方法入口一次性读定当前链后完成整轮，不会读到"半替换"的链；正在进行
 * 的旧调用继续用旧链跑完，新调用用新链，切换对上层透明。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class MutableDelegatingModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(MutableDelegatingModel.class);

    private final AtomicReference<Model> delegate;

    public MutableDelegatingModel(Model initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial model must not be null");
        }
        this.delegate = new AtomicReference<>(initial);
    }

    /**
     * 原子替换内部模型链。旧链上在途的调用不受影响（各自跑完），后续调用一律走新链。
     *
     * @param next 新模型链（非空）
     */
    public void swap(Model next) {
        if (next == null) {
            throw new IllegalArgumentException("swap target model must not be null");
        }
        Model prev = delegate.getAndSet(next);
        log.info("[hot-config] model chain swapped, from={}, to={}",
            prev.getModelName(), next.getModelName());
    }

    /** 当前生效的模型链（测试/诊断用）。 */
    public Model current() {
        return delegate.get();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return delegate.get().stream(messages, tools, options);
    }

    @Override
    public String getModelName() {
        return delegate.get().getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.get().supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.get().supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.get().getContextWindowSize();
    }
}
