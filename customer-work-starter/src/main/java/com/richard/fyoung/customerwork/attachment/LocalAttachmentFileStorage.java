package com.richard.fyoung.customerwork.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 附件本地盘存储：把上传字节落到 {@code {base-dir}/{yyyyMM}/{uuid}.{ext}}，返回相对路径。
 *
 * <p>磁盘文件名只用 {@code uuid.ext}（原始文件名不落盘名，防路径穿越），原名仅进 DB。相对路径形如
 * {@code 202607/xxxx.pdf}，与 base-dir 拼接即绝对路径。{@code storage.type=local}（默认）时启用。</p>
 * @author owlzhangfq@gmail.com
 */
public class LocalAttachmentFileStorage implements AttachmentFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentFileStorage.class);

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final String baseDir;

    public LocalAttachmentFileStorage(String baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public String store(byte[] data, String id, String ext) throws IOException {
        String month = LocalDate.now().format(MONTH_FMT);
        String fileName = (ext == null || ext.isEmpty()) ? id : id + "." + ext;
        Path dir = Paths.get(baseDir, month);
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName);
        Files.write(target, data);
        String relativePath = month + "/" + fileName;
        log.info("attachment stored to local disk, id={}, path={}, size={}", id, relativePath, data.length);
        return relativePath;
    }
}
