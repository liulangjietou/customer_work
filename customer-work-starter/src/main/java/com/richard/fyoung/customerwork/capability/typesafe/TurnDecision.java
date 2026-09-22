package com.richard.fyoung.customerwork.capability.typesafe;

import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一轮对话入站时的 Jev 决策：一次调用同时回答「意图」与「情绪升级倾向」（官方 Speculative Fan-out）。
 *
 * <p>两个答案都可能缺失（部分问题解析失败），缺失的一侧一律按「无决策」处理，不采取任何动作。</p>
 *
 * @param intent     意图答案，可为 null
 * @param escalation 升级倾向答案，可为 null
 * @param model      实际服务的模型版本
 * @param latencyMs  调用耗时
 */
public record TurnDecision(SystemOneAnswer.Choice intent, SystemOneAnswer.Score escalation,
                           String model, long latencyMs) {

    /** 意图选项中「无法归类」的编码：任何决策点都不据它采取动作。 */
    public static final String INTENT_OTHER = "other";

    /**
     * 两段式转人工的判定。
     *
     * @param topLevel 最高档编号（档数 - 1）
     */
    public EscalationAction escalationAction(TypeSafeProperties.Escalation cfg, int topLevel) {
        if (escalation == null) {
            return EscalationAction.NONE;
        }
        // score 是期望值、会落在两档之间，「是否处于最高档」只能看概率最大的档位
        if (escalation.mostLikelyLevel() == topLevel
            && escalation.confidence() >= cfg.getAutoHandoffMinConfidence()) {
            return EscalationAction.AUTO_HANDOFF;
        }
        if (escalation.score() >= cfg.getHintMinScore() && escalation.confidence() >= cfg.getHintMinConfidence()) {
            return EscalationAction.HINT;
        }
        return EscalationAction.NONE;
    }

    /** 本轮应保留的工具组；不满足收窄条件时返回 empty（不收窄）。 */
    public Optional<List<String>> scopedGroups(TypeSafeProperties.ToolScope cfg) {
        return routableIntent(cfg.getMinConfidence())
            .map(code -> cfg.getIntentGroups().get(code))
            .filter(groups -> !CollectionUtils.isEmpty(groups));
    }

    /** 达到置信度门槛且不是 other 的意图编码。 */
    public Optional<String> routableIntent(double minConfidence) {
        if (intent == null || INTENT_OTHER.equals(intent.choice()) || intent.confidence() < minConfidence) {
            return Optional.empty();
        }
        return Optional.of(intent.choice());
    }

    /** 从批量调用结果里取出本轮决策。 */
    static TurnDecision from(SystemOneResult result, String intentId, String escalationId) {
        return new TurnDecision(result.choice(intentId).orElse(null), result.score(escalationId).orElse(null),
            result.model(), result.latencyMs());
    }

    /** 两段式转人工的三种处置。 */
    public enum EscalationAction {
        /** 直接建工单转人工。 */
        AUTO_HANDOFF,
        /** 提示模型考虑转人工，最终由模型决定。 */
        HINT,
        /** 不处置。 */
        NONE
    }

    /** 供展示：意图各选项的描述，按编码取。 */
    public static String describeIntent(Map<String, String> criteria, String code) {
        String description = criteria.get(code);
        return description == null ? code : code + "（" + description + "）";
    }
}
