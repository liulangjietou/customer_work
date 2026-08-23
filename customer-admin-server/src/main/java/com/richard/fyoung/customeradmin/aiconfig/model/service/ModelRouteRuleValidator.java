package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelRoutePurpose;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteCondition;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteConflictVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteRuleRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 路由条件规范化、冲突检测和 dry-run 逐维解释。 */
@Component
public class ModelRouteRuleValidator {

    private static final Set<String> COMPLEXITIES = Set.of("LOW", "MEDIUM", "HIGH");

    public List<ModelRouteConflictVO> validate(List<ModelRouteRuleRequest> rules) {
        List<ModelRouteConflictVO> conflicts = new ArrayList<>();
        if (CollectionUtils.isEmpty(rules)) {
            conflicts.add(new ModelRouteConflictVO("ROUTE_RULES_EMPTY", null, null, "至少需要一条路由规则"));
            return conflicts;
        }
        int unconditionalDefaults = 0;
        int fallbackCount = 0;
        for (int i = 0; i < rules.size(); i++) {
            ModelRouteRuleRequest rule = rules.get(i);
            ModelRoutePurpose purpose = parsePurpose(rule == null ? null : rule.purpose(), i, conflicts);
            ModelRouteCondition condition = normalize(rule == null ? null : rule.condition());
            validateCondition(condition, i, conflicts);
            if (purpose == ModelRoutePurpose.DEFAULT && isUnconditional(condition)) {
                unconditionalDefaults++;
            }
            if (purpose == ModelRoutePurpose.FALLBACK) {
                fallbackCount++;
                if (!isUnconditional(condition)) {
                    conflicts.add(new ModelRouteConflictVO("FALLBACK_MUST_BE_UNCONDITIONAL", i, null,
                        "故障兜底规则必须无条件，确保 DEGRADE 可确定性 fail-closed"));
                }
            }
        }
        if (unconditionalDefaults != 1) {
            conflicts.add(new ModelRouteConflictVO("DEFAULT_RULE_REQUIRED", null, null,
                "必须且只能有一条无条件 DEFAULT 规则"));
        }
        if (fallbackCount > 1) {
            conflicts.add(new ModelRouteConflictVO("FALLBACK_RULE_DUPLICATE", null, null,
                "最多允许一条无条件 FALLBACK 规则"));
        }
        detectPriorityConflicts(rules, conflicts);
        return List.copyOf(conflicts);
    }

    public ModelRouteCondition normalize(ModelRouteCondition condition) {
        if (condition == null) {
            return ModelRouteCondition.empty();
        }
        List<Long> agentIds = condition.agentIds() == null ? List.of()
            : condition.agentIds().stream().filter(id -> id != null).distinct().sorted().toList();
        List<String> channelCodes = condition.channelCodes() == null ? List.of()
            : condition.channelCodes().stream().filter(StringUtils::hasText)
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        String complexity = StringUtils.hasText(condition.complexity())
            ? condition.complexity().trim().toUpperCase(Locale.ROOT) : null;
        return new ModelRouteCondition(agentIds, channelCodes, condition.minInputTokens(),
            condition.maxInputTokens(), condition.requiresTools(), condition.requiresStructuredOutput(), complexity);
    }

    public MatchExplanation explain(ModelRouteCondition raw, ModelRouteDryRunContext context) {
        ModelRouteCondition condition = normalize(raw);
        List<String> reasons = new ArrayList<>();
        boolean matched = true;
        if (!condition.agentIds().isEmpty()) {
            boolean current = context.agentId() != null && condition.agentIds().contains(context.agentId());
            reasons.add("agentId " + (current ? "命中" : "未命中") + " " + condition.agentIds());
            matched &= current;
        }
        if (!condition.channelCodes().isEmpty()) {
            String channel = StringUtils.hasText(context.channelCode())
                ? context.channelCode().trim().toLowerCase(Locale.ROOT) : null;
            boolean current = channel != null && condition.channelCodes().contains(channel);
            reasons.add("channelCode " + (current ? "命中" : "未命中") + " " + condition.channelCodes());
            matched &= current;
        }
        int tokens = context.inputTokens() == null ? 0 : context.inputTokens();
        if (condition.minInputTokens() != null) {
            boolean current = tokens >= condition.minInputTokens();
            reasons.add("inputTokens=" + tokens + (current ? " ≥ " : " < ") + condition.minInputTokens());
            matched &= current;
        }
        if (condition.maxInputTokens() != null) {
            boolean current = tokens <= condition.maxInputTokens();
            reasons.add("inputTokens=" + tokens + (current ? " ≤ " : " > ") + condition.maxInputTokens());
            matched &= current;
        }
        if (condition.requiresTools() != null) {
            boolean current = condition.requiresTools().equals(Boolean.TRUE.equals(context.requiresTools()));
            reasons.add("requiresTools " + (current ? "命中" : "未命中"));
            matched &= current;
        }
        if (condition.requiresStructuredOutput() != null) {
            boolean current = condition.requiresStructuredOutput()
                .equals(Boolean.TRUE.equals(context.requiresStructuredOutput()));
            reasons.add("requiresStructuredOutput " + (current ? "命中" : "未命中"));
            matched &= current;
        }
        if (condition.complexity() != null) {
            String complexity = StringUtils.hasText(context.complexity())
                ? context.complexity().trim().toUpperCase(Locale.ROOT) : null;
            boolean current = condition.complexity().equals(complexity);
            reasons.add("complexity " + (current ? "命中" : "未命中") + " " + condition.complexity());
            matched &= current;
        }
        if (reasons.isEmpty()) {
            reasons.add("无条件规则");
        }
        return new MatchExplanation(matched, List.copyOf(reasons));
    }

