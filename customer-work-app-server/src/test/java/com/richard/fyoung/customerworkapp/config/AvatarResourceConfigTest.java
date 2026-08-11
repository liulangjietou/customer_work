package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 头像静态访问路由测试：正常读取、路径穿越拒绝、缺失 404。
 * @author owlzhangfq@gmail.com
 */
class AvatarResourceConfigTest {

    private WebTestClient client(Path dir) {
        CustomerWorkProperties properties = new CustomerWorkProperties();
        properties.getUserAuth().getAvatar().setDirectory(dir.toString());
        properties.getUserAuth().getAvatar().setUrlPrefix("/api/avatars/");
        RouterFunction<ServerResponse> router = new AvatarResourceConfig().avatarResourceRouter(properties);
        return WebTestClient.bindToRouterFunction(router).build();
    }

    @Test
    void serve_existingFile_shouldReturnContent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pic.png"), "image-bytes");

        client(dir).get().uri("/api/avatars/pic.png")
            .exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("image-bytes");
    }

    @Test
    void serve_missingFile_shouldReturn404(@TempDir Path dir) {
        client(dir).get().uri("/api/avatars/none.png")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void serve_pathTraversal_shouldReject(@TempDir Path dir) {
        // 单段 {filename} 变量含 ".." 片段（真实 / 无法进单段路径变量），被显式防穿越校验拦为 400
        client(dir).get().uri("/api/avatars/a..b.png")
            .exchange()
            .expectStatus().isBadRequest();
    }
}
