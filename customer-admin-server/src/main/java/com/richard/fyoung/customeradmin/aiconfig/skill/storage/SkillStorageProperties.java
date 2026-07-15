package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Skill 多存储目标配置：{@code admin.skill.storage.*}。
 *
 * <p>{@link Local} 本地 workspace 始终启用；{@link Nacos}/{@link Sftp} 默认关闭，
 * 仅 {@code enabled=true} 时由 {@code SkillStorageConfig} 注册对应 Publisher。
 * 连接密码走环境变量注入，不入库、不硬编码。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.skill.storage")
public class SkillStorageProperties {

    private Local local = new Local();
    private Nacos nacos = new Nacos();
    private Sftp sftp = new Sftp();

    /** 本地 workspace 目标（默认且始终可用）。 */
    @Data
    public static class Local {
        /** SKILL.md 落盘根目录，实际写入 {base-dir}/{skillCode}/SKILL.md。 */
        private String baseDir = "./data/admin-workspace/skills";
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
