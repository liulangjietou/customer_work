package com.richard.fyoung.customerwork.data.attachment;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内附件文件存储测试替身：key 生成规则与 {@link MinioAttachmentFileStorage} 一致
 * （{@code {yyyyMM}/{id}.{ext}}），内容存在 Map 里。
 *
 * <p>生产侧只剩 MinIO 一种实现（文件不再落本地盘），故不依赖对象存储的单测需要这样一个替身。
 * 真实往返由 {@code MinioAttachmentFileStorageIntegrationTest}（门控）覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryTestFileStorage implements AttachmentFileStorage {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public String store(byte[] data, String id, String ext) {
        String fileName = (ext == null || ext.isEmpty()) ? id : id + "." + ext;
        String key = LocalDate.now().format(MONTH_FMT) + "/" + fileName;
        objects.put(key, data);
        return key;
    }

    @Override
    public void storeAt(String storagePath, byte[] data) {
        objects.put(storagePath, data);
    }

    @Override
    public byte[] read(String storagePath) throws IOException {
        byte[] data = objects.get(storagePath);
        if (data == null) {
            throw new IOException("attachment object not found: " + storagePath);
        }
        return data;
    }

    @Override
    public void delete(String storagePath) {
        objects.remove(storagePath);
    }
}
