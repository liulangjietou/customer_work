package com.richard.fyoung.customeradmin.workspace.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentMemorySyncService} 单测：水合（正向/存量迁移）、回写（变更/未变更/文件缺失）与异常兜底。
 * @author owlzhangfq@gmail.com
 */
class AgentMemorySyncServiceTest {

    private static final String AGENT_CODE = "memo-agent";

    @TempDir
    Path workspace;

    private AgentMemoryStore store;
    private AgentMemorySyncService service;

    @BeforeEach
    void setUp() {
        store = mock(AgentMemoryStore.class);
        service = new AgentMemorySyncService(store);
    }

    @Test
    void hydrate_shouldWriteStoreContentToWorkspace() throws Exception {
        when(store.load(AGENT_CODE))
            .thenReturn(Optional.of(new AgentMemorySnapshot("from-store", LocalDateTime.now())));

        service.hydrate(AGENT_CODE, workspace);

        assertEquals("from-store", Files.readString(workspace.resolve("MEMORY.md"), StandardCharsets.UTF_8));
    }

    @Test
    void hydrate_shouldImportLegacyWorkspaceFile_whenStoreEmpty() throws Exception {
        when(store.load(AGENT_CODE)).thenReturn(Optional.empty());
        Files.writeString(workspace.resolve("MEMORY.md"), "legacy", StandardCharsets.UTF_8);

        service.hydrate(AGENT_CODE, workspace);

        verify(store).save(AGENT_CODE, "legacy");
    }

    @Test
    void hydrate_shouldDoNothing_whenBothSidesEmpty() {
        when(store.load(AGENT_CODE)).thenReturn(Optional.empty());

        service.hydrate(AGENT_CODE, workspace);

        verify(store, never()).save(anyString(), anyString());
    }

    @Test
    void persistIfChanged_shouldSave_whenContentDiffers() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "v2", StandardCharsets.UTF_8);
        when(store.load(AGENT_CODE))
            .thenReturn(Optional.of(new AgentMemorySnapshot("v1", LocalDateTime.now())));

        service.persistIfChanged(AGENT_CODE, workspace);

        verify(store).save(AGENT_CODE, "v2");
    }

    @Test
    void persistIfChanged_shouldSkip_whenContentUnchanged() throws Exception {
        Files.writeString(workspace.resolve("MEMORY.md"), "same", StandardCharsets.UTF_8);
        when(store.load(AGENT_CODE))
            .thenReturn(Optional.of(new AgentMemorySnapshot("same", LocalDateTime.now())));

        service.persistIfChanged(AGENT_CODE, workspace);

        verify(store, never()).save(anyString(), anyString());
    }

    @Test
    void persistIfChanged_shouldSkip_whenWorkspaceFileAbsent() {
        service.persistIfChanged(AGENT_CODE, workspace);

        verify(store, never()).save(anyString(), anyString());
    }

    @Test
    void bothMethods_shouldSwallowStoreExceptions() {
        // 同步失败不允许打断对话主链路/实例构建（本服务是记忆同步链路的唯一异常兜底点）
        when(store.load(anyString())).thenThrow(new IllegalStateException("db down"));

        assertDoesNotThrow(() -> service.hydrate(AGENT_CODE, workspace));
        assertDoesNotThrow(() -> service.persistIfChanged(AGENT_CODE, workspace));
        verify(store, never()).save(any(), any());
    }
}
