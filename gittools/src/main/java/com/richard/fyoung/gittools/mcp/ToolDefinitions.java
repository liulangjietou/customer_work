package com.richard.fyoung.gittools.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.gittools.api.RepositoryApi;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** MCP 工具定义，工具只编排 RepositoryApi，不直接访问外部 Git API。 */
public final class ToolDefinitions {
    private static final McpSchema.ToolAnnotations READ_ONLY = new McpSchema.ToolAnnotations(
            "Read-only Git repository retrieval", true, false, true, false, true);

    private ToolDefinitions() {
    }

    public static List<McpServerFeatures.SyncToolSpecification> all(RepositoryApi api, ObjectMapper objectMapper) {
        return List.of(
                tool("git_search_code", "Search code in the configured repository",
                        schema(Map.of("query", stringProperty("Search text"), "ref", stringProperty("Branch or tag"),
                                "page", integerProperty("Page number"), "per_page", integerProperty("Page size"),
                                "response_format", formatProperty()), List.of("query")),
                        (exchange, arguments) -> run(objectMapper, arguments, "git_search_code",
                                () -> api.searchCode(text(arguments, "query"), text(arguments, "ref"),
                                        integer(arguments, "page", 1), integer(arguments, "per_page", 20)))),
                tool("git_get_file", "Read a file from the configured repository",
                        schema(Map.of("path", stringProperty("Relative repository path"),
                                "ref", stringProperty("Branch or tag"), "response_format", formatProperty()),
                                List.of("path")),
                        (exchange, arguments) -> run(objectMapper, arguments, "git_get_file",
                                () -> api.getFile(text(arguments, "path"), text(arguments, "ref")))),
                tool("git_list_tree", "List repository tree entries",
                        schema(Map.of("path", stringProperty("Optional relative directory path"),
                                "ref", stringProperty("Branch or tag"), "recursive", booleanProperty("List recursively"),
                                "response_format", formatProperty()), List.of()),
                        (exchange, arguments) -> run(objectMapper, arguments, "git_list_tree",
                                () -> api.listTree(text(arguments, "path"), text(arguments, "ref"),
                                        bool(arguments, "recursive", true)))),
                tool("git_list_commits", "List commits for a branch or path",
                        schema(Map.of("ref", stringProperty("Branch or tag"), "path", stringProperty("Relative path"),
                                "page", integerProperty("Page number"), "per_page", integerProperty("Page size"),
                                "response_format", formatProperty()), List.of()),
                        (exchange, arguments) -> run(objectMapper, arguments, "git_list_commits",
                                () -> api.listCommits(text(arguments, "ref"), text(arguments, "path"),
                                        integer(arguments, "page", 1), integer(arguments, "per_page", 20)))),
                tool("git_get_pull_request", "Read a pull request or merge request",
                        schema(Map.of("number", integerProperty("Pull request or merge request number"),
                                "response_format", formatProperty()), List.of("number")),
                        (exchange, arguments) -> run(objectMapper, arguments, "git_get_pull_request",
                                () -> api.getPullRequest(integer(arguments, "number", 0)))),
                tool("git_search_issues", "Search issues in the configured repository",
                        schema(Map.of("query", stringProperty("Search text"), "state", stringProperty("Issue state"),
                                "page", integerProperty("Page number"), "per_page", integerProperty("Page size"),
                                "response_format", formatProperty()), List.of("query")),
                        (exchange, arguments) -> run(objectMapper, arguments, "git_search_issues",
                                () -> api.searchIssues(text(arguments, "query"), text(arguments, "state"),
                                        integer(arguments, "page", 1), integer(arguments, "per_page", 20)))));
    }

    private static McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            McpSchema.JsonSchema schema,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(schema)
                        .annotations(READ_ONLY)
                        .build(),
                handler);
    }

    private static McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, false, Map.of(), Map.of());
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProperty(String description) {
        return Map.of("type", "integer", "description", description, "minimum", 1);
    }

    private static Map<String, Object> booleanProperty(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> formatProperty() {
        return Map.of("type", "string", "enum", List.of("json", "markdown"), "default", "json");
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }

    private static int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private static boolean bool(Map<String, Object> arguments, String key, boolean fallback) {
        Object value = arguments.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static McpSchema.CallToolResult run(
            ObjectMapper objectMapper,
            Map<String, Object> arguments,
            String toolName,
            java.util.function.Supplier<Map<String, Object>> operation) {
        try {
            Map<String, Object> result = operation.get();
            String format = text(arguments, "response_format");
            String output = "markdown".equalsIgnoreCase(format)
                    ? markdown(result)
                    : objectMapper.writeValueAsString(result);
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(output)), false, result, Map.of());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "query failed" : exception.getMessage();
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Gittools query failed: " + message)), true);
        }
    }

    private static String markdown(Map<String, Object> result) {
        StringBuilder output = new StringBuilder();
        result.forEach((key, value) -> output.append("## ").append(key).append("\n\n")
                .append(value).append("\n\n"));
        return output.toString().trim();
    }
}
