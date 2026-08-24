package com.richard.fyoung.customerwork.core.model.routing;

import com.richard.fyoung.customerwork.core.model.failover.FailoverModel.FallbackModelUnavailableException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 不可变策略驱动的确定性模型路由器。
 *
 * <p>普通调用按 priority、ruleId 取第一条命中规则；配额 DEGRADE 永远只看 FALLBACK 规则，缺失即
 * fail-closed。正常候选在首分片前失败时可切一次策略备用，已经吐出分片后不切换，避免拼接两段回答。</p>
 */
public final class PolicyRoutingModel implements Model {

    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;
    private static final int MEDIUM_COMPLEXITY_TOKENS = 500;
    private static final int HIGH_COMPLEXITY_TOKENS = 2000;

    private static final Comparator<PolicyRouteSpec.Rule> RULE_ORDER =
        Comparator.comparing(PolicyRouteSpec.Rule::priority)
            .thenComparing(PolicyRouteSpec.Rule::ruleId);

    private final PolicyRouteSpec spec;
    private final Map<Long, Model> models;
    private final List<PolicyRouteSpec.Rule> normalRules;
    private final PolicyRouteSpec.Rule fallbackRule;
    private final Model capabilityBaseline;

    public PolicyRoutingModel(PolicyRouteSpec spec, Map<Long, Model> models) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.models = Map.copyOf(Objects.requireNonNull(models, "models"));
        validateSpec();
        this.normalRules = spec.rules().stream()
            .filter(rule -> rule.purpose() != PolicyRouteSpec.Purpose.FALLBACK)
            .sorted(RULE_ORDER)
            .toList();
        this.fallbackRule = spec.rules().stream()
            .filter(rule -> rule.purpose() == PolicyRouteSpec.Purpose.FALLBACK)
            .sorted(RULE_ORDER)
            .findFirst().orElse(null);
        this.capabilityBaseline = modelFor(unconditionalDefault());
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.deferContextual(context -> {
            boolean forcedFallback = ModelRoutingContext.isFallbackPreferred(context);
            PolicyRouteSpec.Rule selected = forcedFallback
                ? requireFallbackRule()
                : selectNormal(resolveHint(context, messages, tools, options));
            Model selectedModel = modelFor(selected);
            AtomicBoolean emitted = new AtomicBoolean(false);
            Flux<ChatResponse> primary = selectedModel.stream(messages, tools, options)
                .doOnNext(response -> emitted.set(true));
            if (forcedFallback || fallbackRule == null || !isAvailable(fallbackRule.deploymentId())
                || Objects.equals(selected.deploymentId(), fallbackRule.deploymentId())) {
                return primary;
            }
            return primary.onErrorResume(error -> emitted.get()
                ? Flux.error(error)
                : modelFor(fallbackRule).stream(messages, tools, options));
        });
    }

    /** 供 dry-run/单测复用同一确定性选择语义。 */
    public Long selectDeployment(ModelRouteHint hint) {
        return selectNormal(hint == null ? ModelRouteHint.empty() : hint).deploymentId();
    }

    public PolicyRouteSpec spec() {
        return spec;
    }

    @Override
    public String getModelName() {
        return "policy:" + spec.policyId() + ":v" + spec.versionNo();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return models.values().stream().allMatch(Model::supportsNativeStructuredOutput);
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return models.values().stream().allMatch(Model::supportsNativeStructuredOutputWithTools);
    }

    @Override
    public int getContextWindowSize() {
        return models.values().stream().mapToInt(Model::getContextWindowSize)
            .min().orElseGet(capabilityBaseline::getContextWindowSize);
    }

    private PolicyRouteSpec.Rule selectNormal(ModelRouteHint hint) {
        PolicyRouteSpec.Rule matched = normalRules.stream()
            .filter(rule -> matches(rule.condition(), hint))
            .filter(rule -> isAvailable(rule.deploymentId()))
            .findFirst().orElse(null);
        if (matched != null) {
            return matched;
        }
        if (fallbackRule != null && isAvailable(fallbackRule.deploymentId())) {
            return fallbackRule;
        }
        throw new ModelRouteUnavailableException(
            "no healthy route matched policy " + spec.policyId() + " version " + spec.versionNo());
    }

    private PolicyRouteSpec.Rule requireFallbackRule() {
        if (fallbackRule == null || !models.containsKey(fallbackRule.deploymentId())
            || !isAvailable(fallbackRule.deploymentId())) {
            throw new FallbackModelUnavailableException("no policy fallback deployment for forced route");
        }
        return fallbackRule;
    }

    private boolean matches(PolicyRouteSpec.Condition raw, ModelRouteHint hint) {
        PolicyRouteSpec.Condition condition = raw == null ? PolicyRouteSpec.Condition.empty() : raw;
        if (!condition.agentIds().isEmpty()
            && (hint.agentId() == null || !condition.agentIds().contains(hint.agentId()))) {
            return false;
        }
        if (!condition.channelCodes().isEmpty()) {
            String channel = normalize(hint.channelCode());
            if (channel == null || condition.channelCodes().stream().map(this::normalize)
                .noneMatch(channel::equals)) {
                return false;
            }
        }
        int tokens = hint.inputTokens() == null ? 0 : hint.inputTokens();
        if (condition.minInputTokens() != null && tokens < condition.minInputTokens()
            || condition.maxInputTokens() != null && tokens > condition.maxInputTokens()) {
            return false;
        }
        if (condition.requiresTools() != null
            && !condition.requiresTools().equals(Boolean.TRUE.equals(hint.requiresTools()))) {
            return false;
        }
        if (condition.requiresStructuredOutput() != null
            && !condition.requiresStructuredOutput().equals(
                Boolean.TRUE.equals(hint.requiresStructuredOutput()))) {
            return false;
        }
        return condition.complexity() == null
            || condition.complexity().equalsIgnoreCase(hint.complexity());
    }

    private ModelRouteHint resolveHint(ContextView context,
                                       List<Msg> messages,
                                       List<ToolSchema> tools,
                                       GenerateOptions options) {
        ModelRouteHint supplied = ModelRoutingContext.routeHint(context);
        int tokens = supplied.inputTokens() == null ? estimateInputTokens(messages) : supplied.inputTokens();
        boolean requiresTools = supplied.requiresTools() == null
            ? tools != null && !tools.isEmpty() : supplied.requiresTools();
        boolean requiresStructured = supplied.requiresStructuredOutput() == null
            ? options != null && options.getResponseFormat() != null : supplied.requiresStructuredOutput();
        String complexity = supplied.complexity() == null
            ? inferComplexity(tokens, options) : supplied.complexity();
        return new ModelRouteHint(
            supplied.agentId() == null ? spec.agentId() : supplied.agentId(),
            supplied.channelCode() == null ? spec.channelCode() : supplied.channelCode(),
            tokens, requiresTools, requiresStructured, complexity);
    }

    private int estimateInputTokens(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int characters = 0;
        for (Msg message : messages) {
            if (message == null) {
                continue;
            }
            String text = message.getTextContent();
            characters += text == null ? 0 : text.length();
        }
        return Math.max(1, (characters + CHARS_PER_TOKEN_ESTIMATE - 1) / CHARS_PER_TOKEN_ESTIMATE);
    }

    private String inferComplexity(int tokens, GenerateOptions options) {
        if (options != null && options.getReasoningEffort() != null) {
            String effort = options.getReasoningEffort().trim().toUpperCase(Locale.ROOT);
            if ("LOW".equals(effort) || "MEDIUM".equals(effort) || "HIGH".equals(effort)) {
                return effort;
            }
        }
        if (tokens >= HIGH_COMPLEXITY_TOKENS) {
            return "HIGH";
        }
        return tokens >= MEDIUM_COMPLEXITY_TOKENS ? "MEDIUM" : "LOW";
    }

    private void validateSpec() {
        if (spec.policyId() == null || spec.versionId() == null || spec.versionNo() == null
            || spec.rules().isEmpty()) {
            throw new IllegalArgumentException("routing policy identity/version/rules are required");
        }
        List<PolicyRouteSpec.Rule> defaults = new ArrayList<>();
        int fallbackCount = 0;
        for (PolicyRouteSpec.Rule rule : spec.rules()) {
            if (rule == null || rule.ruleId() == null || rule.purpose() == null
                || rule.deploymentId() == null || rule.priority() == null) {
                throw new IllegalArgumentException("routing rule fields are incomplete");
            }
            if (!models.containsKey(rule.deploymentId())) {
                throw new IllegalArgumentException("routing deployment model is missing: " + rule.deploymentId());
            }
            if (rule.purpose() == PolicyRouteSpec.Purpose.DEFAULT && isUnconditional(rule.condition())) {
                defaults.add(rule);
            }
            if (rule.purpose() == PolicyRouteSpec.Purpose.FALLBACK) {
                fallbackCount++;
                if (!isUnconditional(rule.condition())) {
                    throw new IllegalArgumentException("fallback route must be unconditional");
                }
            }
        }
        if (defaults.size() != 1 || fallbackCount > 1) {
            throw new IllegalArgumentException("routing policy requires one default and at most one fallback");
        }
    }

    private PolicyRouteSpec.Rule unconditionalDefault() {
        return spec.rules().stream()
            .filter(rule -> rule.purpose() == PolicyRouteSpec.Purpose.DEFAULT
                && isUnconditional(rule.condition()))
            .findFirst()
            .orElseThrow();
    }

    private boolean isUnconditional(PolicyRouteSpec.Condition raw) {
        PolicyRouteSpec.Condition condition = raw == null ? PolicyRouteSpec.Condition.empty() : raw;
        return condition.agentIds().isEmpty() && condition.channelCodes().isEmpty()
            && condition.minInputTokens() == null && condition.maxInputTokens() == null
            && condition.requiresTools() == null && condition.requiresStructuredOutput() == null
            && condition.complexity() == null;
    }

    private Model modelFor(PolicyRouteSpec.Rule rule) {
        return models.get(rule.deploymentId());
    }

    private boolean isAvailable(Long deploymentId) {
        PolicyRouteSpec.Health health = spec.healthOverlays().get(deploymentId);
        return health == null || health.routingAvailable();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 普通请求没有任何规则命中；这是错误配置，不允许偷偷回主模型。 */
    public static class ModelRouteUnavailableException extends IllegalStateException {
        public ModelRouteUnavailableException(String message) {
            super(message);
        }
    }
}
