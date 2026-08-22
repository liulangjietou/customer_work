package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 长期记忆主体解析边界测试。 */
class MemorySubjectResolverTest {

    private final MemorySubjectResolver resolver = new MemorySubjectResolver();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        QuotaSubjectContext.clear();
    }

    @Test
    void userSession_shouldResolveVerifiedTenantAndUserSubject() {
        TenantContext.set("tenant-a");
        QuotaSubjectContext.set(QuotaSubject.user("U100"));

        MemorySubjectKey subject = resolver.resolve("uU100:conv-1",
            MemorySubjectResolver.CUSTOMER_SERVICE_AGENT);

        assertEquals("tenant-a", subject.tenantId());
        assertEquals(MemorySubjectType.USER, subject.subjectType());
        assertEquals("U100", subject.subjectId());
    }

    @Test
    void forgedUserStyleSession_withoutVerifiedSubject_shouldRemainSessionScoped() {
        TenantContext.set("tenant-a");

        MemorySubjectKey subject = resolver.resolve("uU100:conv-1",
            MemorySubjectResolver.CUSTOMER_SERVICE_AGENT);

        assertEquals(MemorySubjectType.SESSION, subject.subjectType());
        assertEquals("uU100:conv-1", subject.subjectId());
    }

    @Test
    void verifiedUser_shouldNotDependOnClientSessionFormat() {
        TenantContext.set("tenant-a");
        QuotaSubjectContext.set(QuotaSubject.user("U100"));

        MemorySubjectKey subject = resolver.resolve("arbitrary-session",
            MemorySubjectResolver.CUSTOMER_SERVICE_AGENT);

        assertEquals(MemorySubjectType.USER, subject.subjectType());
        assertEquals("U100", subject.subjectId());
    }

    @Test
    void unknownChannelSession_shouldUseFullSessionInsteadOfTenantPrefix() {
        TenantContext.set("tenant-a");

        MemorySubjectKey first = resolver.resolve("tenant-a:conv-1", "customer-service");
        MemorySubjectKey second = resolver.resolve("tenant-a:conv-2", "customer-service");

        assertEquals(MemorySubjectType.SESSION, first.subjectType());
        assertEquals("tenant-a:conv-1", first.subjectId());
        assertNotEquals(first.scopeId(), second.scopeId(),
            "未知渠道没有可靠终端用户主体时必须按完整会话隔离");
    }

    @Test
    void arbitrarySessionStartingWithU_shouldNotBeMisclassifiedAsUser() {
        TenantContext.set("tenant-a");

        MemorySubjectKey subject = resolver.resolve("user-session-without-delimiter", "customer-service");

        assertEquals(MemorySubjectType.SESSION, subject.subjectType());
        assertEquals("user-session-without-delimiter", subject.subjectId());
    }
}
