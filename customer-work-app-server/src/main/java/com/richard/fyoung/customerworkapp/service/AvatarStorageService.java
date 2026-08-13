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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.richard.fyoung.customerwork.infra.config.properties.UserAuthProperties;

/**
 * 用户头像存储：校验（扩展名白名单 + 大小上限）、以 UUID 命名写入对象存储、返回可访问 URL（响应式）。
 *
 * <p>存储后端走 starter 的 {@link AttachmentFileStorage} SPI（MinIO），项目内<b>不落任何文件</b>。
 * 大小上限 / URL 前缀仍取 {@code customer-work.user-auth.avatar.*}。改造前落在旧本地目录的存量头像
 * 需要重新上传，或由运维把旧文件按同名 key 灌进 MinIO。</p>
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
     * 按 key 读头像字节。
     *
     * @throws IOException 对象不存在或读取失败
     */
    public byte[] read(String key) throws IOException {
        return fileStorage.read(key);
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
