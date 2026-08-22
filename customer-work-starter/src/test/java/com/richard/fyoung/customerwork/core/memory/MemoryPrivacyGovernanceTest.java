package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 长期记忆主体隔离、显式同意和撤回清理测试。 */
class MemoryPrivacyGovernanceTest {

    private CustomerWorkProperties properties;
    private InMemoryMemoryConsentStore consentStore;
    private InMemoryLongTermMemoryStore memoryStore;
    private ErasableFactLog factLog;
    private MemoryConsentService consentService;
    private MemorySubjectKey subject;

    @BeforeEach
    void setUp() {
        properties = new CustomerWorkProperties();
        properties.getMemory().setConsentRequired(true);
        consentStore = new InMemoryMemoryConsentStore();
        memoryStore = new InMemoryLongTermMemoryStore();
        factLog = new ErasableFactLog();
        consentService = new MemoryConsentService(properties, consentStore, memoryStore, factLog);
        subject = new MemorySubjectKey("tenant-a", MemorySubjectType.USER, "1001", "customer-service");
    }

    @Test
    void subjectKey_shouldIncludeTenantSubjectAndAgent() {
        assertNotEquals(subject.scopeId(),
            new MemorySubjectKey("tenant-b", MemorySubjectType.USER, "1001", "customer-service").scopeId());
        assertNotEquals(subject.scopeId(),
            new MemorySubjectKey("tenant-a", MemorySubjectType.USER, "1002", "customer-service").scopeId());
        assertNotEquals(subject.scopeId(),
            new MemorySubjectKey("tenant-a", MemorySubjectType.USER, "1001", "sales-agent").scopeId());
        assertEquals(subject.scopeId(), subject.providerUserId());
        assertFalse(subject.scopeId().contains("1001"), "外部 Provider 标识不能泄露真实 userId");
    }

    @Test
    void governedMemory_shouldNotRecordOrRetrieveBeforeConsent() {
        GovernedLongTermMemory governed = governedMemory();

        governed.record(List.of(userMsg("我的地址是杭州"))).block();

        assertEquals(0, memoryStore.size(subject.scopeId()));
        StepVerifier.create(governed.retrieve(userMsg("地址"))).expectNext("").verifyComplete();
    }

    @Test
    void grant_shouldEnableMemoryAndWithdraw_shouldEraseL2AndL3() {
        GovernedLongTermMemory governed = governedMemory();
        consentService.grant(subject, "privacy-2026-08");

        governed.record(List.of(userMsg("我的地址是杭州"))).block();

        assertEquals(1, memoryStore.size(subject.scopeId()));
        assertEquals(1, factLog.read(subject.scopeId()).size());
        assertEquals(1, consentService.list(subject, 50).size());
        assertEquals(List.of("我的地址是杭州"), consentService.listFacts(subject, 50));

        MemoryConsent withdrawn = consentService.withdraw(subject);

        assertEquals(MemoryConsentStatus.WITHDRAWN, withdrawn.status());
        assertFalse(consentService.isGranted(subject));
        assertEquals(0, memoryStore.size(subject.scopeId()));
        assertTrue(factLog.read(subject.scopeId()).isEmpty());
        StepVerifier.create(governed.retrieve(userMsg("地址"))).expectNext("").verifyComplete();
    }

    @Test
    void consent_shouldBeIsolatedBetweenUsersInSameTenant() {
        MemorySubjectKey other = new MemorySubjectKey(
            "tenant-a", MemorySubjectType.USER, "1002", "customer-service");
        consentService.grant(subject, null);

        assertTrue(consentService.isGranted(subject));
        assertFalse(consentService.isGranted(other));
    }

    @Test
    void list_shouldRemainAvailableWithoutProcessingConsent() {
        memoryStore.add(subject.scopeId(), "历史遗留记忆");
        memoryStore.add(subject.scopeId(), "较新的记忆");

        assertEquals(List.of("较新的记忆", "历史遗留记忆"), consentService.list(subject, 50),
            "主体访问权不能被处理同意状态阻断");
    }

    @Test
    void withdraw_shouldRemainWithdrawnAndReportErasureFailure() {
        factLog.append(subject.scopeId(), "待清理事实");
        LongTermMemoryStore unsupportedStore = new LongTermMemoryStore() {
            @Override
            public void add(String scopeId, String fact) {
            }

            @Override
            public List<String> recall(String scopeId, String query, int topK) {
                return List.of();
            }

            @Override
            public List<String> list(String scopeId, int limit) {
                return List.of();
            }

            @Override
            public void clear(String scopeId) {
            }

            @Override
            public int size(String scopeId) {
                return 0;
            }
        };
        MemoryConsentService service = new MemoryConsentService(
            properties, consentStore, unsupportedStore, factLog);
        service.grant(subject, null);

        assertThrows(MemoryErasureException.class, () -> service.withdraw(subject));
        assertFalse(service.isGranted(subject), "擦除失败也必须先停掉后续处理");
        assertTrue(factLog.read(subject.scopeId()).isEmpty(), "一个存储失败不能阻止其它存储继续擦除");
    }

    private GovernedLongTermMemory governedMemory() {
        return new GovernedLongTermMemory(
            new InMemoryLongTermMemory(memoryStore, factLog, subject.scopeId(), 5),
            subject, consentService);
    }

    private Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(text).build()).build();
    }

    private static class ErasableFactLog implements FactLog {
        private final List<FactRecord> records = new ArrayList<>();

        @Override
        public void append(String scopeId, String fact) {
            records.add(new FactRecord(System.currentTimeMillis(), scopeId, fact));
        }

        @Override
        public List<String> read(String scopeId) {
            return records.stream().filter(record -> record.scope().equals(scopeId))
                .map(FactRecord::fact).toList();
        }

        @Override
        public List<FactRecord> readRecords(String scopeId) {
            return records.stream().filter(record -> record.scope().equals(scopeId)).toList();
        }

        @Override
        public List<String> readForSubjectAccess(String scopeId, int limit) {
            List<String> facts = read(scopeId);
            return facts.subList(Math.max(0, facts.size() - Math.max(1, limit)), facts.size());
        }

        @Override
        public void erase(String scopeId) {
            records.removeIf(record -> record.scope().equals(scopeId));
        }
    }
}
