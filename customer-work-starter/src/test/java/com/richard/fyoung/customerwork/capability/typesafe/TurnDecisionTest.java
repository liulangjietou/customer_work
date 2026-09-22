package com.richard.fyoung.customerwork.capability.typesafe;

import com.richard.fyoung.customerwork.capability.typesafe.TurnDecision.EscalationAction;
import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnDecisionTest {

    private static final int TOP = 3;
    private final TypeSafeProperties props = new TypeSafeProperties();

    /**
     * 这条是照着官方文档改出来的。Score 的 score 是<b>期望值</b>（各档编号 × 概率求和），
     * 最高档占 95% 时期望值是 2.95 而不是 3。若按「score 是否等于最高档编号」判定，
     * 期望值几乎永远不会恰好等于 3.0，自动转人工会形同虚设；正确的做法是看概率最大的档位。
     */
    @Test
    @DisplayName("期望值 2.95 而非 3.0：仍按最可能档位判为最高档，直接转人工")
    void autoHandoffUsesMostLikelyLevelNotScore() {
        EscalationAction action = escalate(2.95, 0.93, Map.of(0, 0.0, 1, 0.0, 2, 0.05, 3, 0.95));

        assertEquals(EscalationAction.AUTO_HANDOFF, action);
    }

    @Test
    @DisplayName("明显不满、最可能是第 2 档：只提示模型")
    void upsetIsHint() {
        // 期望值 0.05×1 + 0.5×2 + 0.45×3 = 2.4
        assertEquals(EscalationAction.HINT, escalate(2.4, 0.62, Map.of(0, 0.0, 1, 0.05, 2, 0.5, 3, 0.45)));
    }

    @Test
    @DisplayName("最可能是最高档但置信度不够：退回提示")
    void topLevelWithoutConfidenceIsOnlyHint() {
        // 期望值 0.3×2 + 0.7×3 = 2.7
        assertEquals(EscalationAction.HINT, escalate(2.7, 0.7, Map.of(0, 0.0, 1, 0.0, 2, 0.3, 3, 0.7)));
    }

    @Test
    @DisplayName("情绪平稳：不处置")
    void calmIsNone() {
        assertEquals(EscalationAction.NONE, escalate(0.2, 0.9, Map.of(0, 0.8, 1, 0.2, 2, 0.0, 3, 0.0)));
    }

    @Test
    @DisplayName("缺少情绪答案：不处置")
    void missingEscalationIsNone() {
        TurnDecision decision = new TurnDecision(new SystemOneAnswer.Choice("order", 0.99), null, "m", 1);
        assertEquals(EscalationAction.NONE, decision.escalationAction(props.getEscalation(), TOP));
    }

    @Test
    @DisplayName("意图高置信且有映射：收窄到对应工具组")
    void scopesMappedIntent() {
        assertEquals(Optional.of(List.of("after_sales", "order", "knowledge")),
            intent("refund", 0.95).scopedGroups(props.getToolScope()));
    }

    @Test
    @DisplayName("置信度低于门槛、意图为 other、未配置映射：一律不收窄")
    void doesNotScopeWhenUnsure() {
        assertTrue(intent("refund", 0.89).scopedGroups(props.getToolScope()).isEmpty(), "0.89 < 0.9");
        assertTrue(intent(TurnDecision.INTENT_OTHER, 0.99).scopedGroups(props.getToolScope()).isEmpty());
        props.getToolScope().getIntentGroups().remove("order");
        assertTrue(intent("order", 0.99).scopedGroups(props.getToolScope()).isEmpty());
    }

    private EscalationAction escalate(double score, double confidence, Map<Integer, Double> probabilities) {
        TurnDecision decision = new TurnDecision(null,
            new SystemOneAnswer.Score(score, confidence, probabilities), "m", 1);
        return decision.escalationAction(props.getEscalation(), TOP);
    }

    private TurnDecision intent(String code, double confidence) {
        return new TurnDecision(new SystemOneAnswer.Choice(code, confidence), null, "m", 1);
    }
}
