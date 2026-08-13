package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
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
import reactor.core.scheduler.Schedulers;

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
 * 用户头像存储：校验（扩展名白名单 + 大小上限）、以 UUID 命名写入对象存储、返回可访问 URL（响应式）。
 *
 * <p>存储后端走 starter 的 {@link AttachmentFileStorage} SPI（{@code customer-work.attachment.storage.type}
 * 决定 minio / local），不再各自落本地盘——多副本部署时 A 机上传的头像 B 机读不到，正是这次要解决的问题。
 * 大小上限 / URL 前缀仍取 {@code customer-work.user-auth.avatar.*}；{@code directory} 降级为
 * 存量头像的读兜底目录（见 {@link #read}）。</p>
 *
 * <p>大小校验仍是边收边计数、超限即中断（{@code sink.error}），故聚合进内存的字节量被上限封住，
 * 不会被一个超大文件打爆。扩展名校验为链路唯一防御点（fast-fail），非法即 400。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif");

    private final UserAuthProperties.Avatar config;
    private final AttachmentFileStorage fileStorage;

    public AvatarStorageService(CustomerWorkProperties properties, AttachmentFileStorage fileStorage) {
        this.config = properties.getUserAuth().getAvatar();
        this.fileStorage = fileStorage;
    }

    /**
     * 存储头像文件并返回可访问 URL。
     *
     * @param filePart 上传的文件分片
     * @return 可访问的相对 URL（{@code urlPrefix + key}）
     */
    public Mono<String> store(FilePart filePart) {
        // Mono.defer 把同步校验的异常统一收敛为 onError（一致的响应式失败契约）
        return Mono.defer(() -> {
            String extension = extractExtension(filePart.filename());
            long maxBytes = config.getMaxSizeBytes();
            AtomicLong counter = new AtomicLong();
            // 边收边计数、超限即中断：内存占用因此被大小上限封住，不会被一个超大文件打爆
            Flux<DataBuffer> counted = filePart.content().handle((buffer, sink) -> {
                if (counter.addAndGet(buffer.readableByteCount()) > maxBytes) {
                    DataBufferUtils.release(buffer);
                    sink.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "头像文件超过大小限制（" + maxBytes + " bytes）"));
                    return;
                }
                sink.next(buffer);
            });

            return DataBufferUtils.join(counted)
                .map(AvatarStorageService::toBytesAndRelease)
                .flatMap(bytes -> storeBytes(bytes, extension))
                // 存储是阻塞 IO（对象存储 HTTP / 本地盘写），不能占着事件循环线程
                .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /** 交给存储 SPI 并拼出访问 URL；失败翻译成 500（与旧落盘失败语义一致）。 */
    private Mono<String> storeBytes(byte[] bytes, String extension) {
        try {
            String key = fileStorage.store(bytes, UUID.randomUUID().toString(), extension);
            return Mono.just(config.getUrlPrefix() + key);
        } catch (IOException e) {
            log.error("avatar store failed, code={}", "AVATAR-UPLOAD-FAIL", e);
            return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "头像保存失败"));
        }
    }

    /**
     * 按 key 读头像字节：先查对象存储，未命中回落改造前的本地盘目录。
     *
     * <p>存量头像落在 {@code {directory}/{uuid}.{ext}}，DB 里 {@code cw_user.avatar_url} 存的是
     * 那个无 {@code yyyyMM} 前缀的 URL；有这层兜底，存量头像不用迁移即可继续访问。</p>
     *
     * @throws IOException 两处都没有
     */
    public byte[] read(String key) throws IOException {
        try {
            return fileStorage.read(key);
        } catch (Exception e) {
            Path legacy = resolveLegacy(key);
            if (legacy != null && Files.isRegularFile(legacy)) {
                return Files.readAllBytes(legacy);
            }
            throw new IOException("avatar not found in storage or legacy dir: " + key, e);
        }
    }

    /** 旧目录路径解析，附带路径穿越校验（key 来自 URL）；越界返回 null。 */
    private Path resolveLegacy(String key) {
        Path base = Paths.get(config.getDirectory()).toAbsolutePath().normalize();
        Path target = base.resolve(key).normalize();
        if (!target.startsWith(base)) {
            log.error("legacy avatar path escapes base dir, code={}, key={}", "AVATAR-PATH-TRAVERSAL", key);
            return null;
        }
        return target;
    }

    /** 把聚合后的 DataBuffer 读成字节数组并释放，避免堆外内存泄漏。 */
    private static byte[] toBytesAndRelease(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
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
}
