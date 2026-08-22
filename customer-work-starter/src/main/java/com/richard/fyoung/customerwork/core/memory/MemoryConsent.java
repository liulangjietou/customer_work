package com.richard.fyoung.customerwork.core.memory;

/** 长期记忆同意记录。 */
public record MemoryConsent(MemorySubjectKey subject,
                            MemoryConsentStatus status,
                            String consentVersion,
                            Long grantedAtMs,
                            Long withdrawnAtMs,
                            long updatedAtMs) {
}
