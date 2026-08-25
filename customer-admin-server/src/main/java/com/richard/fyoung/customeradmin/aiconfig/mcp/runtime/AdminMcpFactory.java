package com.richard.fyoung.customeradmin.aiconfig.mcp.runtime;

import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugCallResult;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugImageVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpTestResult;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.config.AdminMcpSecurityProperties;
import com.richard.fyoung.customerwork.tool.mcp.McpClientFactory;
import com.richard.fyoung.customerwork.tool.mcp.McpConnectivityResult;
import com.richard.fyoung.customerwork.tool.mcp.McpToolCallResult;
import com.richard.fyoung.customerwork.tool.mcp.McpToolDescriptor;
import com.richard.fyoung.customerwork.tool.mcp.McpSecurityPolicy;
import com.richard.fyoung.customerwork.tool.mcp.McpServerSpec;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
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

    private final McpClientFactory mcpClientFactory;

    /**
     * {@code @Autowired} 不能省：本类有多个构造器，Spring 在「多构造器且无 {@code @Autowired}」时
     * <b>不会</b>挑参数最多的那个，而是回退到无参构造器。此前正是如此——容器实际用的是那个
     * 装配 {@link McpSecurityPolicy#strict()} 的无参构造器，白名单恒为空，于是
     * {@code admin.mcp.security.*} 配得再对也没被读到：localhost 的 MCP 一律报「指向内网/环回，已拦截」，
     * stdio 一律报「未配置执行白名单」。而属性类自身绑定是正常的，照着它打诊断日志只会看到正确的值，
     * 把人引向配置本身。同样的坑 PR #68 已在别处修过 5 处。
     *
     * <p>无参构造器已一并删除：留着它，将来谁再拿掉这个注解，故障还是「静默降级成最严格策略」
     * 而不是启动失败。现在没有无参构造器可回退，容器会当场报错——这类问题必须在启动时就炸出来。</p>
     */
    @Autowired
    public AdminMcpFactory(AdminMcpSecurityProperties properties) {
        this(new McpSecurityPolicy(properties::getAllowedHosts, properties::getAllowedCommands,
            properties::getAllowedWorkingDirectories, properties::getAllowedEnvironmentKeys));
    }

    /** 离线单测用：显式传入策略（如 {@link McpSecurityPolicy#strict()}）。 */
    AdminMcpFactory(McpSecurityPolicy securityPolicy) {
        this.mcpClientFactory = new McpClientFactory(securityPolicy);
    }

    /** 保存门禁：与真实运行时构建复用同一解析与安全策略。 */
    public McpServerSpec validateConfiguration(String mcpName, String mcpType, String config) throws Exception {
        return mcpClientFactory.parseSpec(mcpName, mcpType, config);
    }

    /** 底层 AgentScope 构建入口，仅供兼容与配置检查；真实运行时统一使用 {@link #buildClient}。 */
    public McpClientBuilder buildClientBuilder(String mcpName, String mcpType, String config) throws Exception {
        return mcpClientFactory.buildClientBuilder(mcpName, mcpType, config);
    }

    /** 真实运行时统一走 starter 的兼容构建入口，避免 POST-only Streamable HTTP 在可选 GET 上失败。 */
    public Mono<McpClientWrapper> buildClient(String mcpName, String mcpType, String config,
                                               Duration timeout) throws Exception {
        return mcpClientFactory.buildClient(mcpName, mcpType, config, timeout);
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
        return new McpTestResult(result.success() ? ConnectivityTestStatus.SUCCESS : ConnectivityTestStatus.FAILED,
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
