package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skill 存储目标装配：local 无条件注册；nacos/sftp 仅 {@code enabled=true} 时注册。
 *
 * <p>admin-server 排除了 starter 的自动装配，故这些 Bean 在本模块显式注册（参考 starter 的
 * 条件装配范式，但用 {@link ConditionalOnProperty} 按 enabled 开关控制）。{@code SkillService}
 * 注入全部 {@link SkillContentPublisher}，某目标未启用即无对应 Bean，勾选它会被判为"目标未启用"。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SkillStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillStorageConfig.class);

    @Bean
    public LocalWorkspaceSkillPublisher localWorkspaceSkillPublisher(SkillStorageProperties properties) {
        log.info("skill storage publisher enabled: local, base-dir={}", properties.getLocal().getBaseDir());
        return new LocalWorkspaceSkillPublisher(properties.getLocal().getBaseDir());
    }

    @Bean
    @ConditionalOnProperty(prefix = "admin.skill.storage.nacos", name = "enabled", havingValue = "true")
    public NacosSkillPublisher nacosSkillPublisher(SkillStorageProperties properties) {
        log.info("skill storage publisher enabled: nacos, server-addr={}, group={}",
            properties.getNacos().getServerAddr(), properties.getNacos().getGroup());
        return new NacosSkillPublisher(properties.getNacos());
    }

    @Bean
    @ConditionalOnProperty(prefix = "admin.skill.storage.sftp", name = "enabled", havingValue = "true")
    public SftpSkillPublisher sftpSkillPublisher(SkillStorageProperties properties) {
        log.info("skill storage publisher enabled: sftp, host={}, remote-dir={}",
            properties.getSftp().getHost(), properties.getSftp().getRemoteDir());
        return new SftpSkillPublisher(properties.getSftp());
    }
}
