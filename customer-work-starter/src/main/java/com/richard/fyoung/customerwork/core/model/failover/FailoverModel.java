package com.richard.fyoung.customerwork.core.model.failover;

import com.richard.fyoung.customerwork.core.model.routing.ModelRoutingContext;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 主备容错模型：包裹一组有序候选 {@link Model}（主在前、备在后），带熔断记忆。
 *
 * <p>每次调用先取"未熔断"候选（全部熔断则退化为全量候选兜底，不拒绝服务），按序尝试：
 * 成功→{@link ModelCircuitBreakerRegistry#recordSuccess} 返回；失败→{@code recordFailure} 后切下一个；
 * 全部失败→抛最后一个异常（fast fail）。流式调用用 {@link Flux#onErrorResume} 链式降级。</p>
 *
 * <p><b>首分片之后失败要不要继续切候选</b>由 {@code midStreamFailoverEnabled} 决定，两种语义都有真实场景：
 * <ul>
 *   <li>{@code true}（默认，动态智能体运行时）：流中途失败也切下一个候选从头重发（{@code stream} 重新订阅），
 *       宁可重复也要拿到完整回答；</li>
 *   <li>{@code false}（客服主链路）：已经吐过分片再切候选，会把"前一候选的前半段 + 新候选的完整输出"拼在一起，
 *       用户看到重复错乱的文字，此时直接把错误透传给上层做截断/兜底文案。</li>
 * </ul></p>
 * @author owlzhangfq@gmail.com
 */
public class FailoverModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FailoverModel.class);

    /** 候选调用失败的日志错误码。 */
    private static final String CODE_FAILOVER_CALL_FAIL = "MODEL-FAILOVER-CALL-FAIL";

    private final List<Candidate> candidates;
    private final ModelCircuitBreakerRegistry registry;
    private final boolean midStreamFailoverEnabled;
    private final Set<Long> unavailableCandidates;

    /**
     * 默认允许流中途失败切候选。
     *
     * @param candidates 有序候选（主在前备在后），至少一个
     * @param registry   熔断状态登记表
     */
    public FailoverModel(List<Candidate> candidates, ModelCircuitBreakerRegistry registry) {
        this(candidates, registry, true, Set.of());
    }

    /**
     * @param candidates               有序候选（主在前备在后），至少一个
     * @param registry                 熔断状态登记表
     * @param midStreamFailoverEnabled 首个分片已发出后再失败，是否仍切下一个候选
     */
    public FailoverModel(List<Candidate> candidates, ModelCircuitBreakerRegistry registry,
                         boolean midStreamFailoverEnabled) {
        this(candidates, registry, midStreamFailoverEnabled, Set.of());
    }

    /** 健康 overlay 标记的候选是硬不可用，不得因本地熔断候选耗尽而重新放行。 */
    public FailoverModel(List<Candidate> candidates, ModelCircuitBreakerRegistry registry,
                         boolean midStreamFailoverEnabled, Set<Long> unavailableCandidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("FailoverModel requires at least one candidate");
        }
        this.candidates = candidates;
        this.registry = registry;
        this.midStreamFailoverEnabled = midStreamFailoverEnabled;
        this.unavailableCandidates = unavailableCandidates == null
            ? Set.of() : Set.copyOf(unavailableCandidates);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(context -> {
            List<Candidate> order = ModelRoutingContext.isFallbackPreferred(context)
                ? selectFallbackCandidates()
                : selectCandidates();
            return attemptFrom(order, 0, messages, tools, options);
        });
    }

    /** 从第 {@code index} 个候选开始尝试，失败则递归降级到下一个。 */
    private Flux<ChatResponse> attemptFrom(List<Candidate> order, int index,
                                           List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Candidate candidate = order.get(index);
        // defer：每次订阅重新发起底层调用，并为本次订阅单独记"是否已吐过分片"
        return Flux.defer(() -> {
            AtomicBoolean chunkEmitted = new AtomicBoolean(false);
            return candidate.model().stream(messages, tools, options)
                .doOnNext(response -> chunkEmitted.set(true))
                .doOnComplete(() -> registry.recordSuccess(candidate.modelId()))
                .onErrorResume(error -> {
                    registry.recordFailure(candidate.modelId());
                    log.error("model candidate call failed, code={}, modelId={}, midStreamFailover={}",
                        CODE_FAILOVER_CALL_FAIL, candidate.modelId(), midStreamFailoverEnabled, error);
                    if (!midStreamFailoverEnabled && chunkEmitted.get()) {
                        // 已吐分片：切候选会造成前后半段拼接错乱，直接透传错误
                        return Flux.error(error);
                    }
                    if (index + 1 < order.size()) {
                        return attemptFrom(order, index + 1, messages, tools, options);
                    }
                    // fast fail：所有候选均失败，抛最后一个异常
                    return Flux.error(error);
                });
        });
    }

    /** 取未熔断候选；若全部熔断则退化为全量候选兜底（不能拒绝服务）。 */
    private List<Candidate> selectCandidates() {
        List<Candidate> eligible = candidates.stream()
            .filter(candidate -> !unavailableCandidates.contains(candidate.modelId()))
            .collect(Collectors.toList());
        if (eligible.isEmpty()) {
            throw new NoHealthyModelAvailableException("no healthy primary or fallback model available");
        }
        List<Candidate> available = eligible.stream()
            .filter(c -> !registry.isOpen(c.modelId()))
            .collect(Collectors.toList());
        return available.isEmpty() ? eligible : available;
    }

    /**
     * 配额降级只允许备用候选，不能在备用失败后偷偷回到主模型继续消耗原预算。
     * 熔断中的备用同样不可选；没有可用备用时快速失败，由入口层返回明确的额度提示。
     */
    private List<Candidate> selectFallbackCandidates() {
        List<Candidate> available = candidates.stream()
            .skip(1)
            .filter(candidate -> !unavailableCandidates.contains(candidate.modelId()))
            .filter(candidate -> !registry.isOpen(candidate.modelId()))
            .collect(Collectors.toList());
        if (available.isEmpty()) {
            throw new FallbackModelUnavailableException("no available fallback model for forced route");
        }
        return available;
    }

    /** 标识/能力探测均委托给配置的主模型（候选列表首个）。 */
    private Model primary() {
        return candidates.get(0).model();
    }

    @Override
    public String getModelName() {
        return primary().getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return primary().supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return primary().supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return primary().getContextWindowSize();
    }

    /**
     * 有序候选：{@code modelId} 供熔断登记（admin 侧为 {@code ai_model_config.id}；
     * starter 自身按 yml 建链时用固定序号），{@code model} 为真实调用实例。
     */
    public record Candidate(Long modelId, Model model) {
    }

    /** 强制降级时没有可用备用模型。 */
    public static class FallbackModelUnavailableException extends IllegalStateException {
        public FallbackModelUnavailableException(String message) {
            super(message);
        }
    }

    public static class NoHealthyModelAvailableException extends IllegalStateException {
        public NoHealthyModelAvailableException(String message) {
            super(message);
        }
    }
}
