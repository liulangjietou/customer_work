package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.richard.fyoung.customerwork.infra.config.properties.UserAuthProperties;

/**
 * 头像存储服务单测：扩展名/大小校验、成功写入对象存储、超限中断不触达存储、读取的旧目录兜底。
 * @author owlzhangfq@gmail.com
 */
class AvatarStorageServiceTest {

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    private AttachmentFileStorage fileStorage;

    private AvatarStorageService service(long maxBytes) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        UserAuthProperties.Avatar avatar = properties.getUserAuth().getAvatar();
        avatar.setMaxSizeBytes(maxBytes);
        avatar.setUrlPrefix("/api/avatars/");
        fileStorage = mock(AttachmentFileStorage.class);
        return new AvatarStorageService(properties, fileStorage);
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
    void store_validPng_shouldWriteToStorageAndReturnUrl() throws Exception {
        AvatarStorageService service = service(1024);
        when(fileStorage.store(any(), anyString(), anyString())).thenReturn("202608/uuid.png");

        String url = service.store(filePart("photo.png", "hello-", "image")).block();

        assertEquals("/api/avatars/202608/uuid.png", url);
        // 分块内容应完整聚合后才交给存储，不能只写第一块
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> ext = ArgumentCaptor.forClass(String.class);
        verify(fileStorage).store(bytes.capture(), anyString(), ext.capture());
        assertArrayEquals("hello-image".getBytes(StandardCharsets.UTF_8), bytes.getValue());
        assertEquals("png", ext.getValue());
    }

    @Test
    void store_disallowedExtension_shouldReject() throws IOException {
        AvatarStorageService service = service(1024);

        StepVerifier.create(service.store(filePart("evil.txt", "x")))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.BAD_REQUEST,
                ((ResponseStatusException) e).getStatusCode()))
            .verify();
        verify(fileStorage, never()).store(any(), anyString(), anyString());
    }

    @Test
    void store_missingExtension_shouldReject() throws IOException {
        AvatarStorageService service = service(1024);

        StepVerifier.create(service.store(filePart("noext", "x")))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.BAD_REQUEST,
                ((ResponseStatusException) e).getStatusCode()))
            .verify();
        verify(fileStorage, never()).store(any(), anyString(), anyString());
    }

    @Test
    void store_oversize_shouldAbortBeforeTouchingStorage() throws IOException {
        // 上限 4 字节，分两块共 10 字节 → 第二块触发超限中断
        AvatarStorageService service = service(4);

        StepVerifier.create(service.store(filePart("big.jpg", "abcd", "efghij")))
            .expectErrorSatisfies(e -> assertEquals(HttpStatus.PAYLOAD_TOO_LARGE,
                ((ResponseStatusException) e).getStatusCode()))
            .verify();

        verify(fileStorage, never()).store(any(), anyString(), anyString());
    }

    @Test
    void read_shouldPreferObjectStorage() throws Exception {
        AvatarStorageService service = service(1024);
        when(fileStorage.read("202608/uuid.png")).thenReturn(new byte[] {1, 2});

        assertArrayEquals(new byte[] {1, 2}, service.read("202608/uuid.png"));
    }

    @Test
    void read_shouldThrowWhenMissingEverywhere() throws Exception {
        AvatarStorageService service = service(1024);
        when(fileStorage.read(anyString())).thenThrow(new IOException("object not found"));

        assertThrows(IOException.class, () -> service.read("none.png"));
    }

}
