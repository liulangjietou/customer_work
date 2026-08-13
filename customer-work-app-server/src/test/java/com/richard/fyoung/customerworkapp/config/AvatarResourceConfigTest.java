package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerworkapp.service.AvatarStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 头像访问路由测试：对象存储命中、旧目录存量兜底、缺失 404、越界 key 拦成 404。
 *
 * <p>key 现在可能带 {@code yyyyMM/} 前缀（含斜杠），故路由用 {@code {*key}} 捕获剩余全部路径段——
 * 单段变量匹配不到新 key，这条是本次改造最容易漏的地方。</p>
 * @author owlzhangfq@gmail.com
 */
class AvatarResourceConfigTest {

    /** storage 命中：返回给定字节。 */
    private WebTestClient clientWithStoredObject(Path legacyDir, String key, String content) throws IOException {
        AttachmentFileStorage storage = mock(AttachmentFileStorage.class);
        when(storage.read(key)).thenReturn(content.getBytes(StandardCharsets.UTF_8));
        return client(legacyDir, storage);
    }

    /** storage 全部未命中：走旧目录兜底。 */
    private WebTestClient clientWithEmptyStorage(Path legacyDir) throws IOException {
        AttachmentFileStorage storage = mock(AttachmentFileStorage.class);
        when(storage.read(anyString())).thenThrow(new IOException("object not found"));
        return client(legacyDir, storage);
    }

    private WebTestClient client(Path legacyDir, AttachmentFileStorage storage) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getUserAuth().getAvatar().setDirectory(legacyDir.toString());
        properties.getUserAuth().getAvatar().setUrlPrefix("/api/avatars/");
        AvatarStorageService service = new AvatarStorageService(properties, storage);
        RouterFunction<ServerResponse> router =
            new AvatarResourceConfig().avatarResourceRouter(properties, service);
        return WebTestClient.bindToRouterFunction(router).build();
    }

    @Test
    void serve_objectInStorage_shouldReturnContent(@TempDir Path dir) throws Exception {
        // 新 key 带 yyyyMM 前缀，路由必须能匹配含斜杠的多段路径
        WebTestClient client = clientWithStoredObject(dir, "202608/pic.png", "image-bytes");

        client.get().uri("/api/avatars/202608/pic.png")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("image-bytes");
    }

    @Test
    void serve_legacyFileOnDisk_shouldStillWork(@TempDir Path dir) throws Exception {
        // 存量头像只在旧目录里，DB 存的 URL 无 yyyyMM 前缀——不兜底的话老用户头像全 404
        Files.writeString(dir.resolve("pic.png"), "legacy-bytes");

        clientWithEmptyStorage(dir).get().uri("/api/avatars/pic.png")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("legacy-bytes");
    }

    @Test
    void serve_missingEverywhere_shouldReturn404(@TempDir Path dir) throws Exception {
        clientWithEmptyStorage(dir).get().uri("/api/avatars/none.png")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void serve_keyEscapingBaseDir_shouldReturn404(@TempDir Path dir) throws Exception {
        Files.writeString(dir.getParent().resolve("outside.png"), "secret");

        // 越界 key 在旧目录兜底里被拦掉，对外表现为"没这张图"，不泄漏目录结构
        clientWithEmptyStorage(dir).get().uri("/api/avatars/subdir/../../outside.png")
            .exchange()
            .expectStatus().isNotFound();
    }
}
