package com.richard.fyoung.customeradmin.tenant.access;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.tenant.access.entity.TenantAccessPublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Properties;

/** 把单租户访问快照发布到独立 Nacos dataId。 */
@Component
public class TenantAccessPublisher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TenantAccessPublisher.class);

    private final RuntimePublishProperties runtimePublishProperties;
    private final ObjectMapper objectMapper;
    private final ConfigServiceFactory configServiceFactory;
    private volatile ConfigService configService;

    @Autowired
    public TenantAccessPublisher(RuntimePublishProperties runtimePublishProperties,
                                 ObjectMapper objectMapper) {
        this(runtimePublishProperties, objectMapper, NacosFactory::createConfigService);
    }

    TenantAccessPublisher(RuntimePublishProperties runtimePublishProperties,
                          ObjectMapper objectMapper,
                          ConfigServiceFactory configServiceFactory) {
        this.runtimePublishProperties = runtimePublishProperties;
        this.objectMapper = objectMapper;
        this.configServiceFactory = configServiceFactory;
    }

    public void publish(TenantAccessPublishTask task) {
        TenantAccessPayload payload = new TenantAccessPayload(
            TenantAccessConstants.SCHEMA_VERSION,
            task.getTenantId(),
            task.getTenantStatus(),
            task.getAccessEpoch(),
            task.getExpireTime() == null ? null : task.getExpireTime().toString(),
            task.getCreatedAtMs());
        try {
            String json = objectMapper.writeValueAsString(payload);
            boolean published = configService().publishConfig(task.getDataId(), task.getGroupName(), json);
            if (!published) {
                throw new IllegalStateException("nacos publishConfig returned false, dataId=" + task.getDataId());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("publish tenant access snapshot failed: " + e.getMessage(), e);
        }
    }

    private ConfigService configService() throws Exception {
        ConfigService local = configService;
        if (local == null) {
            synchronized (this) {
                local = configService;
                if (local == null) {
                    local = configServiceFactory.create(buildProperties());
                    configService = local;
                }
            }
        }
        return local;
    }

    private Properties buildProperties() {
        RuntimePublishProperties.Nacos nacos = runtimePublishProperties.getNacos();
        Properties result = new Properties();
        result.put(PropertyKeyConst.SERVER_ADDR, nacos.getServerAddr());
        if (StringUtils.hasText(nacos.getNamespace())) {
            result.put(PropertyKeyConst.NAMESPACE, nacos.getNamespace());
        }
        if (StringUtils.hasText(nacos.getUsername())) {
            result.put(PropertyKeyConst.USERNAME, nacos.getUsername());
            result.put(PropertyKeyConst.PASSWORD, nacos.getPassword());
        }
        return result;
    }

    @Override
    public void destroy() {
        ConfigService client = configService;
        if (client == null) {
            return;
        }
        try {
            client.shutDown();
        } catch (Exception e) {
            log.error("tenant access Nacos publisher shutdown failed, code={}",
                "TENANT-ACCESS-NACOS-PUBLISHER-SHUTDOWN-FAIL", e);
        }
    }

    @FunctionalInterface
    interface ConfigServiceFactory {
        ConfigService create(Properties properties) throws Exception;
    }

    private record TenantAccessPayload(
        int schemaVersion,
        String tenantId,
        String status,
        long accessEpoch,
        String expireTime,
        long changedAtMs
    ) {
    }
}
