package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerworkapp.service.AvatarStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 头像访问路由测试：对象存储命中出图、缺失 404。
 *
 * <p>key 带 {@code yyyyMM/} 前缀（含斜杠），故路由用 {@code {*key}} 捕获剩余全部路径段——
 * 单段变量匹配不到这种 key，是本次改造最容易漏的地方。头像本体只在 MinIO，本地盘兜底已下线。</p>
 * @author owlzhangfq@gmail.com
 */
class AvatarResourceConfigTest {

    /** storage 命中：返回给定字节。 */
    private WebTestClient clientWithStoredObject(String key, String content) throws IOException {
        AttachmentFileStorage storage = mock(AttachmentFileStorage.class);
        when(storage.read(key)).thenReturn(content.getBytes(StandardCharsets.UTF_8));
        return client(storage);
    }

    /** storage 全部未命中。 */
    private WebTestClient clientWithEmptyStorage() throws IOException {
        AttachmentFileStorage storage = mock(AttachmentFileStorage.class);
        when(storage.read(anyString())).thenThrow(new IOException("object not found"));
        return client(storage);
    }

    private WebTestClient client(AttachmentFileStorage storage) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getUserAuth().getAvatar().setUrlPrefix("/api/avatars/");
        AvatarStorageService service = new AvatarStorageService(properties, storage);
        RouterFunction<ServerResponse> router =
            new AvatarResourceConfig().avatarResourceRouter(properties, service);
        return WebTestClient.bindToRouterFunction(router).build();
    }

    @Test
    void serve_objectInStorage_shouldReturnContent() throws Exception {
        // 新 key 带 yyyyMM 前缀，路由必须能匹配含斜杠的多段路径
        clientWithStoredObject("202608/pic.png", "image-bytes")
            .get().uri("/api/avatars/202608/pic.png")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("image-bytes");
    }

    @Test
    void serve_flatKeyWithoutMonthPrefix_shouldAlsoWork() throws Exception {
        clientWithStoredObject("pic.png", "flat-bytes")
            .get().uri("/api/avatars/pic.png")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("flat-bytes");
    }

    @Test
    void serve_missingObject_shouldReturn404() throws Exception {
        clientWithEmptyStorage().get().uri("/api/avatars/none.png")
            .exchange()
            .expectStatus().isNotFound();
    }
}
