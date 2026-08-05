package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import com.richard.fyoung.customerwork.skill.storage.SkillContentPublisher;
import com.richard.fyoung.customerwork.skill.storage.SkillContentPublishers;
import com.richard.fyoung.customerwork.skill.storage.SkillStorageSettings;
import com.richard.fyoung.customerwork.skill.storage.SkillStorageTarget;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skill 存储目标装配：local 无条件注册；nacos/sftp 仅 {@code enabled=true} 时注册。
 *
 * <p>发布器实现已下沉 starter（{@code com.richard.fyoung.customerwork.skill.storage}），本类只负责
 * 把 admin 的 {@link SkillStorageProperties}（{@code admin.*} 前缀，配置保持兼容）映射成 starter 的
 * {@link SkillStorageSettings} 并调静态工厂建实例——admin-server 排除了 starter 的自动装配，
 * starter 也刻意不为发布器注册 Bean，故在本模块显式注册。{@code SkillService} 注入全部
 * {@link SkillContentPublisher}，某目标未启用即无对应 Bean，勾选它会被判为"目标未启用"。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SkillStorageConfig {

    @Bean
    public SkillContentPublisher localWorkspaceSkillPublisher(SkillStorageProperties properties) {
        return SkillContentPublishers.create(SkillStorageTarget.LOCAL, toSettings(properties));
    }

    @Bean
    @ConditionalOnProperty(prefix = "admin.skill.storage.nacos", name = "enabled", havingValue = "true")
    public SkillContentPublisher nacosSkillPublisher(SkillStorageProperties properties) {
        return SkillContentPublishers.create(SkillStorageTarget.NACOS, toSettings(properties));
    }

    @Bean
    @ConditionalOnProperty(prefix = "admin.skill.storage.sftp", name = "enabled", havingValue = "true")
    public SkillContentPublisher sftpSkillPublisher(SkillStorageProperties properties) {
        return SkillContentPublishers.create(SkillStorageTarget.SFTP, toSettings(properties));
    }

    /** admin 配置 → starter 入参映射（{@code enabled} 是本模块的装配开关，不属于连接参数，不映射）。 */
    static SkillStorageSettings toSettings(SkillStorageProperties properties) {
        SkillStorageSettings settings = new SkillStorageSettings();
        settings.getLocal().setBaseDir(properties.getLocal().getBaseDir());

        SkillStorageProperties.Nacos nacos = properties.getNacos();
        SkillStorageSettings.Nacos nacosSettings = settings.getNacos();
        nacosSettings.setServerAddr(nacos.getServerAddr());
        nacosSettings.setNamespace(nacos.getNamespace());
        nacosSettings.setGroup(nacos.getGroup());
        nacosSettings.setUsername(nacos.getUsername());
        nacosSettings.setPassword(nacos.getPassword());
        nacosSettings.setDataIdPrefix(nacos.getDataIdPrefix());
        nacosSettings.setTimeoutMs(nacos.getTimeoutMs());

        SkillStorageProperties.Sftp sftp = properties.getSftp();
        SkillStorageSettings.Sftp sftpSettings = settings.getSftp();
        sftpSettings.setHost(sftp.getHost());
        sftpSettings.setPort(sftp.getPort());
        sftpSettings.setUsername(sftp.getUsername());
        sftpSettings.setPassword(sftp.getPassword());
        sftpSettings.setRemoteDir(sftp.getRemoteDir());
        sftpSettings.setTimeoutMs(sftp.getTimeoutMs());
        sftpSettings.setStrictHostKeyChecking(sftp.isStrictHostKeyChecking());
        return settings;
    }
}
