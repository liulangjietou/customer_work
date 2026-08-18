package com.richard.fyoung.customerwork.safety.subjectquota;

import com.richard.fyoung.customerwork.infra.config.properties.SubjectQuotaProperties;
import com.richard.fyoung.customerwork.infra.counter.InMemoryWindowCounter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主体配额判定单测：关闭放行、无等级放行、两个维度各自触顶、WARN 不拦、被拒不占额度、用量快照。
 * @author owlzhangfq@gmail.com
 */
class SubjectQuotaGuardTest {

    private static final String TENANT = "acme";

    private final InMemorySubjectQuotaLevelStore levelStore = new InMemorySubjectQuotaLevelStore();
    private final InMemorySubjectQuotaHitStore hitStore = new InMemorySubjectQuotaHitStore();
    private final InMemoryWindowCounter counter = new InMemoryWindowCounter();
    private final SubjectQuotaProperties properties = new SubjectQuotaProperties();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        QuotaSubjectContext.clear();
    }

    private SubjectQuotaGuard guard(boolean enabled) {
        // 内置档清空：本类要测的是库里配的等级，留着出厂档会让"没配等级"这个用例失去意义
        properties.setBuiltinLevels(null);
        SubjectQuotaLevelProvider provider = new SubjectQuotaLevelProvider(levelStore, false);
        SubjectLevelResolver resolver = new SubjectLevelResolver(provider, userId -> Optional.of("gold"), properties);
        return new SubjectQuotaGuard(resolver, counter, hitStore, enabled);
    }

    private void saveLevel(long tokenLimit, int requestLimit, SubjectExceedAction action) {
        levelStore.save(new SubjectQuotaLevel(null, TENANT, "gold", "金卡", QuotaSubjectType.USER,
            1800, tokenLimit, requestLimit, action, true, null));
    }

    @Test
    void check_shouldAllow_whenDisabled() {
        TenantContext.set(TENANT);
        saveLevel(10, 1, SubjectExceedAction.BLOCK);
        SubjectQuotaGuard g = guard(false);
        g.recordRequest(QuotaSubject.user("U-1"));
        g.recordTokens(QuotaSubject.user("U-1"), 999);

        assertTrue(g.check(QuotaSubject.user("U-1"), "/api/customer/chat").allowed(),
            "功能关闭时一律放行，且不记账");
    }

    @Test
    void check_shouldAllow_whenNoLevelConfigured() {
        TenantContext.set(TENANT);
        assertTrue(guard(true).check(QuotaSubject.user("U-1"), "/x").allowed(), "查不到等级等于不受限");
    }

    @Test
    void check_shouldAllow_whenSubjectMissing() {
        TenantContext.set(TENANT);
        saveLevel(10, 1, SubjectExceedAction.BLOCK);
        assertTrue(guard(true).check(null, "/x").allowed(), "无主体（内部任务）应放行而不是报错");
    }

    @Test
    void check_shouldBlock_whenRequestLimitReached() {
        TenantContext.set(TENANT);
        saveLevel(0, 2, SubjectExceedAction.BLOCK);
        SubjectQuotaGuard g = guard(true);
        QuotaSubject subject = QuotaSubject.user("U-1");

        g.recordRequest(subject);
        assertTrue(g.check(subject, "/x").allowed(), "第 2 次请求仍在额度内");
        g.recordRequest(subject);

        SubjectQuotaDecision decision = g.check(subject, "/x");
        assertFalse(decision.allowed(), "达到次数上限应拒绝");
        assertTrue(decision.shouldBlock());
        assertEquals(SubjectQuotaDecision.LimitKind.REQUEST, decision.kind());
        assertEquals(1800, decision.retryAfterSeconds(), "重试建议给的是窗口长度这个上界");
    }

    @Test
    void check_shouldBlock_whenTokenLimitReached() {
        TenantContext.set(TENANT);
        saveLevel(100, 0, SubjectExceedAction.BLOCK);
        SubjectQuotaGuard g = guard(true);
        QuotaSubject subject = QuotaSubject.user("U-1");

        g.recordTokens(subject, 99);
        assertTrue(g.check(subject, "/x").allowed(), "未达上限放行");
        g.recordTokens(subject, 1);

        SubjectQuotaDecision decision = g.check(subject, "/x");
        assertFalse(decision.allowed());
        assertEquals(SubjectQuotaDecision.LimitKind.TOKEN, decision.kind());
        assertEquals(100L, decision.used());
    }

    @Test
    void check_shouldReportRequestKind_whenBothLimitsReached() {
        TenantContext.set(TENANT);
        saveLevel(10, 1, SubjectExceedAction.BLOCK);
        SubjectQuotaGuard g = guard(true);
        QuotaSubject subject = QuotaSubject.user("U-1");
        g.recordRequest(subject);
        g.recordTokens(subject, 50);

        // 两个维度都超时报次数：那更贴近用户刚才做的事（"你问得太频繁"比"额度用完了"好理解）
        assertEquals(SubjectQuotaDecision.LimitKind.REQUEST, g.check(subject, "/x").kind());
    }

    @Test
    void check_shouldNotBlock_whenActionIsWarn() {
        TenantContext.set(TENANT);
        saveLevel(0, 1, SubjectExceedAction.WARN);
        SubjectQuotaGuard g = guard(true);
        QuotaSubject subject = QuotaSubject.user("U-1");
        g.recordRequest(subject);

        SubjectQuotaDecision decision = g.check(subject, "/x");
        assertFalse(decision.allowed(), "WARN 档同样判定为超限");
        assertFalse(decision.shouldBlock(), "但不拦——观察期就是靠这个区分成立的");
    }

    @Test
    void check_shouldNotConsumeQuota_whenRejected() {
        TenantContext.set(TENANT);
        saveLevel(0, 1, SubjectExceedAction.BLOCK);
        SubjectQuotaGuard g = guard(true);
        QuotaSubject subject = QuotaSubject.user("U-1");
        g.recordRequest(subject);

        g.check(subject, "/x");
        g.check(subject, "/x");

        // 判定只读、记账在放行之后，所以被拒的请求不会把窗口越推越远
        assertEquals(1L, g.usage(subject).requestUsed(), "被拒绝的请求不得计入用量");
    }

    @Test
    void usage_shouldReportRemaining() {
        TenantContext.set(TENANT);
        saveLevel(1000, 10, SubjectExceedAction.BLOCK);
        SubjectQuotaGuard g = guard(true);
        QuotaSubject subject = QuotaSubject.user("U-1");
        g.recordRequest(subject);
        g.recordTokens(subject, 200);

        SubjectQuotaUsage usage = g.usage(subject);
        assertEquals("gold", usage.levelCode());
        assertEquals(200L, usage.tokenUsed());
        assertEquals(800L, usage.tokenRemaining());
        assertEquals(9L, usage.requestRemaining());
    }

    @Test
    void usage_shouldReportUnlimited_whenNoLevel() {
        TenantContext.set(TENANT);
        SubjectQuotaUsage usage = guard(true).usage(QuotaSubject.user("U-1"));
        assertNull(usage.levelCode());
        assertEquals(-1L, usage.tokenRemaining(), "不限时返回 -1，与'还剩 0'区分开");
    }

    @Test
    void check_shouldNotShareQuota_acrossSubjects() {
        TenantContext.set(TENANT);
        saveLevel(0, 1, SubjectExceedAction.BLOCK);
        SubjectQuotaGuard g = guard(true);
        g.recordRequest(QuotaSubject.user("U-1"));

        assertTrue(g.check(QuotaSubject.user("U-2"), "/x").allowed(), "额度按主体独立，不得互相挤占");
    }

    /** 轮询等待异步落库；超时即返回当前值，让断言给出真实差异而不是一句超时。 */
    private int awaitHitCount(int expected) {
        long deadline = System.currentTimeMillis() + 2000L;
        int size = hitStore.findRecent(TENANT, 0L, 10).size();
        while (size < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            size = hitStore.findRecent(TENANT, 0L, 10).size();
        }
        return size;
    }

    @Test
    void check_shouldRecordHit_whenExceeded() {
        TenantContext.set(TENANT);
        saveLevel(0, 1, SubjectExceedAction.BLOCK);
        SubjectQuotaGuard g = guard(true);
        QuotaSubject subject = QuotaSubject.user("U-1");
        g.recordRequest(subject);
        g.check(subject, "/api/customer/chat");

        // 命中落库是异步提交的（判定在响应式链路上，阻塞 IO 不能占事件循环线程），故这里要等一等
        assertEquals(1, awaitHitCount(1), "超限应留下一条命中记录");
    }
}
