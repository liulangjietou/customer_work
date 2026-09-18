package com.richard.fyoung.gittools.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.richard.fyoung.gittools.config.RepositoryProvider;
import com.richard.fyoung.gittools.config.RepositoryRef;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 固定单仓库的 GitHub/GitLab 只读检索 API。 */
public class RepositoryApi {
    private static final int MAX_PAGE_SIZE = 100;
    private final GitApiClient client;
    private final RepositoryRef repository;
    private final String configuredRef;
    private final int maxFileBytes;

    public RepositoryApi(GitApiClient client, RepositoryRef repository) {
        this(client, repository, "main", 512 * 1024);
    }

    public RepositoryApi(GitApiClient client, RepositoryRef repository, String configuredRef, int maxFileBytes) {
        this.client = client;
        this.repository = repository;
        this.configuredRef = configuredRef == null || configuredRef.isBlank() ? "main" : configuredRef;
        this.maxFileBytes = maxFileBytes;
    }

    public Map<String, Object> searchCode(String query, String ref, int page, int perPage) {
        requireText(query, "query");
        Map<String, String> params = pageParams(page, perPage);
        String actualRef = defaultRef(ref);
        if (repository.provider() == RepositoryProvider.GITHUB) {
            params.put("q", "repo:" + repository.displayName() + " " + query + " ref:" + actualRef);
            JsonNode response = client.getJson("/search/code", params);
            return mapSearchResponse(response, "github");
        }
        params.put("scope", "blobs");
        params.put("search", query);
        params.put("ref", actualRef);
        JsonNode response = client.getJson(gitlabPath("search"), params);
        return mapSearchResponse(response, "gitlab");
    }