    public String summary(ModelRouteCondition raw) {
        ModelRouteCondition condition = normalize(raw);
        List<String> parts = new ArrayList<>();
        if (!condition.agentIds().isEmpty()) {
            parts.add("agent∈" + condition.agentIds());
        }
        if (!condition.channelCodes().isEmpty()) {
            parts.add("channel∈" + condition.channelCodes());
        }
        if (condition.minInputTokens() != null) {
            parts.add("tokens≥" + condition.minInputTokens());
        }
        if (condition.maxInputTokens() != null) {
            parts.add("tokens≤" + condition.maxInputTokens());
        }
        if (condition.requiresTools() != null) {
            parts.add("tools=" + condition.requiresTools());
        }
        if (condition.requiresStructuredOutput() != null) {
            parts.add("json=" + condition.requiresStructuredOutput());
        }
        if (condition.complexity() != null) {
            parts.add("complexity=" + condition.complexity());
        }
        return parts.isEmpty() ? "无条件" : String.join(" AND ", parts);
    }

    private void detectPriorityConflicts(List<ModelRouteRuleRequest> rules,
                                         List<ModelRouteConflictVO> conflicts) {
        for (int left = 0; left < rules.size(); left++) {
            ModelRouteRuleRequest a = rules.get(left);
            if (a == null || a.priority() == null) {
                continue;
            }
            for (int right = left + 1; right < rules.size(); right++) {
                ModelRouteRuleRequest b = rules.get(right);
                if (b == null || !a.priority().equals(b.priority())) {
                    continue;
                }
                ModelRoutePurpose aPurpose = safePurpose(a.purpose());
                ModelRoutePurpose bPurpose = safePurpose(b.purpose());
                if (aPurpose == null || bPurpose == null
                    || (aPurpose == ModelRoutePurpose.FALLBACK) != (bPurpose == ModelRoutePurpose.FALLBACK)) {
                    continue;
                }
                if (overlaps(normalize(a.condition()), normalize(b.condition()))) {
                    conflicts.add(new ModelRouteConflictVO("ROUTE_PRIORITY_CONFLICT", left, right,
                        "相同优先级 " + a.priority() + " 的条件存在交集，命中结果不确定"));
                }
            }
        }
    }

    private boolean overlaps(ModelRouteCondition left, ModelRouteCondition right) {
        return listOverlaps(left.agentIds(), right.agentIds())
            && listOverlaps(left.channelCodes(), right.channelCodes())
            && rangeOverlaps(left.minInputTokens(), left.maxInputTokens(),
                right.minInputTokens(), right.maxInputTokens())
            && scalarOverlaps(left.requiresTools(), right.requiresTools())
            && scalarOverlaps(left.requiresStructuredOutput(), right.requiresStructuredOutput())
            && scalarOverlaps(left.complexity(), right.complexity());
    }

    private boolean listOverlaps(List<?> left, List<?> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return true;
        }
        Set<Object> values = new HashSet<>(left);
        return right.stream().anyMatch(values::contains);
    }

    private boolean rangeOverlaps(Integer leftMin, Integer leftMax, Integer rightMin, Integer rightMax) {
        int aMin = leftMin == null ? 0 : leftMin;
        int aMax = leftMax == null ? Integer.MAX_VALUE : leftMax;
        int bMin = rightMin == null ? 0 : rightMin;
        int bMax = rightMax == null ? Integer.MAX_VALUE : rightMax;
        return aMin <= bMax && bMin <= aMax;
    }

    private boolean scalarOverlaps(Object left, Object right) {
        return left == null || right == null || left.equals(right);
    }

    private void validateCondition(ModelRouteCondition condition, int index,
                                   List<ModelRouteConflictVO> conflicts) {
        if (condition.minInputTokens() != null && condition.minInputTokens() < 0
            || condition.maxInputTokens() != null && condition.maxInputTokens() < 0) {
            conflicts.add(new ModelRouteConflictVO("TOKEN_RANGE_INVALID", index, null,
                "token 条件不能小于 0"));
        }
        if (condition.minInputTokens() != null && condition.maxInputTokens() != null
            && condition.minInputTokens() > condition.maxInputTokens()) {
            conflicts.add(new ModelRouteConflictVO("TOKEN_RANGE_INVALID", index, null,
                "minInputTokens 不能大于 maxInputTokens"));
        }
        if (condition.complexity() != null && !COMPLEXITIES.contains(condition.complexity())) {
            conflicts.add(new ModelRouteConflictVO("COMPLEXITY_INVALID", index, null,
                "complexity 仅支持 LOW/MEDIUM/HIGH"));
        }
    }

    private ModelRoutePurpose parsePurpose(String value, int index,
                                           List<ModelRouteConflictVO> conflicts) {
        ModelRoutePurpose purpose = safePurpose(value);
        if (purpose == null) {
            conflicts.add(new ModelRouteConflictVO("PURPOSE_INVALID", index, null,
                "purpose 仅支持 DEFAULT/ECONOMY/COMPLEX_REASONING/FALLBACK"));
        }
        return purpose;
    }

    private ModelRoutePurpose safePurpose(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return ModelRoutePurpose.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isUnconditional(ModelRouteCondition condition) {
        return condition.agentIds().isEmpty() && condition.channelCodes().isEmpty()
            && condition.minInputTokens() == null && condition.maxInputTokens() == null
            && condition.requiresTools() == null && condition.requiresStructuredOutput() == null
            && condition.complexity() == null;
    }

    public record ModelRouteDryRunContext(Long agentId,
                                          String channelCode,
                                          Integer inputTokens,
                                          Boolean requiresTools,
                                          Boolean requiresStructuredOutput,
                                          String complexity) {
    }

    public record MatchExplanation(boolean matched, List<String> reasons) {
    }
}
