package com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 主备容错模型：包裹一组有序候选 {@link Model}（主在前、备在后），带熔断记忆。
 *
 * <p>每次调用先取"未熔断"候选（全部熔断则退化为全量候选兜底，不拒绝服务），按序尝试：
 * 成功→{@link ModelCircuitBreakerRegistry#recordSuccess} 返回；失败→{@code recordFailure} 后切下一个；
 * 全部失败→抛最后一个异常（fast fail）。流式调用用 {@link Flux#onErrorResume} 链式降级，
 * 流中途失败也切下一个候选从头重发（{@code stream} 重新订阅）。</p>
 * @author owlzhangfq@gmail.com
 */
public class FailoverModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FailoverModel.class);

    /** 候选调用失败的日志错误码。 */
    private static final String CODE_FAILOVER_CALL_FAIL = "MODEL-FAILOVER-CALL-FAIL";

    private final List<Candidate> candidates;
    private final ModelCircuitBreakerRegistry registry;

    /**
     * @param candidates 有序候选（主在前备在后），至少一个
     * @param registry   熔断状态登记表
     */
    public FailoverModel(List<Candidate> candidates, ModelCircuitBreakerRegistry registry) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("FailoverModel requires at least one candidate");
        }
        this.candidates = candidates;
        this.registry = registry;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        List<Candidate> order = selectCandidates();
        return attemptFrom(order, 0, messages, tools, options);
    }

    /** 从第 {@code index} 个候选开始尝试，失败则递归降级到下一个。 */
    private Flux<ChatResponse> attemptFrom(List<Candidate> order, int index,
                                           List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Candidate candidate = order.get(index);
        return Flux.defer(() -> candidate.model().stream(messages, tools, options))
            .doOnComplete(() -> registry.recordSuccess(candidate.modelId()))
            .onErrorResume(error -> {
                registry.recordFailure(candidate.modelId());
                log.error("model candidate call failed, switching to next, code={}, modelId={}",
                    CODE_FAILOVER_CALL_FAIL, candidate.modelId(), error);
                if (index + 1 < order.size()) {
                    return attemptFrom(order, index + 1, messages, tools, options);
                }
                // fast fail：所有候选均失败，抛最后一个异常
                return Flux.error(error);
            });
    }

    /** 取未熔断候选；若全部熔断则退化为全量候选兜底（不能拒绝服务）。 */
    private List<Candidate> selectCandidates() {
        List<Candidate> available = candidates.stream()
            .filter(c -> !registry.isOpen(c.modelId()))
            .collect(Collectors.toList());
        return available.isEmpty() ? candidates : available;
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
     * 有序候选：{@code modelId} 供熔断登记，{@code model} 为真实调用实例。
     */
    public record Candidate(Long modelId, Model model) {
    }
}
