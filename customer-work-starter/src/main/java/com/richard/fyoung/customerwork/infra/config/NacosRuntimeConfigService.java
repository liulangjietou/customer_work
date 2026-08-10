package com.richard.fyoung.customerwork.infra.config;

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

    /**
     * 绑定到给定 ConfigService：拉取初始配置并注册热更新监听器（抽出以便单测）。
     *
     * <p><b>灰度优先</b>：配了 {@code nacos.tenant-code} 时先看租户专属 dataId
     * （{@code <主dataId>-tenant-<租户码>}），有内容就用它，没有才回落主 dataId。
     * 灰度因此对本端是透明的——本端并不理解"灰度"，只是多试了一个更具体的 dataId；
     * 运营方把灰度版本写进那个 dataId，名单外的实例自然继续用主 dataId 上的全量版本。</p>
     *
     * <p>两个 dataId 都要挂监听：灰度期间运营方可能改灰度版本，灰度结束后又会删掉它——
     * 只听一个的话，要么灰度更新收不到，要么灰度撤销后回不到全量版本。</p>
     */
    void bind(ConfigService configService) throws NacosException {
        CustomerWorkProperties.Nacos cfg = properties.getNacos();
        String mainDataId = cfg.getRuntimeConfigDataId();
        String tenantDataId = tenantDataId(cfg);

        String initial = null;
        if (tenantDataId != null) {
            initial = configService.getConfig(tenantDataId, cfg.getGroup(), cfg.getTimeoutMs());
            if (StringUtils.hasText(initial)) {
                log.info("[Nacos] gray config applied, dataId={}", tenantDataId);
            }
        }
        if (!StringUtils.hasText(initial)) {
            initial = configService.getConfig(mainDataId, cfg.getGroup(), cfg.getTimeoutMs());
        }
        applyConfig(initial);

        configService.addListener(mainDataId, cfg.getGroup(), new Listener() {
            @Override
            public Executor getExecutor() {
                return Runnable::run;   // 同步回调，简单可控
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                applyConfig(configInfo);
            }
        });

        if (tenantDataId != null) {
            configService.addListener(tenantDataId, cfg.getGroup(), new Listener() {
                @Override
                public Executor getExecutor() {
                    return Runnable::run;
                }

                @Override
                public void receiveConfigInfo(String configInfo) {
                    // 灰度被撤销时 Nacos 回调的是空串：此时不 applyConfig（那会被当成"无配置"跳过），
                    // 而是主动回读主 dataId 恢复全量版本，否则实例会一直停在灰度版本上
                    if (StringUtils.hasText(configInfo)) {
                        applyConfig(configInfo);
                        return;
                    }
                    restoreFromMainDataId(configService, cfg);
                }
            });
        }
    }

    /** 灰度撤销后回到全量版本。读取失败只记日志——保持当前配置总比清空好。 */
    private void restoreFromMainDataId(ConfigService configService, CustomerWorkProperties.Nacos cfg) {
        try {
            String main = configService.getConfig(cfg.getRuntimeConfigDataId(), cfg.getGroup(), cfg.getTimeoutMs());
            if (StringUtils.hasText(main)) {
                applyConfig(main);
                log.info("[Nacos] gray config removed, restored from main dataId={}", cfg.getRuntimeConfigDataId());
            }
        } catch (Exception e) {
            log.error("restore runtime config from main dataId failed, code={}",
                "RUNTIME-CONFIG-RESTORE-FAIL", e);
        }
    }

    /** 租户专属 dataId；未配租户码时返回 null（单租户部署不受灰度机制影响）。 */
    private String tenantDataId(CustomerWorkProperties.Nacos cfg) {
        String tenantCode = cfg.getTenantCode();
        if (tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        return cfg.getRuntimeConfigDataId() + "-tenant-" + tenantCode.trim();
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
