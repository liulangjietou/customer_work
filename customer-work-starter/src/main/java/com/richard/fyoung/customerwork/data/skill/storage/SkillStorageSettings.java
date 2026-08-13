package com.richard.fyoung.customerwork.data.skill.storage;

import lombok.Data;

/**
 * Skill 多存储目标的连接参数（纯 POJO，不带 {@code @ConfigurationProperties}）。
 *
 * <p>starter 只提供实现与 {@link SkillContentPublishers} 工厂，配置前缀由宿主模块自己定义
 * （admin 用 {@code admin.skill.storage.*}），把自己的 Properties 映射成本类再调工厂，
 * 这样 starter 不绑定任何一个宿主的配置前缀。字段默认值与 admin 侧保持一致。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class SkillStorageSettings {

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
        private String bucket = "customer-work-skills";
        /** 对象 key 前缀，实际 key = {prefix}{skillCode}/SKILL.md。 */
        private String prefix = "skills/";
        /** bucket 不存在时是否自动创建。 */
        private boolean autoCreateBucket = true;
    }

    /** Nacos 配置中心目标。 */
    @Data
    public static class Nacos {
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
