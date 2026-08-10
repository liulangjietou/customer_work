package com.richard.fyoung.customerwork.data.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AttachmentFileStorage} 静态工厂：按 {@code customer-work.attachment.storage.type} 选择存储实现。
 *
 * <p>选型逻辑只写这一份，starter 的 {@link AttachmentConfig} 与 admin 的 {@code AdminAttachmentConfig} 都调它，
 * 避免两处装配各自判 type 而漂移（照 {@link VisionOcrServices} 的模式）。两种后端：<br>
 * - {@code local}（默认）：本地磁盘 {@link LocalAttachmentFileStorage}（base-dir）。<br>
 * - {@code minio}：MinIO 对象存储 {@link MinioAttachmentFileStorage}，构造不连网、bucket 惰性确保，
 *   MinIO 不可达不影响应用启动。</p>
 * @author owlzhangfq@gmail.com
 */
public final class AttachmentFileStorages {

    private static final Logger log = LoggerFactory.getLogger(AttachmentFileStorages.class);

    /** 存储后端标识：MinIO 对象存储。 */
    private static final String TYPE_MINIO = "minio";

    private AttachmentFileStorages() {
    }

    /**
     * 按配置的 storage.type 创建附件文件存储。
     *
     * @param properties 附件配置（取 {@code storage.type} 与对应后端子配置；local 用顶层 base-dir）
     * @return 选定后端的 {@link AttachmentFileStorage} 实现
     */
    public static AttachmentFileStorage create(AttachmentProperties properties) {
        AttachmentProperties.Storage storage = properties.getStorage();
        if (TYPE_MINIO.equalsIgnoreCase(storage.getType())) {
            AttachmentProperties.Minio minio = storage.getMinio();
            log.info("attachment file storage: minio (endpoint={}, bucket={}, auto-create={})",
                minio.getEndpoint(), minio.getBucket(), minio.isAutoCreateBucket());
            return new MinioAttachmentFileStorage(minio.getEndpoint(), minio.getAccessKey(),
                minio.getSecretKey(), minio.getBucket(), minio.isAutoCreateBucket());
        }
        log.info("attachment file storage: local (base-dir={})", properties.getBaseDir());
        return new LocalAttachmentFileStorage(properties.getBaseDir());
    }
}
