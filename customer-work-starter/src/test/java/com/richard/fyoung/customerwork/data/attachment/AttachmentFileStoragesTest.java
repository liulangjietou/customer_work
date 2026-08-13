package com.richard.fyoung.customerwork.data.attachment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link AttachmentFileStorages} 存储后端切换测试：验证按 {@code storage.type} 选出正确实现类型。
 *
 * <p>全程离线：MinIO 分支只验证构造出 {@link MinioAttachmentFileStorage}（构造不连网、bucket 惰性确保），
 * 不触真实 MinIO。真实往返在 {@link MinioAttachmentFileStorageIntegrationTest}（门控）覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
class AttachmentFileStoragesTest {

    @Test
    void create_shouldReturnMinioByDefault() {
        AttachmentProperties props = new AttachmentProperties();
        // 默认 storage.type=minio：二进制落本地盘在多副本下是坏的（A 机上传 B 机读不到）
        AttachmentFileStorage storage = AttachmentFileStorages.create(props);
        assertInstanceOf(MinioAttachmentFileStorage.class, storage);
    }

    @Test
    void create_shouldReturnLocalWhenTypeIsLocal() {
        AttachmentProperties props = new AttachmentProperties();
        props.getStorage().setType("local");
        AttachmentFileStorage storage = AttachmentFileStorages.create(props);
        assertInstanceOf(LocalAttachmentFileStorage.class, storage);
    }

    @Test
    void create_shouldReturnMinioWhenTypeIsMinio() {
        AttachmentProperties props = new AttachmentProperties();
        props.getStorage().setType("minio");
        props.getStorage().getMinio().setEndpoint("http://localhost:9000");
        props.getStorage().getMinio().setBucket("cw-attachment-it");

        // 构造不连网——即便 MinIO 不可达也应正常返回实现（bucket 惰性确保）
        AttachmentFileStorage storage = AttachmentFileStorages.create(props);
        assertInstanceOf(MinioAttachmentFileStorage.class, storage);
    }

    @Test
    void create_shouldBeCaseInsensitiveForType() {
        AttachmentProperties props = new AttachmentProperties();
        props.getStorage().setType("MinIO");
        AttachmentFileStorage storage = AttachmentFileStorages.create(props);
        assertInstanceOf(MinioAttachmentFileStorage.class, storage);
    }
}
