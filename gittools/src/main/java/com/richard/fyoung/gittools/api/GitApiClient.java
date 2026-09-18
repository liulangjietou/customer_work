package com.richard.fyoung.gittools.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.gittools.config.GitToolsProperties;
import com.richard.fyoung.gittools.config.RepositoryProvider;
import com.richard.fyoung.gittools.config.RepositoryRef;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** GitHub/GitLab API 的只读 HTTP 客户端，集中处理鉴权、超时、大小限制和错误脱敏。 */
public class GitApiClient {
    private static final Logger log = LoggerFactory.getLogger(GitApiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RepositoryRef repositoryRef;
    private final GitToolsProperties properties;

    public GitApiClient(RepositoryRef repositoryRef, GitToolsProperties properties, ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        this.objectMapper = objectMapper;
        this.repositoryRef = repositoryRef;
        this.properties = properties;
    }

    public JsonNode getJson(String path, Map<String, String> query) {
        String url = repositoryRef.apiBaseUrl() + path;
        if (query != null && !query.isEmpty()) {
            url += "?" + encodeQuery(query);
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.getReadTimeout())
                .GET()
                .header("Accept", "application/json")
                .header("User-Agent", "gittools-mcp-server/1.0");
        if (repositoryRef.provider() == RepositoryProvider.GITHUB) {
            requestBuilder.header("Authorization", "Bearer " + properties.getToken())
                    .header("X-GitHub-Api-Version", "2022-11-28");
        } else {
            requestBuilder.header("PRIVATE-TOKEN", properties.getToken());
        }

        try {
            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readLimited(response.body(), properties.getMaxResponseBytes());
            String text = new String(body, StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = providerErrorDetail(text);
                throw new GitApiException("Git service request failed: " + detail + " (HTTP "
                        + response.statusCode() + ")", response.statusCode());
            }
            return text.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
        } catch (GitApiException exception) {
            throw exception;
        } catch (IOException exception) {
            log.error("git service request failed, errorCode={}, path={}", "GIT-API-IO", path, exception);
            throw new GitApiException("Git service request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.error("git service request interrupted, errorCode={}, path={}", "GIT-API-INTERRUPTED", path,
                    exception);
            throw new GitApiException("Git service request interrupted", exception);
        }
    }

    public static String encodeComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String encodePath(String value) {
        String[] segments = value.split("/", -1);
        StringBuilder encoded = new StringBuilder(value.length());
        for (int index = 0; index < segments.length; index++) {
            if (index > 0) {
                encoded.append('/');
            }
            encoded.append(encodeComponent(segments[index]));
        }
        return encoded.toString();
    }

    private static String encodeQuery(Map<String, String> query) {
        StringBuilder result = new StringBuilder();
        query.forEach((key, value) -> {
            if (result.length() > 0) {
                result.append('&');
            }
            result.append(encodeComponent(key)).append('=').append(encodeComponent(value));
        });
        return result.toString();
    }

    private static byte[] readLimited(InputStream inputStream, int maxBytes) throws IOException {
        try (InputStream input = inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new GitApiException("Git service response exceeded configured size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String providerErrorDetail(String body) {
        try {
            JsonNode error = objectMapper.readTree(body);
            String message = error.path("message").asText("");
            if (message.isBlank()) {
                message = error.path("error").asText("");
            }
            if (!message.isBlank()) {
                return redact(message);
            }
        } catch (Exception ignored) {
            // 返回通用信息，避免将上游 HTML 或 token 反射到日志和 MCP 响应。
        }
        return "upstream rejected the request";
    }

    private String redact(String value) {
        return value.replace(properties.getToken(), "[redacted]")
                .replace(properties.getServerToken(), "[redacted]");
    }
}
