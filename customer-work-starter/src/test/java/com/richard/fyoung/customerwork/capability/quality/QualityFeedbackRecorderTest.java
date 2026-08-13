package com.richard.fyoung.customerwork.capability.quality;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.memory.FileFactLog;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质检失败反馈记录器单测（数据飞轮）：不通过时落 FactLog，通过时不落，租户按 sessionId 解析。
 * @author owlzhangfq@gmail.com
 */
class QualityFeedbackRecorderTest {

    private QualityFeedbackRecorder newRecorder(Path tempDir) {
        FactLog factLog = new FileFactLog(true, tempDir);
        return new QualityFeedbackRecorder(new QualityInspectionService(), factLog,
            new TenantResolver(new CustomerWorkProperties()));
    }

    @Test
    void inspectAndRecord_shouldRecordFact_whenNotPassed(@TempDir Path tempDir) {
        QualityFeedbackRecorder recorder = newRecorder(tempDir);
        FactLog factLog = new FileFactLog(true, tempDir);

        QualityReport report = recorder.inspectAndRecord("tenantA:sess-1",
            List.of("您放心，钱已打款马上到账。"));

        assertTrue(!report.isPassed(), "资金违规承诺应判不通过");
        // 按租户读回：sessionId "tenantA:sess-1" 应解析出租户 "tenantA"
        List<String> facts = factLog.read("tenantA");
        assertEquals(1, facts.size(), "质检不通过应记录 1 条事实");
        assertTrue(facts.get(0).contains("quality-failure"));
        assertTrue(facts.get(0).contains("tenantA:sess-1"));
    }

    @Test
    void inspectAndRecord_shouldNotRecord_whenPassed(@TempDir Path tempDir) {
        QualityFeedbackRecorder recorder = newRecorder(tempDir);
        FactLog factLog = new FileFactLog(true, tempDir);

        QualityReport report = recorder.inspectAndRecord("tenantA:sess-2",
            List.of("已为您查询订单，预计 1-3 个工作日到账。"));

        assertTrue(report.isPassed());
        assertEquals(0, factLog.read("tenantA").size(), "质检通过不应记录事实");
    }

    @Test
    void inspectAndRecord_shouldDefaultTenant_whenSessionIdHasNoDelimiter(@TempDir Path tempDir) {
        QualityFeedbackRecorder recorder = newRecorder(tempDir);
        FactLog factLog = new FileFactLog(true, tempDir);

        recorder.inspectAndRecord("no-delimiter-session", List.of("这个我也不知道，绝对没问题，你放心钱已打款。"));

        // 无分隔符时整个 sessionId 作为租户
        assertEquals(1, factLog.read("no-delimiter-session").size());
    }
}
