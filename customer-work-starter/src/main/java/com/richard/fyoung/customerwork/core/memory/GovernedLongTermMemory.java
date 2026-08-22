package com.richard.fyoung.customerwork.core.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;

/** 在任意长期记忆 Provider 外统一执行主体同意闸门。 */
public class GovernedLongTermMemory implements LongTermMemory {

    private final LongTermMemory delegate;
    private final MemorySubjectKey subject;
    private final MemoryConsentService consentService;

    public GovernedLongTermMemory(LongTermMemory delegate,
                                  MemorySubjectKey subject,
                                  MemoryConsentService consentService) {
        this.delegate = delegate;
        this.subject = subject;
        this.consentService = consentService;
    }

    @Override
    public Mono<Void> record(List<Msg> messages) {
        return Mono.defer(() -> {
            if (!consentService.isGranted(subject)) {
                return Mono.empty();
            }
            return delegate.record(messages).doOnSuccess(ignored -> consentService.afterRecord(subject));
        });
    }

    @Override
    public Mono<String> retrieve(Msg query) {
        return Mono.defer(() -> consentService.isGranted(subject)
            ? delegate.retrieve(query) : Mono.just(""));
    }
}
