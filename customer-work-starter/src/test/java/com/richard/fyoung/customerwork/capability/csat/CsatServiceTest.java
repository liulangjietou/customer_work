package com.richard.fyoung.customerwork.capability.csat;

import com.richard.fyoung.customerwork.core.support.OpsScopeResolver;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSAT 单测。
 *
 * <p>重点在两处口径：满意按 4 分及以上算（不是平均分），以及邀请与回收分开记
 * （只记评分就算不出回收率，而没有回收率的 CSAT 是个会骗人的数字）。</p>
 * @author owlzhangfq@gmail.com
 */
class CsatServiceTest {

    private CsatStore store;
    private CsatService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryCsatStore();
        service = new CsatService(store, new OpsScopeResolver());
        // 分区取自租户上下文（运营口径），不再由 sessionId 前缀解析
        TenantContext.set("tenantA");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void invite_shouldCreatePendingSurvey() {
        CsatSurvey survey = service.invite("tenantA:sess-1");

        assertFalse(survey.answered(), "刚邀请时还没有评分");
        assertEquals("tenantA", survey.scopeId(), "分区取当前租户");
        assertTrue(survey.invitedAtMs() > 0);
    }

    @Test
    void invite_shouldBeIdempotent() {
        CsatSurvey first = service.invite("tenantA:sess-1");
        service.submit("tenantA:sess-1", 5, "很好");
        CsatSurvey again = service.invite("tenantA:sess-1");

        // 会话可能被多次结束（超时清理 + 用户主动关闭），重复邀请会把回收率的分母灌水
        assertEquals(first.invitedAtMs(), again.invitedAtMs(), "重复邀请不该刷新邀请时间");
        assertEquals(5, again.score(), "更不该清掉已有评分");
    }

    @Test
    void submit_shouldRecordScore() {
        service.invite("tenantA:sess-1");

        CsatSurvey answered = service.submit("tenantA:sess-1", 5, "解决得很快");

        assertTrue(answered.answered());
        assertTrue(answered.satisfied());
        assertEquals("解决得很快", answered.comment());
        assertTrue(answered.submittedAtMs() > 0);
    }

    @Test
    void submitWithoutInvite_shouldStillBeAccepted() {
        // 用户主动评价是好事，不该因为"没请你评"而拒收
        CsatSurvey answered = service.submit("tenantA:sess-9", 4, null);

        assertTrue(answered.answered());
        assertTrue(store.find("tenantA:sess-9").isPresent());
    }

    @Test
    void repeatedSubmit_shouldOverwrite() {
        service.submit("tenantA:sess-1", 2, "不满意");
        CsatSurvey updated = service.submit("tenantA:sess-1", 5, "客服后来解决了");

        assertEquals(5, updated.score(), "用户改主意允许更正");
    }

    @Test
    void outOfRangeScore_shouldFailFast() {
        // 越界分数会污染 CSAT 统计，必须挡在入口
        assertThrows(IllegalArgumentException.class, () -> service.submit("tenantA:sess-1", 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.submit("tenantA:sess-1", 6, null));
    }

    @Test
    void satisfied_shouldBeFourAndAbove() {
        assertFalse(service.submit("tenantA:s1", 3, null).satisfied(), "3 分是无感，不算满意");
        assertTrue(service.submit("tenantA:s2", 4, null).satisfied());
        assertTrue(service.submit("tenantA:s3", 5, null).satisfied());
    }

    @Test
    void scope_shouldFallBackToDefaultWithoutTenantContext() {
        // 未开多租户时全部数据落 default 分区——看板默认值即可直接看到，
        // 这也是"单租户系统"下唯一说得通的口径
        TenantContext.clear();

        CsatSurvey survey = service.invite("u42:conv-abc");

        assertEquals(TenantContext.DEFAULT, survey.scopeId());
    }

    @Test
    void scope_shouldIgnoreSessionIdPrefix() {
        // 早期按 sessionId 前缀分区，而用户端 sessionId 形如 u{userId}:conv-xxx，
        // 于是每个用户各成一个分区，运营看板按任何口径都查不出数据
        CsatSurvey survey = service.invite("u42:conv-abc");

        assertEquals("tenantA", survey.scopeId(), "分区由租户决定，与 sessionId 前缀无关");
    }

    @Test
    void summary_shouldUseIndustryCsatFormula() {
        service.submit("tenantA:s1", 5, null);
        service.submit("tenantA:s2", 4, null);
        service.submit("tenantA:s3", 3, null);
        service.submit("tenantA:s4", 1, null);

        CsatSummary summary = service.summary("tenantA", 0L, Long.MAX_VALUE);

        assertEquals(4, summary.answered());
        assertEquals(2, summary.satisfied());
        // CSAT 是满意率而非平均分：平均分 3.25 看着还行，但实际只有一半人满意
        assertEquals(0.5d, summary.csat(), 1e-9);
        assertEquals(3.25d, summary.averageScore(), 1e-9);
    }

    @Test
    void summary_shouldExposeResponseRate() {
        service.invite("tenantA:s1");
        service.invite("tenantA:s2");
        service.invite("tenantA:s3");
        service.submit("tenantA:s1", 5, null);

        CsatSummary summary = service.summary("tenantA", 0L, Long.MAX_VALUE);

        assertEquals(3, summary.invited());
        assertEquals(1, summary.answered());
        // 回收率 33%，此时那个 100% 的 CSAT 只代表 3 个人里的 1 个
        assertEquals(1.0d, summary.csat(), 1e-9);
        assertEquals(1.0d / 3, summary.responseRate(), 1e-9);
    }

    @Test
    void summary_shouldIsolateScopes() {
        service.submit("tenantA:s1", 5, null);
        TenantContext.runWith("tenantB", () -> service.submit("tenantB:s1", 1, null));

        assertEquals(1, service.summary("tenantA", 0L, Long.MAX_VALUE).answered());
        assertEquals(1.0d, service.summary("tenantA", 0L, Long.MAX_VALUE).csat(), 1e-9);
        assertEquals(0.0d, service.summary("tenantB", 0L, Long.MAX_VALUE).csat(), 1e-9);
    }

    @Test
    void summary_withNoData_shouldNotDivideByZero() {
        CsatSummary summary = service.summary("tenantA", 0L, Long.MAX_VALUE);

        assertEquals(0.0d, summary.csat());
        assertEquals(0.0d, summary.responseRate());
        assertEquals(0.0d, summary.averageScore());
    }

    @Test
    void summary_shouldRespectWindow() {
        service.submit("tenantA:s1", 5, null);

        // 窗口以邀请时间为准，未来的窗口自然查不到
        assertEquals(0, service.summary("tenantA", Long.MAX_VALUE - 1, Long.MAX_VALUE).invited());
    }
}
