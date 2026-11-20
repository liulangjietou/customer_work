package com.richard.fyoung.customeradmin.workspace.chat.service;

import com.richard.fyoung.customeradmin.workspace.memory.AgentMemorySyncService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionModeRegistry;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService;
import com.richard.fyoung.customerwork.infra.lock.InMemorySessionLock;
import com.richard.fyoung.customerwork.infra.lock.SessionLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 两个 ChatService 实例共享一把外部锁，模拟同一会话分别命中两个 Pod。 */
class ChatSessionSerializationTest {

    @Test
    void sameTenantAgentSession_shouldRunSeriallyAcrossServiceInstances() {
        SessionLock sharedLock = new InMemorySessionLock(2);
        ChatService podA = service(sharedLock);
        ChatService podB = service(sharedLock);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        Flux.merge(
            podA.withSessionLock("tenant-a:agent-a:session-a", guardedWork(active, maximum)),
            podB.withSessionLock("tenant-a:agent-a:session-a", guardedWork(active, maximum)))
            .collectList()
            .block(Duration.ofSeconds(3));

        assertEquals(1, maximum.get(), "同一 tenant + agent + session 跨实例不得并行执行");
    }

    private Flux<Integer> guardedWork(AtomicInteger active, AtomicInteger maximum) {
        return Flux.defer(() -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            return Mono.delay(Duration.ofMillis(80))
                .thenMany(Flux.just(current))
                // doOnComplete 在完成信号向锁包装层传播前执行，准确表示受保护业务段结束。
                .doOnComplete(active::decrementAndGet);
        });
    }

    @SuppressWarnings("unchecked")
    private ChatService service(SessionLock lock) {
        ObjectProvider<SessionLock> lockProvider = mock(ObjectProvider.class);
        when(lockProvider.getIfAvailable()).thenReturn(lock);
        return new ChatService(mock(AgentInstanceCache.class), mock(AdminAgentInstanceFactory.class),
            mock(ChatHistoryCache.class), mock(AgentMemorySyncService.class), new ExecutionModeRegistry(),
            new PlanConfirmationService(), mock(ChatAttachmentService.class), null, null, lockProvider);
    }
}
