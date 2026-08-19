package com.richard.fyoung.customerwork.infra.config;

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
import java.util.Locale;

/**
 * 带重试的模型包装（对应「生产健壮性 · 重试/退避」）。
 *
 * <p>实现统一 {@link Model} 抽象，对底层模型调用按指数退避重试，缓解瞬时网络抖动 / 限流，
 * 提升高可用。基于 Reactor 的 {@code retryWhen}，无需额外依赖。可与
 * {@link com.richard.fyoung.customerwork.core.model.failover.FailoverModel} 叠加（先重试，仍失败再切备）。</p>
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
                .maxBackoff(MAX_BACKOFF)        // 退避封顶，避免长尾等待
                .jitter(0.3)                    // 抖动，避免重试惊群
                .filter(ResilientChatModel::isRetryable)   // 只重试瞬时/服务端类错误
                .doBeforeRetry(rs -> log.info("[Model] retrying call after failure, code={}, attempt={}",
                    "MODEL_RETRY", rs.totalRetries() + 1,
                    rs.failure() == null ? "?" : rs.failure().getMessage())));
    }

    /**
     * 是否值得重试。<b>默认重试</b>（瞬时网络抖动、限流、服务端错误都应重试），
     * 仅当能从异常里识别出明确的"客户端错误"信号时才放弃重试——鉴权失败、参数非法、
     * 内容审核拒绝、上下文超长这类错误重试多少次都不会成功，只会白白浪费时延和 Token。
     *
     * <p>这里用消息文本做保守识别，避免硬依赖某个具体 SDK 的异常类型；接入方若有强类型的
     * 模型异常（携带 HTTP 状态码），建议替换为按状态码判断（{@code code == 429 || code >= 500} 才重试）。</p>
     */
    static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) {
                continue;
            }
            String m = msg.toLowerCase(Locale.ROOT);
            boolean clientError =
                m.contains("401") || m.contains("403") || m.contains("400") || m.contains("422")
                || m.contains("unauthorized") || m.contains("forbidden")
                || m.contains("invalid api key") || m.contains("invalid_api_key")
                || m.contains("invalidapikey") || m.contains("authentication")
                || m.contains("content") && (m.contains("policy") || m.contains("filter") || m.contains("risk"))
                || m.contains("context_length") || m.contains("too long") || m.contains("max tokens");
            if (clientError) {
                return false;   // 明确的客户端错误：快速失败，不重试
            }
        }
        return true;   // 未识别为客户端错误：默认重试（保守、不漏重试瞬时故障）
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
}
