package com.richard.fyoung.customerwork.infra.config.properties;

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

    /**
     * 本实例所属租户编码，用于接收<b>按租户灰度</b>的运行时配置。
     *
     * <p>配了就先读 {@code <runtime-config-data-id>-tenant-<租户码>}，读不到才回落主 dataId。
     * 本端并不理解"灰度"，只是多试了一个更具体的 dataId；运营方把灰度版本写进那个 dataId，
     * 名单外的实例自然继续用主 dataId 上的全量版本。留空即不参与灰度（单租户部署无需配置）。</p>
     */
    private String tenantCode = "";
}
