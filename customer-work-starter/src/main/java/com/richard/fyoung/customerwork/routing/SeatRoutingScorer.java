package com.richard.fyoung.customerwork.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 坐席路由打分器（确定性、纯函数、可测、可解释）。
 *
 * <p>对每个候选坐席打分：{@code score = 技能匹配 × 负载空闲度 × 优先级 × 在线状态}，返回 top-N 候选及每个的
 * 打分理由。离线坐席直接排除（{@code 在线状态} 因子为 0）；满负载坐席降权保留（极端下全员满载仍能给出兜底推荐）。
 * 打分不含任何 LLM/随机，同一输入恒定同一输出——供单测精确断言、供坐席工作台展示可信理由。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SeatRoutingScorer {

    /** 命中所需技能的因子。 */
    private static final double SKILL_MATCH = 1.0;
    /** 工单无硬性技能要求时的中性因子。 */
    private static final double SKILL_NEUTRAL = 0.6;
    /** 未命中所需技能的因子（不为 0：技能不匹配仍可兜底接单，只是排后）。 */
    private static final double SKILL_MISS = 0.3;
    /** 满负载坐席的负载因子（降权保留，避免全员满载时无任何推荐）。 */
    private static final double LOAD_OVERLOADED = 0.1;
    /** maxLoad 未配置（<=0）时的负载因子（信息缺失，取中性）。 */
    private static final double LOAD_UNKNOWN = 0.5;
    private static final int SCORE_SCALE = 10_000;

    /**
     * 对候选坐席打分并取 top-N。
     *
     * @param classification 工单分类（提供所需技能与优先级）
     * @param candidates     候选坐席全集
     * @param topN           返回的最大推荐数（&lt;=0 视为 1）
     * @return 按分数降序、同分按 seatId 升序的推荐列表（离线坐席已排除）；无在线坐席时返回空列表
     */
    public List<SeatRecommendation> score(TicketClassification classification,
                                          List<SeatAgent> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String requiredSkill = classification == null ? null : classification.requiredSkill();
        double priorityFactor = priorityFactor(classification);
        int limit = Math.max(1, topN);

        List<SeatRecommendation> scored = new ArrayList<>();
        for (SeatAgent seat : candidates) {
            if (seat == null || !seat.isOnline()) {
                // 在线状态因子为 0：离线坐席直接排除
                continue;
            }
            boolean matched = seat.hasSkill(requiredSkill);
            double skillFactor = skillFactor(requiredSkill, matched);
            double loadFactor = loadFactor(seat);
            double raw = skillFactor * loadFactor * priorityFactor;
            double rounded = Math.round(raw * SCORE_SCALE) / (double) SCORE_SCALE;
            scored.add(new SeatRecommendation(seat.getId(), seat.getName(), seat.getGroup(), rounded,
                matched, seat.getCurrentLoad(), seat.getMaxLoad(),
                explain(requiredSkill, matched, skillFactor, seat, loadFactor, classification, priorityFactor)));
        }
        return scored.stream()
            .sorted(Comparator.comparingDouble(SeatRecommendation::score).reversed()
                .thenComparing(r -> r.seatId() == null ? "" : r.seatId()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    private double skillFactor(String requiredSkill, boolean matched) {
        if (requiredSkill == null || requiredSkill.trim().isEmpty()) {
            return SKILL_NEUTRAL;
        }
        return matched ? SKILL_MATCH : SKILL_MISS;
    }

    /** 负载空闲度：越空闲越高（空闲工位/最大工位）；满负载降权、未配置取中性。 */
    private double loadFactor(SeatAgent seat) {
        if (seat.getMaxLoad() <= 0) {
            return LOAD_UNKNOWN;
        }
        if (seat.isOverloaded()) {
            return LOAD_OVERLOADED;
        }
        int idle = seat.getMaxLoad() - seat.getCurrentLoad();
        return (double) idle / seat.getMaxLoad();
    }

    private double priorityFactor(TicketClassification classification) {
        TicketPriority priority = classification == null || classification.priority() == null
            ? TicketPriority.MEDIUM : classification.priority();
        return (double) priority.weight() / TicketPriority.MAX_WEIGHT;
    }

    /** 生成人读的打分理由（技能/负载/优先级/在线四因子逐项说明）。 */
    private String explain(String requiredSkill, boolean matched, double skillFactor, SeatAgent seat,
                           double loadFactor, TicketClassification classification, double priorityFactor) {
        StringBuilder sb = new StringBuilder();
        if (requiredSkill == null || requiredSkill.trim().isEmpty()) {
            sb.append("无硬性技能要求(中性").append(fmt(skillFactor)).append(")");
        } else if (matched) {
            sb.append("技能匹配[").append(requiredSkill).append("](").append(fmt(skillFactor)).append(")");
        } else {
            sb.append("技能不匹配[需").append(requiredSkill).append("](").append(fmt(skillFactor)).append(")");
        }
        sb.append(" × 负载").append(seat.getCurrentLoad()).append("/").append(seat.getMaxLoad())
            .append(seat.isOverloaded() ? "(满载" : "(空闲").append(fmt(loadFactor)).append(")");
        TicketPriority priority = classification == null || classification.priority() == null
            ? TicketPriority.MEDIUM : classification.priority();
        sb.append(" × 优先级").append(priority.name()).append("(").append(fmt(priorityFactor)).append(")");
        sb.append(" × 在线");
        return sb.toString();
    }

    private String fmt(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}
