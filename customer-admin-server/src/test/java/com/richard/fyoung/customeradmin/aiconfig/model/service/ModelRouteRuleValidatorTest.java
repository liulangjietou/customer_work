package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteCondition;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelRouteRuleRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRouteRuleValidatorTest {

    private final ModelRouteRuleValidator validator = new ModelRouteRuleValidator();

    @Test
    void validate_shouldAcceptDeterministicDefaultEconomyReasoningAndFallback() {
        List<ModelRouteRuleRequest> rules = List.of(
            rule("ECONOMY", 2L, 10, condition(null, 800, false, null, "LOW")),
            rule("COMPLEX_REASONING", 3L, 20, condition(801, null, null, null, "HIGH")),
            rule("DEFAULT", 1L, 100, null),
            rule("FALLBACK", 4L, 0, null));

        assertTrue(validator.validate(rules).isEmpty());
    }

    @Test
    void validate_shouldRejectOverlappingRulesAtSamePriority() {
        List<ModelRouteRuleRequest> rules = List.of(
            rule("ECONOMY", 2L, 10, condition(null, 1000, null, null, null)),
            rule("COMPLEX_REASONING", 3L, 10, condition(500, null, null, null, null)),
            rule("DEFAULT", 1L, 100, null));

        assertTrue(validator.validate(rules).stream()
            .anyMatch(conflict -> "ROUTE_PRIORITY_CONFLICT".equals(conflict.code())));
    }

    @Test
    void validate_shouldRejectConditionalFallback() {
        List<ModelRouteRuleRequest> rules = List.of(
            rule("DEFAULT", 1L, 100, null),
            rule("FALLBACK", 4L, 0, condition(null, null, true, null, null)));

        assertTrue(validator.validate(rules).stream()
            .anyMatch(conflict -> "FALLBACK_MUST_BE_UNCONDITIONAL".equals(conflict.code())));
    }

    @Test
    void explain_shouldReportEveryMatchedDimension() {
        ModelRouteCondition condition = new ModelRouteCondition(List.of(7L), List.of("WECHAT"),
            100, 500, true, false, "HIGH");

        ModelRouteRuleValidator.MatchExplanation explanation = validator.explain(condition,
            new ModelRouteRuleValidator.ModelRouteDryRunContext(7L, "wechat", 300, true, false, "high"));

        assertTrue(explanation.matched());
        assertEquals(7, explanation.reasons().size());
        assertFalse(explanation.reasons().stream().anyMatch(reason -> reason.contains("未命中")));
    }

    private ModelRouteRuleRequest rule(String purpose, Long deploymentId, int priority,
                                       ModelRouteCondition condition) {
        return new ModelRouteRuleRequest(purpose, deploymentId, priority, condition);
    }

    private ModelRouteCondition condition(Integer minTokens, Integer maxTokens,
                                          Boolean tools, Boolean json, String complexity) {
        return new ModelRouteCondition(List.of(), List.of(), minTokens, maxTokens, tools, json, complexity);
    }
}