    public Map<String, Object> getFile(String path, String ref) {
        String normalizedPath = normalizePath(path);
        String actualRef = defaultRef(ref);
        JsonNode response;
        if (repository.provider() == RepositoryProvider.GITHUB) {
            response = client.getJson(githubPath("contents/" + GitApiClient.encodePath(normalizedPath)),
                    Map.of("ref", actualRef));
        } else {
            response = client.getJson(gitlabPath("repository/files/" + GitApiClient.encodeComponent(normalizedPath)),
                    Map.of("ref", actualRef));
        }
        String encoded = response.path("content").asText("").replace("\n", "");
        byte[] content;
        try {
            content = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new GitApiException("Git service returned invalid file content", exception);
        }
        if (content.length > maxFileBytes) {
            throw new GitApiException("Requested file exceeded configured size limit");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", normalizedPath);
        result.put("ref", actualRef);
        result.put("size", content.length);
        result.put("content", new String(content, java.nio.charset.StandardCharsets.UTF_8));
        return result;
    }

    public Map<String, Object> listTree(String path, String ref, boolean recursive) {
        String actualRef = defaultRef(ref);
        JsonNode response;
        List<Map<String, Object>> entries = new ArrayList<>();
        if (repository.provider() == RepositoryProvider.GITHUB) {
            response = client.getJson(githubPath("git/trees/" + GitApiClient.encodeComponent(actualRef)),
                    Map.of("recursive", recursive ? "1" : "0"));
            String prefix = path == null || path.isBlank() ? "" : normalizePath(path) + "/";
            response.path("tree").forEach(item -> {
                String itemPath = item.path("path").asText("");
                if (prefix.isEmpty() || itemPath.equals(prefix.substring(0, prefix.length() - 1))
                        || itemPath.startsWith(prefix)) {
                    entries.add(treeEntry(item));
                }
            });
        } else {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("ref", actualRef);
            params.put("recursive", Boolean.toString(recursive));
            if (path != null && !path.isBlank()) {
                params.put("path", normalizePath(path));
            }
            response = client.getJson(gitlabPath("repository/tree"), params);
            response.forEach(item -> entries.add(treeEntry(item)));
        }
        return Map.of("ref", actualRef, "path", path == null ? "" : path, "entries", entries);
    }

    public Map<String, Object> listCommits(String ref, String path, int page, int perPage) {
        Map<String, String> params = pageParams(page, perPage);
        params.put(repository.provider() == RepositoryProvider.GITHUB ? "sha" : "ref", defaultRef(ref));
        if (path != null && !path.isBlank()) {
            params.put("path", normalizePath(path));
        }
        JsonNode response = client.getJson(repository.provider() == RepositoryProvider.GITHUB
                ? githubPath("commits") : gitlabPath("repository/commits"), params);
        List<Map<String, Object>> commits = new ArrayList<>();
        response.forEach(item -> commits.add(commitEntry(item)));
        return Map.of("ref", defaultRef(ref), "commits", commits);
    }

    public Map<String, Object> getPullRequest(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("number must be positive");
        }
        String endpoint = repository.provider() == RepositoryProvider.GITHUB
                ? githubPath("pulls/" + number)
                : gitlabPath("merge_requests/" + number);
        JsonNode response = client.getJson(endpoint, Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("number", repository.provider() == RepositoryProvider.GITHUB
                ? response.path("number").asInt(number) : response.path("iid").asInt(number));
        result.put("title", response.path("title").asText(""));
        result.put("state", response.path("state").asText(""));
        result.put("url", repository.provider() == RepositoryProvider.GITHUB
                ? response.path("html_url").asText("") : response.path("web_url").asText(""));
        result.put("description", repository.provider() == RepositoryProvider.GITHUB
                ? response.path("body").asText("") : response.path("description").asText(""));
        return result;
    }

    public Map<String, Object> searchIssues(String query, String state, int page, int perPage) {
        requireText(query, "query");
        Map<String, String> params = pageParams(page, perPage);
        JsonNode response;
        if (repository.provider() == RepositoryProvider.GITHUB) {
            params.put("q", "repo:" + repository.displayName() + " " + query);
            if (state != null && !state.isBlank()) {
                params.put("q", params.get("q") + " state:" + state);
            }
            response = client.getJson("/search/issues", params);
            return mapSearchResponse(response, "github");
        }
        params.put("search", query);
        if (state != null && !state.isBlank()) {
            params.put("state", state);
        }
        response = client.getJson(gitlabPath("issues"), params);
        return mapSearchResponse(response, "gitlab");
    }

    private Map<String, String> pageParams(int page, int perPage) {
        if (page < 1 || perPage < 1 || perPage > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be >= 1 and perPage must be between 1 and 100");
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("page", Integer.toString(page));
        result.put(repository.provider() == RepositoryProvider.GITHUB ? "per_page" : "per_page",
                Integer.toString(perPage));
        return result;
    }

    private Map<String, Object> mapSearchResponse(JsonNode response, String provider) {
        List<Map<String, Object>> items = new ArrayList<>();
        JsonNode source = response.isArray() ? response : response.path("items");
        source.forEach(item -> {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("path", provider.equals("github")
                    ? item.path("path").asText("") : item.path("path").asText(item.path("filename").asText("")));
            mapped.put("ref", provider.equals("github") ? item.path("ref").asText("") : item.path("ref").asText(""));
            mapped.put("url", provider.equals("github")
                    ? item.path("html_url").asText("") : item.path("web_url").asText(""));
            mapped.put("score", item.path("score").isNumber() ? item.path("score").numberValue() : null);
            items.add(mapped);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("total", response.path("total_count").asInt(items.size()));
        result.put("items", items);
        return result;
    }

    private Map<String, Object> treeEntry(JsonNode item) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", item.path("path").asText(""));
        entry.put("type", item.path("type").asText(item.path("mode").asText("")));
        entry.put("sha", item.path("sha").asText(""));
        entry.put("size", item.path("size").isNumber() ? item.path("size").asInt() : null);
        return entry;
    }

    private Map<String, Object> commitEntry(JsonNode item) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sha", item.path("sha").asText(""));
        JsonNode commit = item.path("commit");
        entry.put("message", commit.path("message").asText(item.path("title").asText("")));
        entry.put("author", commit.path("author").path("name").asText(item.path("author_name").asText("")));
        entry.put("date", commit.path("author").path("date").asText(item.path("committed_date").asText("")));
        return entry;
    }

    private String githubPath(String suffix) {
        return "/repos/" + GitApiClient.encodeComponent(repository.owner()) + "/"
                + GitApiClient.encodeComponent(repository.name()) + "/" + suffix;
    }

    private String gitlabPath(String suffix) {
        return "/projects/" + GitApiClient.encodeComponent(repository.projectPath()) + "/" + suffix;
    }

    private String defaultRef(String ref) {
        return ref == null || ref.isBlank() ? configuredRef : ref;
    }

    private static String normalizePath(String path) {
        String value = Objects.requireNonNull(path, "path").trim();
        if (value.isBlank() || value.startsWith("/") || value.contains("\0")) {
            throw new IllegalArgumentException("path must be a relative repository path");
        }
        String[] segments = value.split("/");
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path contains an invalid segment");
            }
        }
        return String.join("/", segments);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
