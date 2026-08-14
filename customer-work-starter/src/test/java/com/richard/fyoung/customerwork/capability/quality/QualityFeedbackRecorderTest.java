package com.richard.fyoung.customerwork.capability.quality;

import com.richard.fyoung.customerwork.capability.badcase.Badcase;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseQuery;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseService;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseSource;
import com.richard.fyoung.customerwork.capability.badcase.BadcaseStore;
import com.richard.fyoung.customerwork.capability.badcase.InMemoryBadcaseStore;
import com.richard.fyoung.customerwork.capability.eval.InMemoryEvalCaseStore;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.support.InMemoryTestFactLog;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 质检失败反馈记录器单测（数据飞轮）：不通过时落 FactLog、同时进 badcase 待筛队列，
 * 通过时两者都不落，租户按 sessionId 解析。
 * @author owlzhangfq@gmail.com
 */
class QualityFeedbackRecorderTest {

    private final BadcaseStore badcaseStore = new InMemoryBadcaseStore();

    /** 事实日志实例必须与被测对象共用一份——此前用落盘实现时靠同一个 tempDir 隐式共享。 */
    private QualityFeedbackRecorder newRecorder(FactLog factLog) {
        BadcaseService badcaseService = new BadcaseService(badcaseStore, new InMemoryEvalCaseStore(),
            null, null);
        return new QualityFeedbackRecorder(new QualityInspectionService(), factLog,
            new TenantResolver(new CustomerWorkProperties()), new CustomerWorkProperties(),
            providerOf(badcaseService));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absentProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void inspectAndRecord_shouldEnqueueBadcase_whenNotPassed() {
        QualityFeedbackRecorder recorder = newRecorder(new InMemoryTestFactLog());

        recorder.inspectAndRecord("tenantA:sess-1", List.of("您放心，钱已打款马上到账。"));

        List<Badcase> pending = badcaseStore.query(BadcaseQuery.pending(0, 10));
        assertEquals(1, pending.size(), "质检不过应进入待筛队列");
        assertEquals(BadcaseSource.QUALITY_FAILURE, pending.get(0).getSource());
        assertTrue(pending.get(0).getDetail().contains("score="), "明细要带上得分与扣分项供运营判断是否误报");
    }

    @Test
    void inspectAndRecord_shouldRecordFact_whenNotPassed(@TempDir Path tempDir) {
        FactLog factLog = new InMemoryTestFactLog();
        QualityFeedbackRecorder recorder = newRecorder(factLog);

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
        FactLog factLog = new InMemoryTestFactLog();
        QualityFeedbackRecorder recorder = newRecorder(factLog);

        QualityReport report = recorder.inspectAndRecord("tenantA:sess-2",
            List.of("已为您查询订单，预计 1-3 个工作日到账。"));

        assertTrue(report.isPassed());
        assertEquals(0, factLog.read("tenantA").size(), "质检通过不应记录事实");
    }

    @Test
    void inspectAndRecord_shouldDefaultTenant_whenSessionIdHasNoDelimiter(@TempDir Path tempDir) {
        FactLog factLog = new InMemoryTestFactLog();
        QualityFeedbackRecorder recorder = newRecorder(factLog);

        recorder.inspectAndRecord("no-delimiter-session", List.of("这个我也不知道，绝对没问题，你放心钱已打款。"));

        // 无分隔符时整个 sessionId 作为租户
        assertEquals(1, factLog.read("no-delimiter-session").size());
    }
}
