package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.richard.fyoung.customerwork.infra.config.properties.UserAuthProperties;

/**
 * 头像存储服务单测：扩展名/大小校验、成功落盘、超限中断清理半成品。
 * @author owlzhangfq@gmail.com
 */
class AvatarStorageServiceTest {

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    private AvatarStorageService service(Path dir, long maxBytes) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        UserAuthProperties.Avatar avatar = properties.getUserAuth().getAvatar();
        avatar.setDirectory(dir.toString());
        avatar.setMaxSizeBytes(maxBytes);
        avatar.setUrlPrefix("/api/avatars/");
        return new AvatarStorageService(properties);
    }

    private FilePart filePart(String filename, String... chunks) {
        FilePart part = mock(FilePart.class);
        when(part.filename()).thenReturn(filename);
        Flux<DataBuffer> content = Flux.fromStream(Stream.of(chunks))
            .map(s -> bufferFactory.wrap(s.getBytes(StandardCharsets.UTF_8)));
        when(part.content()).thenReturn(content);
        return part;
    }

    @Test
    void store_validPng_shouldPersistAndReturnUrl(@TempDir Path dir) throws Exception {
        AvatarStorageService service = service(dir, 1024);

        String url = service.store(filePart("photo.png", "hello-image")).block();

        assertTrue(url.startsWith("/api/avatars/"));
        assertTrue(url.endsWith(".png"));
        List<Path> files = Files.list(dir).toList();
        assertEquals(1, files.size(), "应有一个落盘文件");
        assertEquals("hello-image", Files.readString(files.get(0)));
    }

    @Test
    void store_disallowedExtension_shouldReject() {
        AvatarStorageService service = service(Path.of(System.getProperty("java.io.tmpdir")), 1024);

        StepVerifier.create(service.store(filePart("evil.txt", "x")))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.BAD_REQUEST,
                ((ResponseStatusException) e).getStatusCode()))
            .verify();
    }

    @Test
    void store_missingExtension_shouldReject() {
        AvatarStorageService service = service(Path.of(System.getProperty("java.io.tmpdir")), 1024);

        StepVerifier.create(service.store(filePart("noext", "x")))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.BAD_REQUEST,
                ((ResponseStatusException) e).getStatusCode()))
            .verify();
    }

    @Test
    void store_oversize_shouldAbortAndCleanup(@TempDir Path dir) throws Exception {
        // 上限 4 字节，分两块共 10 字节 → 第二块触发超限中断
        AvatarStorageService service = service(dir, 4);

        StepVerifier.create(service.store(filePart("big.jpg", "abcd", "efghij")))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,
                ((ResponseStatusException) e).getStatusCode()))
            .verify();

        assertEquals(0, Files.list(dir).count(), "超限中断后不得残留半成品文件");
    }
}
