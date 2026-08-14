package com.richard.fyoung.customeradmin.ticket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 客服工单坐席服务（customer-work-app-server，8080）接入配置。
 *
 * <p>admin-server 作为人工客服后台，通过 HTTP 代理调用 8080 的坐席 API，并给 admin-web 前端
 * 签发"坐席 WS 接入凭证"（客服浏览器凭凭证直连 8080 的 {@code /ws/agent}）。工单数据全部在
 * 8080 侧，admin-server 不建业务表。</p>
 *
 * <p>{@code agentSecret} 必须与 8080 侧共享同一值（同一 env 变量 {@code CW_AGENT_WS_SECRET}），
 * 否则签发的 {@code X-Agent-Token} 无法被 8080 校验通过。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.customer-work")
public class CustomerWorkClientProperties {

    /** 坐席 API 基础地址（8080）。 */
    private String baseUrl = "http://localhost:8080";

    /** 坐席 WebSocket 接入地址（客服浏览器凭凭证直连）。 */
    private String wsUrl = "ws://localhost:8080/ws/agent";

    /** 坐席令牌签名密钥：必须与 8080 侧共享同一值。 */
    private String agentSecret = "dev-agent-secret-change-me-0001";

    /**
     * 运营侧 API Key：调 8080 的运营类端点（如评测触发）时带在 {@code X-API-Key} 头上。
     *
     * <p>与 {@link #agentSecret} 是两条不同的身份线：坐席令牌代表"某个客服在操作工单"，
     * 而评测这类动作是运营方对整套系统的操作、与具体坐席无关，硬塞一个坐席身份进去会让
     * 8080 侧的审计把运营行为记成某个客服干的。对应 8080 的
     * {@code customer-work.security.auth.api-keys}；未开启鉴权时留空即可。</p>
     */
    private String apiKey = "";

    /** WS 接入凭证有效期（小时）：签发给前端的长有效期凭证，比单次 HTTP 请求令牌长。 */
    private long credentialExpireHours = 12;

    /** 连接 8080 的连接超时（毫秒）。 */
    private int connectTimeoutMs = 5000;

    /** 调用 8080 的读取超时（毫秒）。 */
    private int readTimeoutMs = 30000;
}
