package com.richard.fyoung.customerwork.infra.config;

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
import com.richard.fyoung.customerwork.infra.config.properties.NacosProperties;

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
    /** 独立 prompt dataId 的当前值。 */
    private volatile String nacosPrompt;
    /** 整份运行时配置的覆盖值；为空时回落 nacosPrompt/代码内置提示词。 */
    private volatile String runtimePromptOverride;

    public NacosPromptService(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        NacosProperties cfg = properties.getNacos();
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
            log.error("[Nacos] prompt service fallback to builtin, code={}, dataId={}", "NACOS_PROMPT_FALLBACK",
                cfg.getPromptDataId(), e);
        }
    }

    /**
     * 绑定到给定 ConfigService：拉取初始提示词并注册热更新监听器（抽出以便单测）。
     */
    void bind(ConfigService configService) throws NacosException {
        NacosProperties cfg = properties.getNacos();
        String initial = configService.getConfig(cfg.getPromptDataId(), cfg.getGroup(), cfg.getTimeoutMs());
        if (StringUtils.hasText(initial)) {
            nacosPrompt = initial;
        }
        configService.addListener(cfg.getPromptDataId(), cfg.getGroup(), new Listener() {
            @Override
            public Executor getExecutor() {
                return Runnable::run;   // 同步回调，简单可控
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                if (StringUtils.hasText(configInfo)) {
                    nacosPrompt = configInfo;
                    log.info("[Nacos] 系统提示词已热更新（{} 字）", configInfo.length());
                    return;
                }
                nacosPrompt = null;
                log.info("[Nacos] system prompt dataId cleared, fallback source will be used");
            }
        });
    }

    /** 当前提示词优先取完整运行时覆盖，其次取独立 prompt dataId，均无值时由调用方回退内置。 */
    public Optional<String> currentPrompt() {
        String override = runtimePromptOverride;
        if (StringUtils.hasText(override)) {
            return Optional.of(override);
        }
        return Optional.ofNullable(nacosPrompt).filter(StringUtils::hasText);
    }

    /**
     * 外部热配置（运行时配置整体下发）覆盖系统提示词。
     *
     * <p>与本类自身监听的 prompt dataId 是两条独立来源：整体运行时配置（{@code NacosRuntimeConfigService}）
     * 携带的 systemPrompt 经此写入独立覆盖层，配合 {@code CustomerServiceService#flushHotAgents} 让
     * 重建的 Agent 立即用上新提示词。传入 null/空白表示清除覆盖并回落独立 prompt dataId 或内置提示词。</p>
     *
     * @param prompt 新系统提示词；null/空白表示清除运行时覆盖
     */
    public void updatePrompt(String prompt) {
        if (StringUtils.hasText(prompt)) {
            runtimePromptOverride = prompt;
            log.info("[Nacos] system prompt overridden by runtime config, length={}", prompt.length());
            return;
        }
        runtimePromptOverride = null;
        log.info("[Nacos] runtime system prompt override cleared");
    }

    private Properties buildProperties(NacosProperties cfg) {
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
