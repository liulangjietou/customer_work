package com.richard.fyoung.gittools.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.gittools.config.GitToolsProperties;
import com.richard.fyoung.gittools.config.RepositoryRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RepositoryApiTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void GitHubFileLookupUsesBearerAndConfiguredRef() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            write(exchange, 200, "{\"content\":\"" + Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))
                    + "\"}");
        });
        GitToolsProperties properties = properties("github", "https://github.com/acme/demo", serverUrl(), "main");
        RepositoryRef ref = RepositoryRef.from(properties);
        RepositoryApi api = new RepositoryApi(new GitApiClient(ref, properties, new ObjectMapper()), ref, properties.getRef(),
                properties.getMaxFileBytes());

        Map<String, Object> result = api.getFile("src/main.ts", null);

        assertEquals("/repos/acme/demo/contents/src/main.ts?ref=main", requestPath.get());
        assertEquals("Bearer git-token", authorization.get());
        assertEquals("hello", result.get("content"));
    }

    @Test
    void GitLabFileLookupEncodesNestedProjectAndFilePath() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        startServer(exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            token.set(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            write(exchange, 200, "{\"content\":\"" + Base64.getEncoder().encodeToString("ok".getBytes(StandardCharsets.UTF_8))
                    + "\"}");
        });
        GitToolsProperties properties = properties("gitlab", "https://gitlab.example.com/team/platform/demo", serverUrl(), "main");
        RepositoryRef ref = RepositoryRef.from(properties);
        RepositoryApi api = new RepositoryApi(new GitApiClient(ref, properties, new ObjectMapper()), ref, properties.getRef(),
                properties.getMaxFileBytes());

        api.getFile("src/main.ts", null);

        assertEquals("/projects/team%2Fplatform%2Fdemo/repository/files/src%2Fmain.ts?ref=main", requestPath.get());
        assertEquals("git-token", token.get());
    }

    @Test
    void rejectsTraversalBeforeCallingGitService() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        startServer(exchange -> {
            called.set(true);
            write(exchange, 200, "{}");
        });
        GitToolsProperties properties = properties("github", "https://github.com/acme/demo", serverUrl(), "main");
        RepositoryRef ref = RepositoryRef.from(properties);
        RepositoryApi api = new RepositoryApi(new GitApiClient(ref, properties, new ObjectMapper()), ref, properties.getRef(),
                properties.getMaxFileBytes());

        assertThrows(IllegalArgumentException.class, () -> api.getFile("../secret", null));
        assertTrue(!called.get());
    }

    @Test
    void doesNotExposeGitTokenInProviderError() throws Exception {
        startServer(exchange -> write(exchange, 401, "{\"message\":\"Bad credentials\"}"));
        GitToolsProperties properties = properties("github", "https://github.com/acme/demo", serverUrl(), "main");
        RepositoryRef ref = RepositoryRef.from(properties);
        GitApiClient client = new GitApiClient(ref, properties, new ObjectMapper());

        GitApiException exception = assertThrows(GitApiException.class, () -> client.getJson("/private", Map.of()));

        assertTrue(!exception.getMessage().contains("git-token"));
        assertEquals(401, exception.getStatusCode());
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static GitToolsProperties properties(String provider, String repository, String apiBaseUrl, String ref) {
        GitToolsProperties properties = new GitToolsProperties();
        properties.setProvider(provider);
        properties.setRepository(repository);
        properties.setApiBaseUrl(apiBaseUrl);
        properties.setToken("git-token");
        properties.setServerToken("server-token");
        properties.setRef(ref);
        return properties;
    }
}
