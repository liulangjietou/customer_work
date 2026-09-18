package com.richard.fyoung.customeradmin.gittools.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.gittools.api.GitApiClient;
import com.richard.fyoung.customeradmin.gittools.api.RepositoryApi;
import com.richard.fyoung.customeradmin.gittools.mcp.ToolDefinitions;
import com.richard.fyoung.customeradmin.gittools.web.McpAuthenticationFilter;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * gittools 装配：把固定单仓库的只读 GitHub/GitLab 检索以 MCP（Streamable HTTP）暴露在 admin 进程内。
 *
 * <p>只在 {@code admin.gittools.enabled=true} 时生效，默认关闭。端点挂在 {@code /api/**} 之外，
 * 不走后台登录态，改由 {@link McpAuthenticationFilter} 校验专用 Bearer Token——MCP 客户端是程序，
 * 拿不到 Sa-Token 会话；与 A2A 端点同一思路。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(prefix = "admin.gittools", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GitToolsProperties.class)
public class GitToolsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GitToolsConfiguration.class);

    /** MCP 端点路径；带模块前缀，避免在 admin 根路径下占用通用的 /mcp。 */
    public static final String MCP_ENDPOINT = "/gittools/mcp";

    private static final String SERVER_NAME = "gittools-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";

    @Bean
    public RepositoryRef gitToolsRepositoryRef(GitToolsProperties properties) {
        RepositoryRef ref = RepositoryRef.from(properties);
        log.info("[gittools] mcp endpoint enabled, path={}, provider={}, repository={}",
            MCP_ENDPOINT, ref.provider(), ref.displayName());
        return ref;
    }

    @Bean
    public RepositoryApi gitToolsRepositoryApi(RepositoryRef gitToolsRepositoryRef, GitToolsProperties properties,
                                              ObjectMapper objectMapper) {
        GitApiClient client = new GitApiClient(gitToolsRepositoryRef, properties, objectMapper);
        return new RepositoryApi(client, gitToolsRepositoryRef, properties.getRef(), properties.getMaxFileBytes());
    }

    @Bean
    public WebMvcStreamableServerTransportProvider gitToolsMcpTransport() {
        return WebMvcStreamableServerTransportProvider.builder()
            .mcpEndpoint(MCP_ENDPOINT)
            .build();
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer gitToolsMcpServer(WebMvcStreamableServerTransportProvider gitToolsMcpTransport,
                                           RepositoryApi gitToolsRepositoryApi, ObjectMapper objectMapper) {
        List<McpServerFeatures.SyncToolSpecification> tools = ToolDefinitions.all(gitToolsRepositoryApi, objectMapper);
        return McpServer.sync(gitToolsMcpTransport)
            .serverInfo(SERVER_NAME, SERVER_VERSION)
            .instructions("Read-only retrieval for one configured GitHub or GitLab repository.")
            .tools(tools)
            .build();
    }

    @Bean
    public RouterFunction<ServerResponse> gitToolsMcpRouter(WebMvcStreamableServerTransportProvider gitToolsMcpTransport) {
        return gitToolsMcpTransport.getRouterFunction();
    }

    @Bean
    public FilterRegistrationBean<McpAuthenticationFilter> gitToolsMcpAuthenticationFilter(GitToolsProperties properties) {
        FilterRegistrationBean<McpAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpAuthenticationFilter(properties));
        registration.addUrlPatterns(MCP_ENDPOINT, MCP_ENDPOINT + "/*");
        // 先于业务拦截器拒绝未授权请求，未授权流量不进入 MVC 链路
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
