package com.richard.fyoung.customerwork.routing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 坐席推荐项（HITL 推荐：给坐席工作台展示的候选坐席 + 打分 + 可解释理由）。
 *
 * <p>由 {@link SeatRoutingScorer} 产出并以 JSON 落到 {@code cw_handoff_ticket.suggested_assignees}；坐席
 * <b>人工点选</b>后仍复用现有 claim 流程真正接单——<b>不自动派单</b>。JSON ↔ 对象转换收敛在本类静态方法，
 * 解析失败降级空集合（fail-open：无推荐、坐席照常手动接单）。</p>
 *
 * @param seatId       坐席 ID
 * @param seatName     坐席名
 * @param seatGroup    坐席分组
 * @param score        打分（越高越推荐，保留 4 位小数）
 * @param matchedSkill 是否命中工单所需技能
 * @param currentLoad  当前负载
 * @param maxLoad      最大负载
 * @param reason       可解释的打分理由
 * @author owlzhangfq@gmail.com
 */
public record SeatRecommendation(String seatId, String seatName, String seatGroup, double score,
                                 boolean matchedSkill, int currentLoad, int maxLoad, String reason) {

    private static final Logger log = LoggerFactory.getLogger(SeatRecommendation.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<SeatRecommendation>> LIST_TYPE = new TypeReference<>() {
    };

    /** 序列化为 JSON 字符串（落库用）；失败返回 {@code null}（fail-open，不写推荐）。 */
    public static String toJson(List<SeatRecommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(recommendations);
        } catch (Exception e) {
            log.error("[SeatRecommendation] serialize failed, code={}", "SEAT-RECOMMEND-SERIALIZE-FAIL", e);
            return null;
        }
    }

    /** 从 JSON 字符串反序列化（读库/端点展示用）；空/非法一律降级空集合。 */
    public static List<SeatRecommendation> parseList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return List.of();
        }
        try {
            List<SeatRecommendation> list = MAPPER.readValue(json, LIST_TYPE);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.error("[SeatRecommendation] parse failed, code={}", "SEAT-RECOMMEND-PARSE-FAIL", e);
            return List.of();
        }
    }
}
