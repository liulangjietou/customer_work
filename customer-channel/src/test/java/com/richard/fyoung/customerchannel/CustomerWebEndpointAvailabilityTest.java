package com.richard.fyoung.customerchannel;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * customer-channel 五套前端端点可达性测试（HTTP 层，离线确定性）。
 *
 * <p><b>覆盖边界（诚实说明）</b>：本测试验证各前端端点<b>真实注册、HTTP 可达、OpenAPI 装配</b>，
 * 不触发真实模型调用。真实对话回复（chat-completions 同步/流式）、AG-UI 事件序列、Channel 真实签名
 * 收发依赖真实模型 Key / 公网回调地址 / 真实签名，属外部依赖，不在离线单测覆盖范围
 * （见 docs/customer-channel操作文档.md 的真机自测记录）。</p>
 *
 * <p><b>关于 Channel inbound</b>：飞书 inbound controller 无条件注册（此处可测其可达性）；企业微信
 * inbound 端点映射依赖 {@code customer-channel.channel.wecom.enabled=true}，测试配置未启用故不在此覆盖——
 * 其 Bean 装配由 {@link CustomerWebIntegrationTest} 验证。</p>
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerWebEndpointAvailabilityTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void actuatorHealth_shouldBeReachable() {
        // 离线环境下部分 health indicator 可能 DOWN（外部依赖不可达）→ 200 或 503 均说明端点可达
        ResponseEntity<String> r = rest.getForEntity("/actuator/health", String.class);
        int code = r.getStatusCode().value();
        assertTrue(code == 200 || code == 503, "actuator health 端点应可达: " + r.getStatusCode());
    }

    @Test
    void openApiDocs_shouldBeAvailable() {
        ResponseEntity<String> r = rest.getForEntity("/v3/api-docs", String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), "OpenAPI 文档应装配: " + r.getStatusCode());
    }

    @Test
    void chatCompletionsEndpoint_shouldBeRegistered() {
        // POST-only 端点：GET 命中说明路由已注册（非 404），不触发模型对话
        ResponseEntity<String> r = rest.getForEntity("/v1/chat/completions", String.class);
        assertNotEquals(404, r.getStatusCode().value(), "chat-completions 端点应注册");
    }

    @Test
    void aguiEndpoint_shouldBeRegistered() {
        ResponseEntity<String> r = rest.getForEntity("/agui/run", String.class);
        assertNotEquals(404, r.getStatusCode().value(), "AG-UI /agui/run 端点应注册");
    }

    @Test
    void feishuInboundCallback_shouldBeRegistered() {
        // 空 body 会被框架控制器拒绝(4xx/5xx)，但非 404 即证明 inbound 路由已注册
        ResponseEntity<String> r = rest.postForEntity(
            "/api/channels/feishu/default/callback", "{}", String.class);
        assertNotEquals(404, r.getStatusCode().value(), "飞书 inbound 回调端点应注册");
    }
}
