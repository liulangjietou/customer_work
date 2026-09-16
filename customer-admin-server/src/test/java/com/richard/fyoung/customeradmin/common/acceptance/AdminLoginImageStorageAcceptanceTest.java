package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.richard.fyoung.customeradmin.system.loginimage.controller.LoginImagePublicController;
import com.richard.fyoung.customeradmin.system.loginimage.service.LoginCarouselImageService;
import com.richard.fyoung.customeradmin.system.loginimage.service.LoginImageStorageService;
import com.richard.fyoung.customerwork.data.attachment.MinioAttachmentFileStorage;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 轮播图实际对象存储与公开出图链路；只创建和清理本类独占的随机测试桶。 */
class AdminLoginImageStorageAcceptanceTest {
    private static final String BUCKET = "admin-login-image-" + UUID.randomUUID().toString().replace("-", "");
    private static final List<String> OWNED_KEYS = new ArrayList<>();
    private static MinioClient client;
    private static MinioAttachmentFileStorage objects;
    private static LoginImageStorageService storage;
    private static MockMvc http;
    private static boolean bucketCreated;

    @BeforeAll
    static void createOwnStorage() throws Exception {
        String endpoint = System.getenv().getOrDefault("MINIO_ENDPOINT", "http://127.0.0.1:9000");
        String accessKey = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin");
        String secretKey = System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin");
        client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        bucketCreated = true;
        objects = new MinioAttachmentFileStorage(endpoint, accessKey, secretKey, BUCKET, false);
        storage = new LoginImageStorageService(objects);
        // 新轮播图在专用命名空间内可先预览再保存记录；本类不扩充为数据库引用验收。
        var controller = new LoginImagePublicController(mock(LoginCarouselImageService.class), storage);
        http = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterAll
    static void removeOwnStorage() throws Exception {
        if (!bucketCreated) return;
        for (String key : OWNED_KEYS) {
            client.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(key).build());
        }
        client.removeBucket(RemoveBucketArgs.builder().bucket(BUCKET).build());
    }

    @Test
    void uploadedImageCanBeReadThroughPublicRouteAndDisappearsAfterDeletion() throws Exception {
        var image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        image.setRGB(20, 30, 0x3e63dd);
        var bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        byte[] payload = bytes.toByteArray();
        String url = storage.store(new MockMultipartFile("file", "acceptance.png", "image/png", payload));
        String key = url.substring(LoginImageStorageService.URL_PREFIX.length());
        OWNED_KEYS.add(key);
        assertTrue(storage.ownsKey(key));
        try (var stored = client.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {
            assertArrayEquals(payload, stored.readAllBytes());
        }
        var response = http.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("image/png", response.getContentType());
        assertArrayEquals(payload, response.getContentAsByteArray());
        storage.delete(url);
        http.perform(get(url)).andExpect(status().isNotFound());
    }

    @Test
    void privateObjectInSameBucketCannotBeReadThroughPublicLoginImageRoute() throws Exception {
        byte[] payload = "private-attachment-acceptance".getBytes(StandardCharsets.UTF_8);
        String key = objects.store(payload, UUID.randomUUID().toString(), "txt");
        OWNED_KEYS.add(key);
        http.perform(get(LoginImageStorageService.URL_PREFIX + key)).andExpect(status().isNotFound());
        try (var stored = client.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {
            assertArrayEquals(payload, stored.readAllBytes());
        }
    }
}
