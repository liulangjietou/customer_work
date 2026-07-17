package com.richard.fyoung.customerwork.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话质检单测（离线确定性，规则打分）。
 * @author owlzhangfq@gmail.com
 */
class QualityInspectionServiceTest {

    private final QualityInspectionService svc = new QualityInspectionService();

    @Test
    void compliantReplies_shouldPass() {
        QualityReport r = svc.inspect(List.of(
            "已为您查询订单，预计 1-3 个工作日到账。",
            "退款工单已生成，需人工坐席复核后处理。"));
        assertTrue(r.isPassed());
        assertTrue(r.getScore() >= 60);
    }

    @Test
    void fundViolation_shouldFailRegardlessOfScore() {
        QualityReport r = svc.inspect(List.of("您放心，钱已打款马上到账。"));
        assertFalse(r.isPassed(), "资金违规承诺必须判不通过");
        assertFalse(r.getIssues().isEmpty());
    }

    @Test
    void forbiddenAndAbsoluteWords_shouldDeductScore() {
        QualityReport r = svc.inspect(List.of("这个我也不知道", "绝对没问题"));
        assertTrue(r.getScore() < 100);
        assertFalse(r.getIssues().isEmpty());
    }
}
