package com.richard.fyoung.customerwork.capability.feedback;

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
 * 用户反馈服务单测：UP 不落 FactLog、DOWN 落 FactLog 供飞轮复盘、重复提交覆盖、按会话查询，
 * 以及 DOWN 同时登记进 badcase 待筛队列。
 * @author owlzhangfq@gmail.com
 */
class FeedbackServiceTest {

    private final BadcaseStore badcaseStore = new InMemoryBadcaseStore();

    /** 事实日志实例必须与服务共用一份——此前用落盘实现时靠同一个 tempDir 隐式共享，换内存替身后要显式传入。 */
    private FeedbackService newService(FactLog factLog) {
        InMemoryFeedbackStore store = new InMemoryFeedbackStore();
        TenantResolver resolver = new TenantResolver(new CustomerWorkProperties());
        return new FeedbackService(store, factLog, resolver, new CustomerWorkProperties(),
            providerOf(newBadcaseService()));
    }

    /** 聊天留痕与知识库都不可用：此时 badcase 仍应被登记下来，只是缺少对话上下文。 */
    private BadcaseService newBadcaseService() {
        return new BadcaseService(badcaseStore, new InMemoryEvalCaseStore(), null, null);
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
    void submitUp_shouldNotRecordFact(@TempDir Path tempDir) {
        FactLog factLog = new InMemoryTestFactLog();
        FeedbackService svc = newService(factLog);

        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.UP, null);

        assertTrue(factLog.read("tenantA").isEmpty(), "点赞不应沉淀事实");
    }

    @Test
    void submitDown_shouldRecordFactForFlywheel(@TempDir Path tempDir) {
        FactLog factLog = new InMemoryTestFactLog();
        FeedbackService svc = newService(factLog);

        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.DOWN, "答非所问");

        List<String> facts = factLog.read("tenantA");
        assertEquals(1, facts.size());
        assertTrue(facts.get(0).contains("negative-feedback"));
        assertTrue(facts.get(0).contains("答非所问"));
    }

    @Test
    void submit_shouldReturnAndPersistFeedback(@TempDir Path tempDir) {
        FeedbackService svc = newService(new InMemoryTestFactLog());

        MessageFeedback fb = svc.submit("s1", "MSG-1", FeedbackType.UP, null);
        assertEquals(FeedbackType.UP, fb.type());
        assertEquals(FeedbackType.UP, svc.find("MSG-1").orElseThrow().type());
    }

    @Test
    void submit_repeatedly_shouldOverwriteAndOnlyRecordLatestDown(@TempDir Path tempDir) {
        FactLog factLog = new InMemoryTestFactLog();
        FeedbackService svc = newService(factLog);

        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.DOWN, "第一次");
        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.UP, null);   // 用户改主意了

        assertEquals(FeedbackType.UP, svc.find("MSG-1").orElseThrow().type());
        assertEquals(1, factLog.read("tenantA").size(), "只有 DOWN 才落事实，改成 UP 后不追加新事实");
    }

    @Test
    void submitDown_shouldEnqueueBadcaseForReview() {
        FeedbackService svc = newService(new InMemoryTestFactLog());

        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.DOWN, "答非所问");

        List<Badcase> pending = badcaseStore.query(BadcaseQuery.pending(0, 10));
        assertEquals(1, pending.size(), "点踩应进入待筛队列，事实流水之外还要有可流转的工作项");
        assertEquals(BadcaseSource.NEGATIVE_FEEDBACK, pending.get(0).getSource());
        assertEquals("MSG-1", pending.get(0).getMessageId());
        assertEquals("答非所问", pending.get(0).getDetail());
    }

    @Test
    void submitUp_shouldNotEnqueueBadcase() {
        FeedbackService svc = newService(new InMemoryTestFactLog());

        svc.submit("tenantA:sess-1", "MSG-1", FeedbackType.UP, null);

        assertTrue(badcaseStore.query(BadcaseQuery.pending(0, 10)).isEmpty(), "点赞不是 badcase");
    }

    @Test
    void findBySession_shouldDelegateToStore(@TempDir Path tempDir) {
        FeedbackService svc = newService(new InMemoryTestFactLog());
        svc.submit("s1", "MSG-1", FeedbackType.UP, null);
        svc.submit("s1", "MSG-2", FeedbackType.DOWN, "x");
        svc.submit("s2", "MSG-3", FeedbackType.UP, null);

        assertEquals(2, svc.findBySession("s1").size());
    }
}
