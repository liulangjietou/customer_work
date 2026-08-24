package com.richard.fyoung.customerwork.infra.config.properties;

import com.richard.fyoung.customerwork.safety.tenant.TenantAccessConstants;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nacos 接入配置（配置中心）。
 *
 * <p>把 Agent 的系统提示词托管到 Nacos 配置中心，支持运营侧在不重启服务的情况下热更新提示词
 * （A/B、话术调优）。A2A Agent Card 注册发现需 Nacos AI API 与 a2a SDK，见 README 扩展点。</p>
 */
@Data
public class NacosProperties {
    private boolean enabled = false;
    private String serverAddr = "localhost:8848";
    private String namespace = "";
    private String group = "DEFAULT_GROUP";
    /** 系统提示词配置项 dataId。 */
    private String promptDataId = "customer-work-system-prompt";
    private String username = "";
    private String password = "";
    private long timeoutMs = 3000;

    /** 运行时配置首次订阅失败后的重试间隔；Nacos 恢复后无需重启实例。 */
    private long runtimeConfigSubscribeRetryMs = 10000;

    /**
     * 服务注册对外暴露的实例 IP（供网关 lb:// 路由回连本实例）。留空则自动探测本机地址
     * （{@code InetAddress.getLocalHost()}）。多网卡 / 容器场景可用环境变量显式指定可达 IP。
     */
    private String instanceIp = "";

    /**
     * 是否启用「运行时配置」热更新（admin 8082 下发的模型/MCP/提示词整体配置）。默认关闭，
     * 不影响任何未接入的下游；开启后启动拉取 + 监听 {@link #runtimeConfigDataId}，热应用到运行中的 8080。
     */
    private boolean runtimeConfigEnabled = false;
    /** 运行时配置项 dataId（与 admin 发布端 data-id 对齐）。 */
    private String runtimeConfigDataId = "customer-work-runtime-config";
    /**
     * 运行时配置里 API Key 密文的 AES 解密密钥（16/24/32 字节）。生产用环境变量
     * {@code CUSTOMER_WORK_CONFIG_AES_KEY} 注入，且必须与 admin 的 {@code ADMIN_AES_SECRET_KEY} 同值，
     * 否则密文解不开、整份配置被判失败并保留旧配置。
     */
    private String configAesKey = "";

    /** 生产必须开启；消费端先验签、再解密与切换运行态。 */
    private boolean runtimeConfigSignatureRequired = false;
    private String runtimeConfigSigningKeyId = "";
    private String runtimeConfigSigningSecret = "";
    /** 额外可信 keyId -> HMAC secret；轮换窗口用于保留上一把 key。 */
    private Map<String, String> runtimeConfigSigningKeys = new LinkedHashMap<>();

    public Map<String, String> trustedRuntimeConfigSigningKeys() {
        LinkedHashMap<String, String> trusted = new LinkedHashMap<>(runtimeConfigSigningKeys);
        if (runtimeConfigSigningKeyId != null && !runtimeConfigSigningKeyId.isBlank()
            && runtimeConfigSigningSecret != null && !runtimeConfigSigningSecret.isBlank()) {
            trusted.put(runtimeConfigSigningKeyId, runtimeConfigSigningSecret);
        }
        return Map.copyOf(trusted);
    }

    /** 运行时配置应用结果回传地址（admin 的 /api/open/runtime-config/acks）。 */
    private String runtimeConfigAckUrl = "";

    /** ACK 服务实例级鉴权令牌；每个实例必须唯一，不得复用租户通用 Open API token。 */
    private String runtimeConfigAckToken = "";

    /** 实例稳定标识；留空时优先取 HOSTNAME，再回落 JVM 运行实例名。 */
    private String runtimeConfigInstanceId = "";

    /**
     * 本实例所属租户编码，用于隔离运行时配置发布域。
     *
     * <p>配置后只读取 {@code <runtime-config-data-id>-tenant-<租户码>}，缺失或删除时保留最后安全配置，
     * 不回落主 dataId。开启 {@code customer-work.tenant.enabled} 时本项必填；只有单租户部署可留空并读取主 dataId。</p>
     */
    private String tenantCode = "";

    /** 是否消费控制面发布的租户访问快照；开启后非 default 租户缺快照即 fail-closed。 */
    private boolean tenantAccessEnabled = false;

    /** 租户访问快照基础 dataId；实际订阅 {@code <dataId>-tenant-<租户码>}。 */
    private String tenantAccessDataId = TenantAccessConstants.DEFAULT_DATA_ID;

    /** 对已订阅租户主动回读 Nacos 的间隔，用于补偿监听丢失并刷新确认时间。 */
    private long tenantAccessRefreshIntervalMs = 5000;

    /** 可用快照允许失联后继续使用的最长时间；超时后拒绝新请求，0 表示不做失联超时。 */
    private long tenantAccessMaxStalenessMs = 30000;
}
