package com.richard.fyoung.customeradmin.aiconfig.mcp.runtime;

import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdminMcpFactory} 单测。解析构建/连通性/调试调用的行为已下沉 starter，由
 * {@code McpClientFactoryTest} 覆盖；这里只验证 admin 侧薄壳自己的职责——
 * starter 中立返回类型 -&gt; admin 接口 VO 的转换（前端契约），以及委托链路确实接通。
 * @author owlzhangfq@gmail.com
 */
class AdminMcpFactoryTest {

    private final AdminMcpFactory factory = new AdminMcpFactory();

    /** 委托链路接通即可（三种类型的解析分支由 starter 单测覆盖，这里不重复）。 */
    @Test
    void buildClientBuilder_shouldDelegateToStarter() {
        McpClientBuilder builder = assertDoesNotThrow(() ->
            factory.buildClientBuilder("test", "sse", "{\"url\": \"https://mcp.example.com/sse\"}"));

        assertNotNull(builder);
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
