package com.richard.fyoung.customerwork.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalAttachmentFileStorage} 本地盘存-读往返 + 路径穿越防御测试（全程离线，临时目录）。
 *
 * <p>read 是附件预览/下载链路的唯一路径穿越防御点：storagePath 来自 DB，规范化后必须仍在 baseDir 内，
 * 非法（{@code ../} 逃逸）直接 {@link IllegalArgumentException} fast-fail；文件不存在抛 {@link IOException}。</p>
 * @author owlzhangfq@gmail.com
 */
class LocalAttachmentFileStorageTest {

    @Test
    void storeThenRead_shouldRoundTripBytes(@TempDir Path baseDir) throws IOException {
        LocalAttachmentFileStorage storage = new LocalAttachmentFileStorage(baseDir.toString());
        byte[] payload = "hello-local-storage".getBytes(StandardCharsets.UTF_8);

        String key = storage.store(payload, "attach-1", "txt");
        byte[] readBack = storage.read(key);

        assertArrayEquals(payload, readBack, "读回字节应与写入一致");
    }

    @Test
    void read_shouldRejectPathTraversal(@TempDir Path baseDir) {
        LocalAttachmentFileStorage storage = new LocalAttachmentFileStorage(baseDir.toString());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> storage.read("../../etc/passwd"));
        assertTrue(ex.getMessage().contains("escapes base dir"));
    }

    @Test
    void read_shouldThrowIOException_whenFileMissing(@TempDir Path baseDir) {
        LocalAttachmentFileStorage storage = new LocalAttachmentFileStorage(baseDir.toString());

        assertThrows(IOException.class, () -> storage.read("202607/not-exist.txt"));
    }
}
