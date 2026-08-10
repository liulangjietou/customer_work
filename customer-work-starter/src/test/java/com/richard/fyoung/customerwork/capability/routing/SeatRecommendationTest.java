package com.richard.fyoung.customerwork.capability.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐席推荐 JSON 序列化/解析单测：round-trip + 空/非法降级空集合（fail-open）。
 * @author owlzhangfq@gmail.com
 */
class SeatRecommendationTest {

    @Test
    void toJsonAndParse_shouldRoundTrip() {
        List<SeatRecommendation> recs = List.of(
            new SeatRecommendation("S1", "小赵", "aftersales", 0.72, true, 1, 5, "技能匹配 × 负载1/5 × 在线"));
        String json = SeatRecommendation.toJson(recs);
        assertTrue(json != null && json.contains("S1"));

        List<SeatRecommendation> parsed = SeatRecommendation.parseList(json);
        assertEquals(1, parsed.size());
        assertEquals("S1", parsed.get(0).seatId());
        assertEquals(0.72, parsed.get(0).score());
        assertTrue(parsed.get(0).matchedSkill());
    }

    @Test
    void toJson_shouldReturnNull_forEmpty() {
        assertNull(SeatRecommendation.toJson(List.of()));
        assertNull(SeatRecommendation.toJson(null));
    }

    @Test
    void parseList_shouldDegradeToEmpty_forBlankOrInvalid() {
        assertTrue(SeatRecommendation.parseList(null).isEmpty());
        assertTrue(SeatRecommendation.parseList("").isEmpty());
        assertTrue(SeatRecommendation.parseList("not-json").isEmpty());
    }
}
