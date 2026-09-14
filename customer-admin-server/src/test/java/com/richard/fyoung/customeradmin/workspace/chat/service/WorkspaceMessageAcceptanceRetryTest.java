package com.richard.fyoung.customeradmin.workspace.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatReceipt;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatRequest;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.chat.store.WorkspaceMessageReceiptStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Flux;

/** 锁竞争只重试受理事务；次数有界，提交结果未知或业务流失败不得重放。 */
class WorkspaceMessageAcceptanceRetryTest {
    private static final String CLIENT_ID = "478b7f10-46ba-4217-947f-d86528aa8fbe";
    private final WorkspaceMessageReceiptStore store = mock(WorkspaceMessageReceiptStore.class);
    private final AtomicInteger constructions = new AtomicInteger();

    @Test
    void transientLockFailureRetriesTheSameReceiptWithoutRebuildingTheBusinessStream() {
        when(store.accept(any(), anyString(), anyLong()))
            .thenThrow(new CannotAcquireLockException("deadlock victim"))
            .thenReturn(true);
        when(store.require(any(), anyString())).thenReturn(new ChatReceipt(CLIENT_ID, 1L, null));
        execute().collectList().block(Duration.ofSeconds(1));
        var calls = org.mockito.Mockito.mockingDetails(store).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("accept")).toList();
        assertEquals(2, calls.size());
        org.junit.jupiter.api.Assertions.assertArrayEquals(calls.get(0).getArguments(), calls.get(1).getArguments());
        assertEquals(1, constructions.get());
    }

    @Test
    void competingWinnerAfterRollbackReturnsItsReceiptWithoutExecution() {
        when(store.accept(any(), anyString(), anyLong()))
            .thenThrow(new CannotAcquireLockException("deadlock victim"))
            .thenReturn(false);
        when(store.require(any(), anyString())).thenReturn(new ChatReceipt(CLIENT_ID, 1L, null));
        assertEquals(2, execute().collectList().block(Duration.ofSeconds(1)).size());
        assertEquals(0, constructions.get());
    }

    @Test
    void repeatedLockFailureStopsAfterThreeAttemptsBeforeModelConstruction() {
        var failure = new CannotAcquireLockException("persistent lock contention");
        when(store.accept(any(), anyString(), anyLong())).thenThrow(failure);
        assertSame(failure, assertThrows(CannotAcquireLockException.class, this::execute));
        verify(store, times(3)).accept(any(), anyString(), anyLong());
        verify(store, never()).require(any(), anyString());
        assertEquals(0, constructions.get());
    }

    @Test
    void unknownCommitOrConnectionFailureIsNotRetried() {
        var failure = new DataAccessResourceFailureException("connection lost");
        when(store.accept(any(), anyString(), anyLong())).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class, this::execute));
        verify(store).accept(any(), anyString(), anyLong());
        verify(store, never()).require(any(), anyString());
        assertEquals(0, constructions.get());
    }

    private Flux<ChatStreamChunk> execute() {
        var properties = new AdminTenantProperties();
        properties.setEnabled(false);
        var service = new WorkspaceMessageAcceptanceService(store, properties);
        var request = new ChatRequest("session-1", "消息", false, "auto", List.of(), "消息", CLIENT_ID);
        return service.execute("agent-1", 42, "chat", request, () -> {
            constructions.incrementAndGet();
            return Flux.empty();
        });
    }
}
