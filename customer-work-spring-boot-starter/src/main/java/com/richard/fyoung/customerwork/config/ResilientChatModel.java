package com.richard.fyoung.customerwork.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * 带重试的模型包装（对应「生产健壮性 · 重试/退避」）。
 *
 * <p>实现统一 {@link Model} 抽象，对底层模型调用按指数退避重试，缓解瞬时网络抖动 / 限流，
 * 提升高可用。基于 Reactor 的 {@code retryWhen}，无需额外依赖。可与 {@link FallbackChatModel}
 * 叠加（先重试，仍失败再兜底）。</p>
 * @author owlzhangfq@gmail.com
 */
public class ResilientChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatModel.class);

    private final Model delegate;
    private final int maxAttempts;
    private final Duration backoff;

    public ResilientChatModel(Model delegate, int maxAttempts, long backoffMs) {
        this.delegate = delegate;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = Duration.ofMillis(Math.max(0, backoffMs));
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // defer：每次重试重新发起底层调用（而非重订阅同一个已失败的流）
        return Flux.defer(() -> delegate.stream(messages, tools, options))
            .retryWhen(Retry.backoff(maxAttempts, backoff)
                .doBeforeRetry(rs -> log.warn("[Model] 调用失败重试 #{}：{}",
                    rs.totalRetries() + 1,
                    rs.failure() == null ? "?" : rs.failure().getMessage())));
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }
}
