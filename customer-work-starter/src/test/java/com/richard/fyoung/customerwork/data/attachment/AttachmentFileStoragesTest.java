package com.richard.fyoung.customerwork.data.attachment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link AttachmentFileStorages} 构建测试：MinIO 是唯一后端，且构造不触网。
 *
 * <p>全程离线——{@link MinioAttachmentFileStorage} 构造只建客户端、bucket 惰性确保，
 * 故 MinIO 不可达也应正常返回实现（应用因此不会因对象存储缺席而起不来）。
 * 真实往返在 {@link MinioAttachmentFileStorageIntegrationTest}（门控）覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
class AttachmentFileStoragesTest {

    @Test
    void create_shouldAlwaysReturnMinio() {
        AttachmentProperties props = new AttachmentProperties();

        AttachmentFileStorage storage = AttachmentFileStorages.create(props);

        assertInstanceOf(MinioAttachmentFileStorage.class, storage);
    }

    @Test
    void create_shouldNotTouchNetwork_whenMinioUnreachable() {
        AttachmentProperties props = new AttachmentProperties();
        // 指向一个必然连不上的端点：构造仍应成功（bucket 惰性确保）
        props.getStorage().getMinio().setEndpoint("http://127.0.0.1:1");
        props.getStorage().getMinio().setBucket("cw-attachment-it");

        AttachmentFileStorage storage = AttachmentFileStorages.create(props);

        assertInstanceOf(MinioAttachmentFileStorage.class, storage);
    }
}
