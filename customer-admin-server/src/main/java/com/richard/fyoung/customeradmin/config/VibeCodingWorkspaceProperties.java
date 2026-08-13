package com.richard.fyoung.customeradmin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * VibeCoding 会话工作区持久化配置（对象存储，独立 bucket）。
 *
 * <p>会话产出物是用户生成的代码，工作区本身落在系统临时目录（会被 OS 清理），故必须有权威副本。
 * 单独一个 bucket 而不是复用附件桶：产出物的生命周期、容量特征与备份策略都和聊天附件不同。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.vibecoding.workspace")
public class VibeCodingWorkspaceProperties {

    /**
     * 是否启用工作区持久化。关闭后产出物只存在于本地临时目录，
     * 会随 OS 清理 / 容器销毁丢失——仅在明确不需要保留产出物时才关。
     */
    private boolean enabled = true;
    private String endpoint = "http://localhost:9000";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    /** 存放会话工作区归档的 bucket。 */
    private String bucket = "customer-admin-vibecoding";
    /** 对象 key 前缀，实际 key = {prefix}{agentCode}/{sessionId}.tar.gz。 */
    private String prefix = "workspaces/";
    /** bucket 不存在时是否自动创建。 */
    private boolean autoCreateBucket = true;
    /**
     * 单个会话归档的大小上限（MB）：归档在内存里构建，不封顶会被一个巨大的工作区打爆。
     * 超限时跳过保存并记 error（产出物仍在本地，但不再有权威副本，需要运维介入）。
     */
    private int maxArchiveMb = 100;
}
