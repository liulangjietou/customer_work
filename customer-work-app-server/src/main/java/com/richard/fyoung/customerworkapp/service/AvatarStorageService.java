package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.richard.fyoung.customerwork.infra.config.properties.UserAuthProperties;

/**
 * 用户头像存储：校验（扩展名白名单 + 大小上限）、以 UUID 命名落盘、返回可访问 URL（响应式）。
 *
 * <p>落盘目录 / 大小上限 / URL 前缀来自 {@code customer-work.user-auth.avatar.*} 配置。大小校验采用边写边计数、
 * 超限即中断（{@code sink.error}）的方式，避免把大文件全量读进内存或全量写盘后再拒绝；中断时清理已落盘的半成品。
 * 扩展名校验为链路唯一防御点（fast-fail），非法即 400。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif");

    private final UserAuthProperties.Avatar config;

    public AvatarStorageService(CustomerWorkProperties properties) {
        this.config = properties.getUserAuth().getAvatar();
    }

    /**
     * 存储头像文件并返回可访问 URL。
     *
     * @param filePart 上传的文件分片
     * @return 落盘后的相对访问 URL（{@code urlPrefix + uuid.ext}）
     */
    public Mono<String> store(FilePart filePart) {
        // Mono.defer 把同步校验/建目录的异常统一收敛为 onError（一致的响应式失败契约）
        return Mono.defer(() -> {
            String extension = extractExtension(filePart.filename());
            String storedName = UUID.randomUUID() + "." + extension;

            Path dir = Paths.get(config.getDirectory());
            Path target;
            try {
                Files.createDirectories(dir);
                target = dir.resolve(storedName);
            } catch (IOException e) {
                log.error("avatar dir prepare failed, code={}, dir={}", "AVATAR-UPLOAD-FAIL", config.getDirectory(), e);
                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "头像目录创建失败"));
            }

            long maxBytes = config.getMaxSizeBytes();
            AtomicLong counter = new AtomicLong();
            Flux<DataBuffer> counted = filePart.content().handle((buffer, sink) -> {
                if (counter.addAndGet(buffer.readableByteCount()) > maxBytes) {
                    DataBufferUtils.release(buffer);
                    sink.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "头像文件超过大小限制（" + maxBytes + " bytes）"));
                    return;
                }
                sink.next(buffer);
            });

            final Path finalTarget = target;
            return DataBufferUtils.write(counted, target,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                .then(Mono.just(config.getUrlPrefix() + storedName))
                .doOnError(e -> deleteQuietly(finalTarget));
        });
    }

    /** 提取并校验扩展名（链路唯一防御点，非法即 400）。 */
    private String extractExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename) || !originalFilename.contains(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名缺少扩展名");
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 png/jpg/jpeg/gif 格式头像");
        }
        return ext;
    }

    /** 清理落盘半成品（中断/失败时），失败仅记日志不再抛。 */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.error("avatar cleanup failed, code={}, path={}", "AVATAR-CLEANUP-FAIL", path, e);
        }
    }
}
