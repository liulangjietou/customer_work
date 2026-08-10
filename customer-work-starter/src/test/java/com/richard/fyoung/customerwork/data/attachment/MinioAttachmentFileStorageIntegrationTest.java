package com.richard.fyoung.customerwork.data.attachment;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MinIO 附件存储集成测试（门控）：对接本机真实 MinIO（默认 {@code http://localhost:9000}，minioadmin/minioadmin，
 * 容器 {@code minio-server}）。服务不可达时自动跳过（照 {@link MybatisAttachmentStoreTest} /
 * {@link PaddleOcrVisionOcrIntegrationTest} 的可达性探测模式），可达时真实走 store → 用 SDK 读回字节断言一致 → 清理。
 *
 * <p>测试对象落独立 bucket {@code cw-attachment-it}（避免污染业务桶 customer-work-attachments），
 * 依赖惰性 auto-create-bucket 首次建桶。可用环境变量 {@code MINIO_ENDPOINT} 覆盖地址。</p>
 * @author owlzhangfq@gmail.com
 */
class MinioAttachmentFileStorageIntegrationTest {

    private static final String IT_BUCKET = "cw-attachment-it";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    private static String endpoint() {
        String env = System.getenv("MINIO_ENDPOINT");
        return (env == null || env.isBlank()) ? "http://localhost:9000" : env.trim();
    }

    private static boolean reachable(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), 1500);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void store_shouldPutAndReadBackFromRealMinio() throws Exception {
        String endpoint = endpoint();
        assumeTrue(reachable(endpoint), "MinIO 不可达（" + endpoint + "），跳过该集成测试");

        MinioAttachmentFileStorage storage = new MinioAttachmentFileStorage(
            endpoint, ACCESS_KEY, SECRET_KEY, IT_BUCKET, true);

        String id = UUID.randomUUID().toString().replace("-", "");
        byte[] payload = ("hello-minio-" + id).getBytes(StandardCharsets.UTF_8);

        // 真实上传：首次触发 bucket 惰性确保（不存在则 auto-create）+ putObject
        String key = storage.store(payload, id, "txt");
        assertTrue(key.endsWith("/" + id + ".txt"), "返回 key 应形如 {yyyyMM}/{id}.txt，实际=" + key);

        // 用独立 SDK 客户端读回，断言字节完全一致
        MinioClient client = MinioClient.builder()
            .endpoint(endpoint).credentials(ACCESS_KEY, SECRET_KEY).build();
        try (GetObjectResponse resp = client.getObject(
            GetObjectArgs.builder().bucket(IT_BUCKET).object(key).build())) {
            byte[] readBack = resp.readAllBytes();
            assertArrayEquals(payload, readBack, "读回字节应与写入一致");
        } finally {
            // 清理测试对象，避免污染
            client.removeObject(RemoveObjectArgs.builder().bucket(IT_BUCKET).object(key).build());
        }
    }

    @Test
    void read_shouldReturnStoredBytes_fromRealMinio() throws Exception {
        String endpoint = endpoint();
        assumeTrue(reachable(endpoint), "MinIO 不可达（" + endpoint + "），跳过该集成测试");

        MinioAttachmentFileStorage storage = new MinioAttachmentFileStorage(
            endpoint, ACCESS_KEY, SECRET_KEY, IT_BUCKET, true);

        String id = UUID.randomUUID().toString().replace("-", "");
        byte[] payload = ("read-minio-" + id).getBytes(StandardCharsets.UTF_8);
        String key = storage.store(payload, id, "txt");

        try {
            // 走 storage.read 读回（附件预览/下载链路），断言字节完全一致
            byte[] readBack = storage.read(key);
            assertArrayEquals(payload, readBack, "storage.read 读回字节应与写入一致");
        } finally {
            MinioClient client = MinioClient.builder()
                .endpoint(endpoint).credentials(ACCESS_KEY, SECRET_KEY).build();
            client.removeObject(RemoveObjectArgs.builder().bucket(IT_BUCKET).object(key).build());
        }
    }

    @Test
    void read_shouldThrowIOException_whenObjectMissing() {
        String endpoint = endpoint();
        assumeTrue(reachable(endpoint), "MinIO 不可达（" + endpoint + "），跳过该集成测试");

        MinioAttachmentFileStorage storage = new MinioAttachmentFileStorage(
            endpoint, ACCESS_KEY, SECRET_KEY, IT_BUCKET, true);

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
            () -> storage.read("202607/not-exist-" + UUID.randomUUID() + ".txt"));
    }
}
