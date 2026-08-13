package com.richard.fyoung.customerwork.core.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 分层记忆同步单测（离线，用进程内存储）：水合、存量迁移、变更回写、无变更跳过。
 *
 * <p>这条链路的价值全在"换机 / 重启 / 清理 workspace 之后记忆还在"，故用例都围绕
 * 权威存储与 workspace 文件的一致性展开。</p>
 * @author owlzhangfq@gmail.com
 */
class HarnessMemorySyncServiceTest {

    private static final String MEMORY_FILE_NAME = "MEMORY.md";

    @Test
    void hydrate_shouldWriteStoredMemoryIntoWorkspace(@TempDir Path workspace) throws IOException {
        HarnessMemoryStore store = new InMemoryHarnessMemoryStore();
        store.save(scopeOf(workspace), "# MEMORY\n- 用户偏好顺丰快递\n");

        new HarnessMemorySyncService(store).hydrate(workspace);

        assertEquals("# MEMORY\n- 用户偏好顺丰快递\n",
            Files.readString(workspace.resolve(MEMORY_FILE_NAME), StandardCharsets.UTF_8));
    }

    @Test
    void hydrate_shouldImportLegacyWorkspaceFile_whenStoreEmpty(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve(MEMORY_FILE_NAME), "存量磁盘记忆", StandardCharsets.UTF_8);
        HarnessMemoryStore store = new InMemoryHarnessMemoryStore();

        new HarnessMemorySyncService(store).hydrate(workspace);

        assertEquals("存量磁盘记忆", store.load(scopeOf(workspace)).orElse(null),
            "权威侧为空且 workspace 有存量文件时应反向入库（存量迁移）");
    }

    @Test
    void hydrate_shouldDoNothing_whenBothSidesEmpty(@TempDir Path workspace) {
        HarnessMemoryStore store = new InMemoryHarnessMemoryStore();

        new HarnessMemorySyncService(store).hydrate(workspace);

        assertTrue(store.load(scopeOf(workspace)).isEmpty());
        assertFalse(Files.exists(workspace.resolve(MEMORY_FILE_NAME)), "不该凭空造出空记忆文件");
    }

    @Test
    void persistIfChanged_shouldWriteBackWorkspaceChanges(@TempDir Path workspace) throws IOException {
        HarnessMemoryStore store = new InMemoryHarnessMemoryStore();
        store.save(scopeOf(workspace), "旧记忆");
        Files.writeString(workspace.resolve(MEMORY_FILE_NAME), "新记忆", StandardCharsets.UTF_8);

        new HarnessMemorySyncService(store).persistIfChanged(workspace);

        assertEquals("新记忆", store.load(scopeOf(workspace)).orElse(null));
    }

    @Test
    void persistIfChanged_shouldSkip_whenContentUnchanged(@TempDir Path workspace) throws IOException {
        RecordingHarnessMemoryStore store = new RecordingHarnessMemoryStore();
        store.save(scopeOf(workspace), "同样的记忆");
        Files.writeString(workspace.resolve(MEMORY_FILE_NAME), "同样的记忆", StandardCharsets.UTF_8);
        int savesBefore = store.saveCount;

        new HarnessMemorySyncService(store).persistIfChanged(workspace);

        assertEquals(savesBefore, store.saveCount, "内容未变化时不该产生写入");
    }

    @Test
    void persistIfChanged_shouldSkip_whenWorkspaceFileMissing(@TempDir Path workspace) {
        RecordingHarnessMemoryStore store = new RecordingHarnessMemoryStore();

        new HarnessMemorySyncService(store).persistIfChanged(workspace);

        assertEquals(0, store.saveCount, "workspace 无记忆文件时不该写入");
    }

    /** scopeOf 与 HarnessMemorySyncService 内部保持一致：绝对规范化路径。 */
    private static String scopeOf(Path workspace) {
        return workspace.toAbsolutePath().normalize().toString();
    }

    /** 计数版进程内存储：用于断言"没有发生写入"。 */
    private static class RecordingHarnessMemoryStore extends InMemoryHarnessMemoryStore {
        private int saveCount;

        @Override
        public void save(String scopeId, String content) {
            saveCount++;
            super.save(scopeId, content);
        }
    }
}
