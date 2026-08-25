package com.richard.fyoung.customeradmin.aiconfig.mcp.runtime;

import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.config.AdminMcpSecurityProperties;
import com.richard.fyoung.customerwork.tool.mcp.McpSecurityPolicy;
import com.richard.fyoung.customerwork.tool.mcp.McpConnectivityResult;
import com.richard.fyoung.customerwork.tool.mcp.McpImageContent;
import com.richard.fyoung.customerwork.tool.mcp.McpToolCallResult;
import com.richard.fyoung.customerwork.tool.mcp.McpToolDescriptor;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminMcpFactory} 单测。解析构建/连通性/调试调用的行为已下沉 starter，由
 * {@code McpClientFactoryTest} 覆盖；这里只验证 admin 侧薄壳自己的职责——
 * starter 中立返回类型 -&gt; admin 接口 VO 的转换（前端契约），以及委托链路确实接通。
 * @author owlzhangfq@gmail.com
 */
class AdminMcpFactoryTest {

    private final AdminMcpFactory factory = new AdminMcpFactory(McpSecurityPolicy.strict());

    /**
     * 防回归：容器装配出来的实例必须<b>从配置读白名单</b>，不能回退到 {@link McpSecurityPolicy#strict()}。
     *
     * <p>本类有多个构造器，Spring 在缺 {@code @Autowired} 时会挑无参构造器而不是参数最多的那个。
     * 此前就是这样：容器拿到的是 strict 策略，白名单恒为空，{@code admin.mcp.security.allowed-hosts}
     * 配了 localhost 也没用，页面上表现为「目标地址指向内网/环回，已拦截: localhost」。
     * 这个断言直接验证「配了就能过」，比断言注解存在更贴近真实故障。</p>
     */
    @Test
    void springWiredInstance_shouldHonourConfiguredAllowlist() {
        AdminMcpSecurityProperties properties = new AdminMcpSecurityProperties();
        properties.setAllowedHosts(List.of("localhost"));

        AdminMcpFactory wired = new AdminMcpFactory(properties);

        assertDoesNotThrow(() -> wired.validateConfiguration("oa", "http",
                "{\"mcpServers\": {\"oa\": {\"url\": \"http://localhost:3002/mcp\"}}}"),
            "白名单已含 localhost，环回地址应放行");
    }

    /** 反过来：白名单为空时环回必须仍被拦，确认放行来自配置而不是策略本身被削弱。 */
    @Test
    void emptyAllowlist_shouldStillRejectLoopback() {
        AdminMcpFactory strictFactory = new AdminMcpFactory(new AdminMcpSecurityProperties());

        assertThrows(Exception.class, () -> strictFactory.validateConfiguration("oa", "http",
                "{\"mcpServers\": {\"oa\": {\"url\": \"http://localhost:3002/mcp\"}}}"),
            "未配置白名单时环回地址不得放行");
    }

    /** 委托链路接通即可（三种类型的解析分支由 starter 单测覆盖，这里不重复）。 */
    @Test
    void buildClientBuilder_shouldDelegateToStarter() {
        McpClientBuilder builder = assertDoesNotThrow(() ->
            factory.buildClientBuilder("test", "sse", "{\"url\": \"https://93.184.216.34/sse\"}"));

        assertNotNull(builder);
    }

    @Test
    void buildClientBuilder_shouldBlockLoopbackAndStdioByDefault() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
            factory.buildClientBuilder("test", "http", "{\"url\":\"http://127.0.0.1/mcp\"}"));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
            factory.buildClientBuilder("test", "stdio", "{\"command\":\"/usr/bin/env\",\"cwd\":\"/tmp\"}"));
    }

    @Test
    void toVo_shouldMapConnectivitySuccessToStatusSuccess() {
        LocalDateTime testedAt = LocalDateTime.now();

        McpTestResult vo = AdminMcpFactory.toVo(new McpConnectivityResult(true, testedAt, null));

        assertEquals(ConnectivityTestStatus.SUCCESS, vo.testStatus());
        assertEquals(testedAt, vo.testTime());
        assertNull(vo.message());
    }

    @Test
    void toVo_shouldMapConnectivityFailureToStatusFailed() {
        McpTestResult vo = AdminMcpFactory.toVo(new McpConnectivityResult(false, LocalDateTime.now(), "connect timed out"));

        assertEquals(ConnectivityTestStatus.FAILED, vo.testStatus());
        assertEquals("connect timed out", vo.message());
    }

    @Test
    void toVo_shouldMapToolDescriptorToDebugToolVo() {
        McpToolDescriptor descriptor = new McpToolDescriptor("queryOrder", "查订单", "object",
            Map.<String, Object>of("orderId", Map.of("type", "string")), List.of("orderId"));

        McpDebugToolVO vo = AdminMcpFactory.toVo(descriptor);

        assertEquals("queryOrder", vo.name());
        assertEquals("查订单", vo.description());
        assertEquals("object", vo.schemaType());
        assertEquals(Map.of("orderId", Map.of("type", "string")), vo.properties());
        assertEquals(List.of("orderId"), vo.required());
    }

    /** 图片内容块要逐个转成前端渲染用的 VO，不能在转换中丢失（调试面板展示图片依赖这一步）。 */
    @Test
    void toVo_shouldMapCallResultIncludingImages() {
        McpToolCallResult result = new McpToolCallResult(true, "已生成图表", null,
            List.of(new McpImageContent("image/png", "aGVsbG8=")), false);

        McpDebugCallResult vo = AdminMcpFactory.toVo(result);

        assertTrue(vo.success());
        assertEquals("已生成图表", vo.output());
        assertEquals(1, vo.images().size());
        assertEquals("image/png", vo.images().get(0).mimeType());
        assertEquals("aGVsbG8=", vo.images().get(0).data());
        assertFalse(vo.outputLooksBinary());
    }

    @Test
    void toVo_shouldKeepFailureAndBinaryFlag() {
        McpToolCallResult result = new McpToolCallResult(false, null, "工具执行失败", List.of(), true);

        McpDebugCallResult vo = AdminMcpFactory.toVo(result);

        assertFalse(vo.success());
        assertEquals("工具执行失败", vo.errorMessage());
        assertTrue(vo.images().isEmpty());
        assertTrue(vo.outputLooksBinary());
    }
}
