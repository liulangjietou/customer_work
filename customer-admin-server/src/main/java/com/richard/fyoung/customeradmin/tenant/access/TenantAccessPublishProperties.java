package com.richard.fyoung.customeradmin.tenant.access;

import com.richard.fyoung.customerwork.safety.tenant.TenantAccessConstants;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 租户访问快照发布配置（{@code admin.tenant.access-publish.*}）。 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.tenant.access-publish")
public class TenantAccessPublishProperties {

    /** 空表示继承既有 runtime-publish.nacos.enabled；显式配置时可独立停启访问快照 Worker。 */
    private Boolean enabled;
    private long scanIntervalMs = 1000;
    private long leaseMs = 30000;
    private int batchSize = 20;
    /** 0 表示无限重试；安全撤权默认不能因短时故障永久放弃。 */
    private int maxAttempts = 0;
    private long baseBackoffMs = 1000;
    private long maxBackoffMs = 60000;
    /** 独立于模型配置的基础 dataId；实际按租户追加 {@code -tenant-{tenantId}}。 */
    private String dataId = TenantAccessConstants.DEFAULT_DATA_ID;

    public boolean deliveryEnabled(RuntimePublishProperties runtimePublishProperties) {
        return enabled == null
            ? runtimePublishProperties.getNacos().isEnabled()
            : enabled;
    }
}
