package com.richard.fyoung.customeradmin.aiconfig.mcp.runtime;

import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugImageVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import com.richard.fyoung.customerwork.tool.mcp.McpClientFactory;
import com.richard.fyoung.customerwork.tool.mcp.McpConnectivityResult;
import com.richard.fyoung.customerwork.tool.mcp.McpToolCallResult;
import com.richard.fyoung.customerwork.tool.mcp.McpToolDescriptor;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 客户端构建（admin 侧装配壳）：解析构建/连通性探测/调试调用的实现全部委托给 starter 的
 * {@link McpClientFactory}（两侧唯一实现，避免"mcpType + config JSON -&gt; 客户端"的解析逻辑双份维护）。
 *
 * <p>本类只负责 admin 侧的契约：作为 Spring Bean 暴露给 {@code AdminAgentInstanceFactory}（真实注册进
 * Toolkit）与 {@code McpService}（连通性测试、调试面板），并把 starter 的中立返回类型转成 admin 的
 * 接口 VO（前端契约不变）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminMcpFactory {

    private final McpClientFactory mcpClientFactory = new McpClientFactory();

    /**
     * 按 mcpType/config 构建一个尚未连接的 {@link McpClientBuilder}。支持 stdio / sse / http 三种传输，
     * 并兼容 Claude Desktop / Cursor 标准格式的 {@code mcpServers} 外层包装、透传 config 里的 {@code headers}。
     */
    public McpClientBuilder buildClientBuilder(String mcpName, String mcpType, String config) throws Exception {
        return mcpClientFactory.buildClientBuilder(mcpName, mcpType, config);
    }

    /** 尝试建立连接并列出工具，验证 MCP 服务可达；用完即关闭，不缓存实例（与真实注册用途区分）。 */
    public McpTestResult testConnectivity(String mcpName, String mcpType, String config) {
        return toVo(mcpClientFactory.testConnectivity(mcpName, mcpType, config));
    }

    /** 调试面板：连接并列出该 MCP 提供的全部工具（含 inputSchema，供前端动态渲染参数表单）。 */
    public List<McpDebugToolVO> listDebugTools(String mcpName, String mcpType, String config) throws Exception {
        return mcpClientFactory.listTools(mcpName, mcpType, config).stream()
            .map(AdminMcpFactory::toVo)
            .collect(Collectors.toList());
    }

    /** 调试面板：单次调用一个工具并返回结果；连接/调用异常由调用方（Service 层）统一兜底成失败结果，这里直接往外抛。 */
    public McpDebugCallResult callDebugTool(String mcpName, String mcpType, String config,
                                             String toolName, Map<String, Object> arguments) throws Exception {
        return toVo(mcpClientFactory.callTool(mcpName, mcpType, config, toolName, arguments));
    }

    /** 连通性结果 -&gt; 库里持久化的测试状态（0未测试/1成功/2失败）。包级可见：同包单测直接校验转换。 */
    static McpTestResult toVo(McpConnectivityResult result) {
        return new McpTestResult(result.success() ? McpTestResult.STATUS_SUCCESS : McpTestResult.STATUS_FAILED,
            result.testedAt(), result.errorMessage());
    }

    /** 工具描述 -&gt; 调试面板工具列表项。 */
    static McpDebugToolVO toVo(McpToolDescriptor tool) {
        return new McpDebugToolVO(tool.name(), tool.description(), tool.schemaType(), tool.properties(), tool.required());
    }

    /** 工具调用结果 -&gt; 调试面板调用结果（图片块逐个转成前端渲染用的 VO）。 */
    static McpDebugCallResult toVo(McpToolCallResult result) {
        List<McpDebugImageVO> images = result.images().stream()
            .map(image -> new McpDebugImageVO(image.mimeType(), image.data()))
            .collect(Collectors.toList());
        return new McpDebugCallResult(result.success(), result.output(), result.errorMessage(), images,
            result.outputLooksBinary());
    }
}
