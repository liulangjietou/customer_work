package com.richard.fyoung.gittools.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.gittools.api.GitApiClient;
import com.richard.fyoung.gittools.api.RepositoryApi;
import com.richard.fyoung.gittools.mcp.ToolDefinitions;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
@EnableConfigurationProperties(GitToolsProperties.class)
public class GitToolsConfiguration {

    @Bean
    public RepositoryRef repositoryRef(GitToolsProperties properties) {
        return RepositoryRef.from(properties);
    }

    @Bean
    public GitApiClient gitApiClient(RepositoryRef repositoryRef, GitToolsProperties properties,
                                    ObjectMapper objectMapper) {
        return new GitApiClient(repositoryRef, properties, objectMapper);
    }

    @Bean
    public RepositoryApi repositoryApi(GitApiClient client, RepositoryRef repositoryRef,
                                      GitToolsProperties properties) {
        return new RepositoryApi(client, repositoryRef, properties.getRef(), properties.getMaxFileBytes());
    }

    @Bean
    public WebMvcStreamableServerTransportProvider mcpTransport() {
        return WebMvcStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mcpServer(WebMvcStreamableServerTransportProvider transport,
                                   RepositoryApi repositoryApi, ObjectMapper objectMapper) {
        List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification> tools =
                ToolDefinitions.all(repositoryApi, objectMapper);
        return McpServer.sync(transport)
                .serverInfo("gittools-mcp-server", "1.0.0")
                .instructions("Read-only retrieval for one configured GitHub or GitLab repository.")
                .tools(tools)
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> mcpRouter(WebMvcStreamableServerTransportProvider transport) {
        return transport.getRouterFunction();
    }
}
