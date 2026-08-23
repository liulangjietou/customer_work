package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/** 长期记忆同意、查看和清除的唯一领域入口。 */
@Service
public class MemoryConsentService {

    public static final String DEFAULT_CONSENT_VERSION = "memory-v1";

    private final CustomerWorkProperties properties;
    private final MemoryConsentStore consentStore;
    private final LongTermMemoryStore memoryStore;
    private final FactLog factLog;

    public MemoryConsentService(CustomerWorkProperties properties,
                                MemoryConsentStore consentStore,
                                LongTermMemoryStore memoryStore,
                                FactLog factLog) {
        this.properties = properties;
        this.consentStore = consentStore;
        this.memoryStore = memoryStore;
        this.factLog = factLog;
    }

    public boolean isGranted(MemorySubjectKey subject) {
        if (!properties.getMemory().isConsentRequired()) {
            return true;
        }
        return consentStore.find(subject)
            .map(consent -> consent.status() == MemoryConsentStatus.GRANTED)
            .orElse(false);
    }

    public MemoryConsent status(MemorySubjectKey subject) {
        return consentStore.find(subject).orElseGet(() -> new MemoryConsent(subject,
            MemoryConsentStatus.WITHDRAWN, DEFAULT_CONSENT_VERSION, null, null, 0L));
    }

    public MemoryConsent grant(MemorySubjectKey subject, String consentVersion) {
        long now = System.currentTimeMillis();
        String version = consentVersion == null || consentVersion.isBlank()
            ? DEFAULT_CONSENT_VERSION : consentVersion.trim();
        MemoryConsent consent = new MemoryConsent(subject, MemoryConsentStatus.GRANTED,
            version, now, null, now);
        consentStore.save(consent);
        return consent;
    }

    /** 先撤回授权再清除，保证清理过程中到达的新请求不会继续沉淀记忆。 */
    public MemoryConsent withdraw(MemorySubjectKey subject) {
        long now = System.currentTimeMillis();
        MemoryConsent previous = status(subject);
        MemoryConsent withdrawn = new MemoryConsent(subject, MemoryConsentStatus.WITHDRAWN,
            previous.consentVersion(), previous.grantedAtMs(), now, now);
        consentStore.save(withdrawn);
        clear(subject);
        return withdrawn;
    }

    public List<String> list(MemorySubjectKey subject, int limit) {
        // 数据主体访问权独立于处理同意：即使已撤回，也必须能查看尚未完成擦除的数据。
        return memoryStore.list(subject.scopeId(), normalizeLimit(limit));
    }

    public List<String> listFacts(MemorySubjectKey subject, int limit) {
        return factLog.readForSubjectAccess(subject.scopeId(), normalizeLimit(limit));
    }

    public void clear(MemorySubjectKey subject) {
        RuntimeException failure = null;
        try {
            memoryStore.erase(subject.scopeId());
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            factLog.erase(subject.scopeId());
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw new MemoryErasureException(failure);
        }
    }

    /** 处理并发撤回：记录完成后再检查一次，若期间已撤回则立即擦除刚写入的数据。 */
    void afterRecord(MemorySubjectKey subject) {
        if (!isGranted(subject)) {
            clear(subject);
        }
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
