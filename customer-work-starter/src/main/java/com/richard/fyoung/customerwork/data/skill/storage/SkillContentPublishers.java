package com.richard.fyoung.customerwork.data.skill.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SkillContentPublisher} 静态工厂：按 {@link SkillStorageTarget} 选择发布实现
 * （照 {@code AttachmentFileStorages.create} 的范式）。
 *
 * <p>starter 不为这些实现注册 Bean——目标是否启用、用什么配置前缀，是宿主模块的装配决策
 * （admin 走 {@code admin.skill.storage.*} + {@code @ConditionalOnProperty}）。宿主把自己的
 * Properties 映射成 {@link SkillStorageSettings} 后调本工厂，选型与建实例的逻辑只写这一份。</p>
 * @author owlzhangfq@gmail.com
 */
public final class SkillContentPublishers {

    private static final Logger log = LoggerFactory.getLogger(SkillContentPublishers.class);

    private SkillContentPublishers() {
    }

    /**
     * 按存储目标创建发布器；构造不建连（Nacos 懒初始化 ConfigService、SFTP 每次操作现连），
     * 外部服务不可达不影响宿主启动。
     *
     * @param target   存储目标
     * @param settings 三个目标的连接参数（只读取 {@code target} 对应的那一段）
     * @return 该目标的 {@link SkillContentPublisher} 实现
     */
    public static SkillContentPublisher create(SkillStorageTarget target, SkillStorageSettings settings) {
        return switch (target) {
            case LOCAL -> {
                SkillStorageSettings.Local local = settings.getLocal();
                log.info("skill content publisher: local (base-dir={})", local.getBaseDir());
                yield new LocalWorkspaceSkillPublisher(local.getBaseDir());
            }
            case NACOS -> {
                SkillStorageSettings.Nacos nacos = settings.getNacos();
                log.info("skill content publisher: nacos (server-addr={}, group={})",
                    nacos.getServerAddr(), nacos.getGroup());
                yield new NacosSkillPublisher(nacos);
            }
            case SFTP -> {
                SkillStorageSettings.Sftp sftp = settings.getSftp();
                log.info("skill content publisher: sftp (host={}, remote-dir={})",
                    sftp.getHost(), sftp.getRemoteDir());
                yield new SftpSkillPublisher(sftp);
            }
        };
    }
}
