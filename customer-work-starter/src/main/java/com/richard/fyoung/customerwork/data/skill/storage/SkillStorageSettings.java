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
