package com.richard.fyoung.customerwork.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Nacos 配置中心驱动的系统提示词服务（对应「接入 Nacos」）。
 *
 * <p>把客服 Agent 的系统提示词托管到 Nacos：启动时拉取一次并注册监听器，运营侧在 Nacos 控制台
 * 修改提示词后<b>无需重启服务即热更新</b>（话术调优 / A/B）。Nacos 不可用或未配置该项时，
 * 回退到代码内置提示词，保证可用性。</p>
 *
 * <p>默认关闭（{@code customer-work.nacos.enabled=false}）；A2A Agent Card 注册发现需 Nacos AI API
 * 与 a2a SDK，作为扩展点见 README。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class NacosPromptService {

    private static final Logger log = LoggerFactory.getLogger(NacosPromptService.class);

    private final CustomerWorkProperties properties;
    private volatile String cachedPrompt;

    public NacosPromptService(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        CustomerWorkProperties.Nacos cfg = properties.getNacos();
        if (!cfg.isEnabled()) {
            return;
        }
        try {
            ConfigService configService = NacosFactory.createConfigService(buildProperties(cfg));
            bind(configService);
            log.info("[Nacos] 已接入配置中心，提示词 dataId={} group={}",
                cfg.getPromptDataId(), cfg.getGroup());
        } catch (Exception e) {
            // Nacos 不可用不应阻断启动，回退内置提示词
            log.warn("[Nacos] 接入失败，回退内置提示词: {}", e.getMessage());
        }
    }

    /**
     * 绑定到给定 ConfigService：拉取初始提示词并注册热更新监听器（抽出以便单测）。
     */
    void bind(ConfigService configService) throws NacosException {
        CustomerWorkProperties.Nacos cfg = properties.getNacos();
        String initial = configService.getConfig(cfg.getPromptDataId(), cfg.getGroup(), cfg.getTimeoutMs());
        if (StringUtils.hasText(initial)) {
            cachedPrompt = initial;
        }
        configService.addListener(cfg.getPromptDataId(), cfg.getGroup(), new Listener() {
            @Override
            public Executor getExecutor() {
                return Runnable::run;   // 同步回调，简单可控
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                if (StringUtils.hasText(configInfo)) {
                    cachedPrompt = configInfo;
                    log.info("[Nacos] 系统提示词已热更新（{} 字）", configInfo.length());
                }
            }
        });
    }

    /** 当前生效的系统提示词（来自 Nacos）；未配置 / 不可用时为空，由调用方回退内置。 */
    public Optional<String> currentPrompt() {
        return Optional.ofNullable(cachedPrompt).filter(StringUtils::hasText);
    }

    private Properties buildProperties(CustomerWorkProperties.Nacos cfg) {
        Properties props = new Properties();
        props.put(PropertyKeyConst.SERVER_ADDR, cfg.getServerAddr());
        if (StringUtils.hasText(cfg.getNamespace())) {
            props.put(PropertyKeyConst.NAMESPACE, cfg.getNamespace());
        }
        if (StringUtils.hasText(cfg.getUsername())) {
            props.put(PropertyKeyConst.USERNAME, cfg.getUsername());
            props.put(PropertyKeyConst.PASSWORD, cfg.getPassword());
        }
        return props;
    }
}
