package com.richard.fyoung.customerwork.core.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内同意存储，仅供开发和测试。 */
public class InMemoryMemoryConsentStore implements MemoryConsentStore {

    private final Map<MemorySubjectKey, MemoryConsent> records = new ConcurrentHashMap<>();

    @Override
    public Optional<MemoryConsent> find(MemorySubjectKey subject) {
        return Optional.ofNullable(records.get(subject));
    }

    @Override
    public void save(MemoryConsent consent) {
        records.put(consent.subject(), consent);
    }
}
