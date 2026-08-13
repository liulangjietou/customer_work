package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Skill 多存储目标配置：{@code admin.skill.storage.*}。
 *
 * <p>{@link Local} 本地 workspace 始终启用；{@link Nacos}/{@link Sftp} 默认关闭，
 * 仅 {@code enabled=true} 时由 {@link SkillStorageConfig} 注册对应 Publisher。
 * 连接密码走环境变量注入，不入库、不硬编码。</p>
 *
 * <p>发布器实现已下沉 starter，本类只留在 admin 侧承接 {@code admin.*} 配置前缀（保持配置兼容），
 * 由 {@link SkillStorageConfig} 映射成 starter 的
 * {@code com.richard.fyoung.customerwork.data.skill.storage.SkillStorageSettings} 后建实例。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.skill.storage")
public class SkillStorageProperties {

    private Minio minio = new Minio();
    private Nacos nacos = new Nacos();
    private Sftp sftp = new Sftp();

    /** MinIO 对象存储目标（默认且始终可用；取代已下线的本地 workspace 目标）。 */
    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        /** 存放技能包的 bucket 名。 */
        private String bucket = "customer-admin-skills";
        /** 对象 key 前缀，实际 key = {prefix}{skillCode}/SKILL.md。 */
        private String prefix = "skills/";
        /** bucket 不存在时是否自动创建。 */
        private boolean autoCreateBucket = true;
    }

    /** Nacos 配置中心目标。 */
    @Data
    public static class Nacos {
        private boolean enabled = false;
        private String serverAddr = "localhost:8848";
        private String namespace;
        private String group = "SKILL_GROUP";
        private String username;
        private String password;
        /** dataId 前缀，实际 dataId = {data-id-prefix}{skillCode}.md。 */
        private String dataIdPrefix = "skill-";
        private long timeoutMs = 3000;
    }

    /** SFTP 目录目标。 */
    @Data
    public static class Sftp {
        private boolean enabled = false;
        private String host;
        private int port = 22;
        private String username;
        private String password;
        /** 远端根目录，实际上传 {remote-dir}/{skillCode}/SKILL.md。 */
        private String remoteDir;
        private int timeoutMs = 10000;
        /** 严格主机密钥校验，默认关闭（内网/受控环境降低首次连接门槛）。 */
        private boolean strictHostKeyChecking = false;
    }
}
