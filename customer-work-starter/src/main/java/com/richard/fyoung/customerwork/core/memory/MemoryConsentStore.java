package com.richard.fyoung.customerwork.core.memory;

import java.util.Optional;

/** 长期记忆同意存储 SPI。 */
public interface MemoryConsentStore {

    Optional<MemoryConsent> find(MemorySubjectKey subject);

    void save(MemoryConsent consent);
}
