package com.richard.fyoung.customerwork.data.skill.storage;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * Nacos 配置中心存储：把 SKILL.md 发布为配置项，dataId = {data-id-prefix}{skillCode}.md，group 可配。
 *
 * <p>ConfigService 懒初始化并复用单实例（连接属性构建方式参考 starter 的 {@code NacosPromptService}）。
 * 是否启用由宿主模块的装配决定（admin 侧走 {@code admin.skill.storage.nacos.enabled}）。</p>
 * @author owlzhangfq@gmail.com
 */
public class NacosSkillPublisher implements SkillContentPublisher {

    private static final Logger log = LoggerFactory.getLogger(NacosSkillPublisher.class);

    private static final String DATA_ID_SUFFIX = ".md";

    private final SkillStorageSettings.Nacos config;
    private volatile ConfigService configService;

    public NacosSkillPublisher(SkillStorageSettings.Nacos config) {
        this.config = config;
    }

    @Override
    public SkillStorageTarget target() {
        return SkillStorageTarget.NACOS;
    }

    @Override
    public void publish(String skillCode, String content) {
        try {
            boolean ok = configService().publishConfig(dataId(skillCode), config.getGroup(), content);
            if (!ok) {
                throw new IllegalStateException("nacos publishConfig returned false");
            }
            log.info("nacos skill published, skillCode={}, dataId={}, group={}",
                skillCode, dataId(skillCode), config.getGroup());
        } catch (Exception e) {
            throw new IllegalStateException("publish to nacos failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void remove(String skillCode) {
        try {
            configService().removeConfig(dataId(skillCode), config.getGroup());
            log.info("nacos skill removed, skillCode={}, dataId={}, group={}",
                skillCode, dataId(skillCode), config.getGroup());
        } catch (Exception e) {
            throw new IllegalStateException("remove from nacos failed: " + e.getMessage(), e);
        }
    }

    private String dataId(String skillCode) {
        return config.getDataIdPrefix() + skillCode + DATA_ID_SUFFIX;
    }

    /** 懒初始化并复用单个 ConfigService（NacosFactory 建连不便宜，避免每次操作重建）。 */
    private ConfigService configService() throws Exception {
        ConfigService local = configService;
        if (local == null) {
            synchronized (this) {
                local = configService;
                if (local == null) {
                    local = NacosFactory.createConfigService(buildProperties());
                    configService = local;
                }
            }
        }
        return local;
    }

    private Properties buildProperties() {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, config.getServerAddr());
        if (StringUtils.hasText(config.getNamespace())) {
            props.put(PropertyKeyConst.NAMESPACE, config.getNamespace());
        }
        if (StringUtils.hasText(config.getUsername())) {
            props.put(PropertyKeyConst.USERNAME, config.getUsername());
            props.put(PropertyKeyConst.PASSWORD, config.getPassword());
        }
        return props;
    }
}
