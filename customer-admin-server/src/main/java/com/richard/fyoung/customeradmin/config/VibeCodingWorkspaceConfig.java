package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customeradmin.workspace.runtime.SessionWorkspaceStorage;
import com.richard.fyoung.customerwork.data.attachment.MinioAttachmentFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * VibeCoding 会话工作区持久化装配。
 *
 * <p>复用 starter 的 {@link MinioAttachmentFileStorage}（同一套惰性 bucket 语义），但指向<b>独立 bucket</b>——
 * 产出物与聊天附件的生命周期和备份策略不同，混在一个桶里运维不好切分。构造不连网，
 * MinIO 不可达不影响 admin 启动。</p>
 *
 * <p>{@code enabled=false} 时返回 {@code null}（NullBean），使用侧的 {@code ObjectProvider#getIfAvailable}
 * 拿到 null 后跳过持久化，产出物仅存于本地临时目录。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class VibeCodingWorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(VibeCodingWorkspaceConfig.class);

    private static final long BYTES_PER_MB = 1024L * 1024L;

    @Bean
    @ConditionalOnMissingBean(SessionWorkspaceStorage.class)
    public SessionWorkspaceStorage sessionWorkspaceStorage(VibeCodingWorkspaceProperties properties) {
        if (!properties.isEnabled()) {
            log.info("vibecoding workspace persistence disabled (产出物仅存本地临时目录，会随清理丢失)");
            return null;
        }
        log.info("vibecoding workspace persistence: minio (endpoint={}, bucket={}, prefix={}, maxArchiveMb={})",
            properties.getEndpoint(), properties.getBucket(), properties.getPrefix(), properties.getMaxArchiveMb());
        MinioAttachmentFileStorage storage = new MinioAttachmentFileStorage(
            properties.getEndpoint(), properties.getAccessKey(), properties.getSecretKey(),
            properties.getBucket(), properties.isAutoCreateBucket());
        return new SessionWorkspaceStorage(storage, properties.getPrefix(),
            properties.getMaxArchiveMb() * BYTES_PER_MB);
    }
}
