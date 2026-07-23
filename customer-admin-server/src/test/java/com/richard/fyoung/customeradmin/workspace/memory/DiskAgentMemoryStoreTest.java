package com.richard.fyoung.customeradmin.workspace.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiskAgentMemoryStore} 单测：{@code {root}/{agentCode}/MEMORY.md} 的读写删与幂等。
 * @author owlzhangfq@gmail.com
 */
class DiskAgentMemoryStoreTest {

    private static final String AGENT_CODE = "memo-agent";

    @TempDir
    Path root;

    private DiskAgentMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new DiskAgentMemoryStore(root);
    }

    @Test
    void load_shouldReturnEmpty_whenFileAbsent() {
        assertTrue(store.load(AGENT_CODE).isEmpty());
    }

    @Test
    void saveThenLoad_shouldRoundTrip() throws Exception {
        store.save(AGENT_CODE, "记忆内容");

        Optional<AgentMemorySnapshot> snapshot = store.load(AGENT_CODE);
        assertTrue(snapshot.isPresent());
        assertEquals("记忆内容", snapshot.get().content());
        assertNotNull(snapshot.get().updateTime());
        assertEquals("记忆内容",
            Files.readString(root.resolve(AGENT_CODE).resolve("MEMORY.md"), StandardCharsets.UTF_8));
    }

    @Test
    void save_shouldOverwriteExisting() {
        store.save(AGENT_CODE, "v1");
        store.save(AGENT_CODE, "v2");

        assertEquals("v2", store.load(AGENT_CODE).orElseThrow().content());
    }

    @Test
    void delete_shouldRemoveFile_andBeIdempotent() {
        store.save(AGENT_CODE, "content");

        store.delete(AGENT_CODE);
        assertTrue(store.load(AGENT_CODE).isEmpty());
        assertDoesNotThrow(() -> store.delete(AGENT_CODE));
    }
}
