package com.richard.fyoung.customeradmin.aiconfig.channel;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 客服机器人运行时配置发布配置：{@code admin.runtime-publish.*}。
 *
 * <p>默认关闭（{@code nacos.enabled=false}）——不影响任何现有后台功能；开启后 admin 在智能体/模型
 * 被改动且命中渠道绑定时，把运行时配置发布到 Nacos，供 8080 客服机器人监听热生效。密文里的 API Key
 * 用 admin 的 {@code ADMIN_AES_SECRET_KEY} 加密后原样携带，8080 侧用同一密钥解密。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.runtime-publish")
public class RuntimePublishProperties {

    private Nacos nacos = new Nacos();

    /** Nacos 配置中心发布目标（与 starter 消费端 {@code customer-work.nacos.*} 对齐）。 */
    @Data
    public static class Nacos {
        private boolean enabled = false;
        private String serverAddr = "localhost:8848";
        private String namespace;
        private String group = "DEFAULT_GROUP";
        /** 运行时配置 dataId（须与 8080 的 {@code customer-work.nacos.runtime-config-data-id} 一致）。 */
        private String dataId = "customer-work-runtime-config";
        private String username;
        private String password;
        private long timeoutMs = 3000;
    }
}
