package com.richard.fyoung.customerwork.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * Nacos 配置中心驱动的「运行时配置」热更新服务（消费端，对应 admin 8082 下发链路）。
 *
 * <p>{@code customer-work.nacos.runtime-config-enabled=true} 时：启动拉取一次 + 注册监听器，admin 侧
 * 在 Nacos 修改整份运行时配置（模型/兜底/重试/提示词/MCP/maxIters）后<b>无需重启 8080 即热生效</b>。
 * 解析 JSON → 用 {@link AesGcmDecryptor} 解密 API Key 密文 → 交 {@link RuntimeConfigApplier} 原子应用。</p>
 *
 * <p>Nacos 不可用 / 无该配置 / JSON 坏 / 解密失败，均<b>保持 yml 既有行为</b>（不覆盖运行链），
 * 与 {@link NacosPromptService} 的降级语义一致——托管失败不拖垮主链路可用性。默认关闭。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class NacosRuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(NacosRuntimeConfigService.class);

    private static final String CODE_PARSE_FAIL = "RUNTIME-CONFIG-PARSE-FAIL";

    private final CustomerWorkProperties properties;
    private final RuntimeConfigApplier applier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile AesGcmDecryptor decryptor;

    public NacosRuntimeConfigService(CustomerWorkProperties properties, RuntimeConfigApplier applier) {
        this.properties = properties;
        this.applier = applier;
    }

    @PostConstruct
    public void start() {
        CustomerWorkProperties.Nacos cfg = properties.getNacos();
        if (!cfg.isRuntimeConfigEnabled()) {
            return;
        }
        try {
            this.decryptor = new AesGcmDecryptor(cfg.getConfigAesKey());
            ConfigService configService = NacosFactory.createConfigService(buildProperties(cfg));
            bind(configService);
            log.info("[Nacos] runtime config hot-update enabled, dataId={}, group={}",
                cfg.getRuntimeConfigDataId(), cfg.getGroup());
        } catch (Exception e) {
            // Nacos 不可用 / 密钥非法不应阻断启动，保持 yml 行为
            log.error("runtime config subscribe failed, keep yml behavior, code={}", "RUNTIME-CONFIG-SUBSCRIBE-FAIL", e);
        }
    }

    /** 绑定到给定 ConfigService：拉取初始配置并注册热更新监听器（抽出以便单测）。 */
    void bind(ConfigService configService) throws NacosException {
        CustomerWorkProperties.Nacos cfg = properties.getNacos();
        String initial = configService.getConfig(cfg.getRuntimeConfigDataId(), cfg.getGroup(), cfg.getTimeoutMs());
        applyConfig(initial);
        configService.addListener(cfg.getRuntimeConfigDataId(), cfg.getGroup(), new Listener() {
            @Override
            public Executor getExecutor() {
                return Runnable::run;   // 同步回调，简单可控
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                applyConfig(configInfo);
            }
        });
    }

    /**
     * 解析并应用一份运行时配置 JSON。包内可见以便单测直接驱动（不依赖真实 Nacos）。
     *
     * <p>坏 JSON / 解密失败在此收口——记 error 后直接返回，绝不调用 applier，保证旧配置不被覆盖。</p>
     *
     * @param json Nacos 配置正文；空白视为「无配置」直接跳过
     * @return 是否成功应用
     */
    boolean applyConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return false;
        }
        CustomerWorkRuntimeConfig dto;
        try {
            dto = objectMapper.readValue(json, CustomerWorkRuntimeConfig.class);
        } catch (Exception e) {
            log.error("runtime config json parse failed, keep old config, code={}", CODE_PARSE_FAIL, e);
            return false;
        }
        String primaryKey;
        String fallbackKey;
        try {
            primaryKey = decryptIfPresent(dto.getModel() == null ? null : dto.getModel().getApiKeyCipher());
            fallbackKey = decryptIfPresent(dto.getFallback() == null ? null : dto.getFallback().getApiKeyCipher());
        } catch (Exception e) {
            log.error("runtime config api key decrypt failed, keep old config, code={}",
                "RUNTIME-CONFIG-DECRYPT-FAIL", e);
            return false;
        }
        return applier.apply(dto, primaryKey, fallbackKey);
    }

    /** 密文非空则解密，空则返回 null（表示不改动现有密钥）。 */
    private String decryptIfPresent(String cipher) {
        if (!StringUtils.hasText(cipher)) {
            return null;
        }
        return requireDecryptor().decrypt(cipher);
    }

    private AesGcmDecryptor requireDecryptor() {
        AesGcmDecryptor local = this.decryptor;
        if (local == null) {
            // 单测直接调 applyConfig 未走 start() 时按配置懒建
            local = new AesGcmDecryptor(properties.getNacos().getConfigAesKey());
            this.decryptor = local;
        }
        return local;
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
