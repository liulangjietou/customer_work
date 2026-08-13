package com.richard.fyoung.customeradmin.common.storage;

import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ImageStorageSupport} 单测：写走对象存储、读带本地盘存量兜底、删两处都清、路径穿越拦截。
 *
 * <p>存量兜底是这次改造能"不迁移数据"的关键——DB 里已存的 {@code /api/menu-icons/{uuid}.png}
 * 对应的文件只在旧目录里，对象存储里没有。</p>
 * @author owlzhangfq@gmail.com
 */
class ImageStorageSupportTest {

    @Test
    void store_shouldDelegateToFileStorage(@TempDir Path legacyRoot) throws IOException {
        AttachmentFileStorage fileStorage = mock(AttachmentFileStorage.class);
        when(fileStorage.store(any(), anyString(), anyString())).thenReturn("202608/uuid.png");

        String key = new ImageStorageSupport(fileStorage, legacyRoot.toString())
            .store(new byte[] {1, 2}, "uuid", "png");

        assertArrayEquals("202608/uuid.png".getBytes(StandardCharsets.UTF_8),
            key.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void read_shouldPreferObjectStorage(@TempDir Path legacyRoot) throws IOException {
        AttachmentFileStorage fileStorage = mock(AttachmentFileStorage.class);
        when(fileStorage.read("202608/uuid.png")).thenReturn(new byte[] {9});
        Files.writeString(legacyRoot.resolve("202608-uuid.png"), "旧图", StandardCharsets.UTF_8);

        byte[] data = new ImageStorageSupport(fileStorage, legacyRoot.toString()).read("202608/uuid.png");

        assertArrayEquals(new byte[] {9}, data);
    }

    @Test
    void read_shouldFallBackToLegacyDir_whenObjectMissing(@TempDir Path legacyRoot) throws IOException {
        AttachmentFileStorage fileStorage = mock(AttachmentFileStorage.class);
        when(fileStorage.read(anyString())).thenThrow(new IOException("object not found"));
        Files.write(legacyRoot.resolve("legacy-uuid.png"), new byte[] {7});

        byte[] data = new ImageStorageSupport(fileStorage, legacyRoot.toString()).read("legacy-uuid.png");

        assertArrayEquals(new byte[] {7}, data, "对象存储没有时应回落旧目录，存量图片才不用迁移");
    }

    @Test
    void read_shouldThrow_whenMissingEverywhere(@TempDir Path legacyRoot) throws IOException {
        AttachmentFileStorage fileStorage = mock(AttachmentFileStorage.class);
        when(fileStorage.read(anyString())).thenThrow(new IOException("object not found"));

        ImageStorageSupport support = new ImageStorageSupport(fileStorage, legacyRoot.toString());

        assertThrows(IOException.class, () -> support.read("nowhere.png"));
    }

    @Test
    void read_shouldRejectPathTraversalInLegacyFallback(@TempDir Path legacyRoot) throws IOException {
        AttachmentFileStorage fileStorage = mock(AttachmentFileStorage.class);
        when(fileStorage.read(anyString())).thenThrow(new IOException("object not found"));
        // 旧目录之外放一个文件，key 用 ../ 试图读它
        Files.writeString(legacyRoot.getParent().resolve("outside.txt"), "机密", StandardCharsets.UTF_8);

        ImageStorageSupport support = new ImageStorageSupport(fileStorage, legacyRoot.toString());

        assertThrows(IOException.class, () -> support.read("../outside.txt"),
            "key 来自 URL，越界读必须被拦成'找不到'而不是真读出去");
    }

    @Test
    void delete_shouldClearBothBackends(@TempDir Path legacyRoot) throws IOException {
        AttachmentFileStorage fileStorage = mock(AttachmentFileStorage.class);
        Path legacyFile = legacyRoot.resolve("legacy-uuid.png");
        Files.write(legacyFile, new byte[] {7});

        new ImageStorageSupport(fileStorage, legacyRoot.toString()).delete("legacy-uuid.png");

        verify(fileStorage).delete("legacy-uuid.png");
        assertFalse(Files.exists(legacyFile), "存量图可能还在旧目录里，两处都要删");
    }

    @Test
    void delete_shouldNotThrow_whenBackendFails(@TempDir Path legacyRoot) throws IOException {
        AttachmentFileStorage fileStorage = mock(AttachmentFileStorage.class);
        doThrow(new IOException("minio down")).when(fileStorage).delete(anyString());

        // 删除记录的主链路在 DB 侧，文件清理失败不该冒泡打断它
        new ImageStorageSupport(fileStorage, legacyRoot.toString()).delete("whatever.png");
    }
}
