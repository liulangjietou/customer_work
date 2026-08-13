package com.richard.fyoung.customerwork.data.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AttachmentFileStorage} 静态工厂：按 {@code customer-work.attachment.minio.*} 构建 MinIO 存储。
 *
 * <p>构建逻辑只写这一份，starter 的 {@link AttachmentConfig} 与 admin 的 {@code AdminAttachmentConfig} 都调它，
 * 避免两处装配各自拼参数而漂移（照 {@link VisionOcrServices} 的模式）。</p>
 *
 * <p><b>为什么只剩 MinIO 一种</b>：文件落本地盘在多副本部署下必然出错——A 机上传的文件 B 机读不到，
 * 容器销毁即丢，且没有任何自动修复的可能。留一个"本地盘"选项只会让人在不知情时踩进去，
 * 故直接删掉而不是留作降级。bucket 惰性确保：构造不连网，MinIO 不可达不影响应用启动，
 * 只在真正上传/读取时失败。</p>
 * @author owlzhangfq@gmail.com
 */
public final class AttachmentFileStorages {

    private static final Logger log = LoggerFactory.getLogger(AttachmentFileStorages.class);

    private AttachmentFileStorages() {
    }

    /**
     * 按配置创建附件文件存储。
     *
     * @param properties 附件配置（取 {@code storage.minio.*} 连接参数）
     * @return MinIO 存储实现
     */
    public static AttachmentFileStorage create(AttachmentProperties properties) {
        AttachmentProperties.Minio minio = properties.getStorage().getMinio();
        log.info("attachment file storage: minio (endpoint={}, bucket={}, auto-create={})",
            minio.getEndpoint(), minio.getBucket(), minio.isAutoCreateBucket());
        return new MinioAttachmentFileStorage(minio.getEndpoint(), minio.getAccessKey(),
            minio.getSecretKey(), minio.getBucket(), minio.isAutoCreateBucket());
    }
}
