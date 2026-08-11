package com.richard.fyoung.customerwork.capability.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐席打分器单测：确定性纯函数，精确断言排序、离线排除、负载排序、topN、空输入与同分 tie-break。
 * @author owlzhangfq@gmail.com
 */
class SeatRoutingScorerTest {

    private final SeatRoutingScorer scorer = new SeatRoutingScorer();

    private SeatAgent seat(String id, Set<String> skills, int max, int cur, boolean online) {
        return new SeatAgent(id, "name-" + id, skills, max, cur, online, "g");
    }

    private TicketClassification cls(String skill, TicketPriority p) {
        return new TicketClassification("退款", skill, p, "不满");
    }

    @Test
    void score_shouldRankSkillMatchAboveMiss() {
        SeatAgent match = seat("A", Set.of("refund"), 5, 2, true);
        SeatAgent miss = seat("B", Set.of("logistics"), 5, 0, true);
        List<SeatRecommendation> recs = scorer.score(cls("refund", TicketPriority.HIGH), List.of(miss, match), 3);

        assertEquals("A", recs.get(0).seatId(), "技能匹配应排第一");
        assertTrue(recs.get(0).matchedSkill());
        assertTrue(recs.get(0).score() > recs.get(1).score());
    }

    @Test
    void score_shouldExcludeOfflineSeats() {
        SeatAgent offline = seat("OFF", Set.of("refund"), 5, 0, false);
        SeatAgent online = seat("ON", Set.of("refund"), 5, 4, true);
        List<SeatRecommendation> recs = scorer.score(cls("refund", TicketPriority.MEDIUM), List.of(offline, online), 3);

        assertEquals(1, recs.size());
        assertEquals("ON", recs.get(0).seatId());
    }

    @Test
    void score_shouldPreferIdlerSeat_whenSkillEqual() {
        SeatAgent busy = seat("BUSY", Set.of("refund"), 5, 4, true);
        SeatAgent idle = seat("IDLE", Set.of("refund"), 5, 1, true);
        List<SeatRecommendation> recs = scorer.score(cls("refund", TicketPriority.MEDIUM), List.of(busy, idle), 3);

        assertEquals("IDLE", recs.get(0).seatId(), "同技能时更空闲的坐席应排前");
    }

    @Test
    void score_shouldRespectTopN() {
        List<SeatAgent> seats = List.of(
            seat("A", Set.of("refund"), 5, 0, true),
            seat("B", Set.of("refund"), 5, 1, true),
            seat("C", Set.of("refund"), 5, 2, true));
        assertEquals(2, scorer.score(cls("refund", TicketPriority.LOW), seats, 2).size());
    }

    @Test
    void score_shouldReturnEmpty_whenNoCandidatesOrAllOffline() {
        assertTrue(scorer.score(cls("refund", TicketPriority.HIGH), List.of(), 3).isEmpty());
        assertTrue(scorer.score(cls("refund", TicketPriority.HIGH),
            List.of(seat("X", Set.of("refund"), 5, 0, false)), 3).isEmpty());
    }

    @Test
    void score_shouldBeDeterministic_withSeatIdTieBreak() {
        // 完全同质坐席（同技能同负载）→ 同分，按 seatId 升序稳定排序
        SeatAgent b = seat("B", Set.of("refund"), 5, 1, true);
        SeatAgent a = seat("A", Set.of("refund"), 5, 1, true);
        List<SeatRecommendation> recs = scorer.score(cls("refund", TicketPriority.MEDIUM), List.of(b, a), 3);
        assertEquals("A", recs.get(0).seatId());
        assertEquals("B", recs.get(1).seatId());
    }

    @Test
    void score_shouldUseNeutralSkillFactor_whenNoRequiredSkill() {
        SeatAgent s = seat("A", Set.of("logistics"), 5, 0, true);
        List<SeatRecommendation> recs = scorer.score(cls(null, TicketPriority.MEDIUM), List.of(s), 3);
        assertFalse(recs.get(0).matchedSkill());
        assertTrue(recs.get(0).score() > 0);
        assertTrue(recs.get(0).reason().contains("无硬性技能要求"));
    }
}
