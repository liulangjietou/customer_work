package com.richard.fyoung.customerwork.capability.feedback;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户反馈服务单测：UP 不落 FactLog、DOWN 落 FactLog 供飞轮复盘、重复提交覆盖、按会话查询。
 * @author owlzhangfq@gmail.com
 */
class FeedbackServiceTest {

    private FeedbackService newService(Path tempDir) {
        InMemoryFeedbackStore store = new InMemoryFeedbackStore();
        FactLog factLog = new FactLog(true, tempDir);
        TenantResolver resolver = new TenantResolver(new CustomerWorkProperties());
        return new FeedbackService(store, factLog, resolver);
    }

    @Test
    void submitUp_shouldNotRecordFact(@TempDir Path tempDir) {
        FeedbackService svc = newService(tempDir);
        FactLog factLog = new FactLog(true, tempDir);

        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.UP, null);

        assertTrue(factLog.read("tenantA").isEmpty(), "点赞不应沉淀事实");
    }

    @Test
    void submitDown_shouldRecordFactForFlywheel(@TempDir Path tempDir) {
        FeedbackService svc = newService(tempDir);
        FactLog factLog = new FactLog(true, tempDir);

        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.DOWN, "答非所问");

        List<String> facts = factLog.read("tenantA");
        assertEquals(1, facts.size());
        assertTrue(facts.get(0).contains("negative-feedback"));
        assertTrue(facts.get(0).contains("答非所问"));
    }

    @Test
    void submit_shouldReturnAndPersistFeedback(@TempDir Path tempDir) {
        FeedbackService svc = newService(tempDir);

        MessageFeedback fb = svc.submit("s1", "MSG-1", FeedbackType.UP, null);
        assertEquals(FeedbackType.UP, fb.type());
        assertEquals(FeedbackType.UP, svc.find("MSG-1").orElseThrow().type());
    }

    @Test
    void submit_repeatedly_shouldOverwriteAndOnlyRecordLatestDown(@TempDir Path tempDir) {
        FeedbackService svc = newService(tempDir);
        FactLog factLog = new FactLog(true, tempDir);

        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.DOWN, "第一次");
        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.UP, null);   // 用户改主意了

        assertEquals(FeedbackType.UP, svc.find("MSG-1").orElseThrow().type());
        assertEquals(1, factLog.read("tenantA").size(), "只有 DOWN 才落事实，改成 UP 后不追加新事实");
    }

    @Test
    void findBySession_shouldDelegateToStore(@TempDir Path tempDir) {
        FeedbackService svc = newService(tempDir);
        svc.submit("s1", "MSG-1", FeedbackType.UP, null);
        svc.submit("s1", "MSG-2", FeedbackType.DOWN, "x");
        svc.submit("s2", "MSG-3", FeedbackType.UP, null);

        assertEquals(2, svc.findBySession("s1").size());
    }
}
