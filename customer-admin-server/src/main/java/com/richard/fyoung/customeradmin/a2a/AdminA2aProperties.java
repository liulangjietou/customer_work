package com.richard.fyoung.customeradmin.a2a;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A2A 协议导出参数：{@code admin.a2a.*}。
 *
 * <p><b>默认关闭</b>。启用时必须配置专用 Bearer Token。A2A 的价值在跨团队/跨组织互操作——把本系统的智能体发布成任何 A2A 客户端都能
 * 按标准协议调用的服务。当前项目内部还没有这样的消费方（后台智能体之间的协作走的是进程内 subagent，
 * 更便宜也更可控），因此这套导出按"能力就位、按需开启"处理：先把协议适配层落下来并验证依赖与
 * Spring Boot 3 的兼容性，等真出现外部消费方时改一个开关即可，而不是提前把它塞进默认启动路径。</p>
 *
 * <p><b>尚未接入 Nacos AI 注册发现</b>：框架的 {@code agentscope-extensions-nacos-a2a} 要求
 * nacos-client 3.2.1（本项目当前 3.0.2），且 Nacos Server 需升到 3.x（本机为 2.4.3）——那是一次
 * 独立的基础设施升级，不该混在协议适配里做。当前形态是"直连式"导出：外部客户端拿到 Agent Card
 * 的 URL 即可调用，不依赖注册中心。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Component
@ConfigurationProperties(prefix = "admin.a2a")
public class AdminA2aProperties {

    /** 是否启用 A2A 导出。 */
    private boolean enabled = false;

    /** 要导出的智能体编码（{@code ai_agent.agent_code}）。 */
    private String agentCode;

    /** A2A JSON-RPC 专用 Bearer Token；启用时必填，不与后台登录或 Open API Token 复用。 */
    private String token;

    /**
     * 对外可达的服务基地址（含协议与端口，不含路径），写进 Agent Card 的 {@code url} 字段。
     * 必须是<b>外部客户端能访问到</b>的地址：容器/反向代理场景下与本机监听地址往往不同，
     * 拿本机地址糊弄会让客户端拿到一张调不通的名片。
     */
    private String baseUrl = "http://localhost:8082";

    /** Agent Card 里声明的版本号。 */
    private String version = "1.0.0";

    /**
     * Agent Card 里的能力描述。留空则回退到通用说明。
     *
     * <p>单独配而不是取智能体表里的字段：{@code ai_agent} 没有面向外部的能力描述列（只有 agentName
     * 与 systemPrompt），而 systemPrompt 是内部提示词，原样发给外部既泄露实现又帮不上调用方选型。</p>
     */
    private String description;

    /** 常量时间比较 Bearer Token，避免把原始令牌写入任何运行时上下文。 */
    public boolean authenticates(String authorization) {
        if (!StringUtils.hasText(token) || !StringUtils.hasText(authorization)
            || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        byte[] actual = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /** A2A 调用主体只保留令牌摘要，不泄露专用凭据。 */
    public String tokenFingerprint() {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
