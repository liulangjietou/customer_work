package com.richard.fyoung.customeradmin.gittools.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/** 受保护的单仓库引用，所有 Git API 请求都基于它生成。 */
public record RepositoryRef(
        RepositoryProvider provider,
        String apiBaseUrl,
        String owner,
        String name,
        String projectPath,
        String displayName) {

    public static RepositoryRef from(GitToolsProperties properties) {
        RepositoryProvider provider = RepositoryProvider.parse(properties.getProvider());
        URI repositoryUri = parseUri(properties.getRepository());
        String apiBaseUrl = normalizeApiBaseUrl(properties.getApiBaseUrl(), provider, repositoryUri);
        String path = trimSlashes(repositoryUri.getPath());
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("repository path must not be empty");
        }

        if (provider == RepositoryProvider.GITHUB) {
            String[] segments = path.split("/");
            if (segments.length != 2 || segments[0].isBlank() || segments[1].isBlank()) {
                throw new IllegalArgumentException("GitHub repository must be https://github.com/{owner}/{repo}");
            }
            return new RepositoryRef(provider, apiBaseUrl, segments[0], segments[1], path,
                    segments[0] + "/" + segments[1]);
        }
        String[] segments = path.split("/");
        if (segments.length < 2) {
            throw new IllegalArgumentException("GitLab repository must contain a namespace and project");
        }
        String name = segments[segments.length - 1];
        String projectPath = path;
        return new RepositoryRef(provider, apiBaseUrl, null, name, projectPath, projectPath);
    }

    private static URI parseUri(String value) {
        try {
            URI uri = new URI(Objects.requireNonNull(value, "repository").trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("repository must use http or https");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("repository must not contain credentials, query, or fragment");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("repository URL is invalid", exception);
        }
    }

    private static String normalizeApiBaseUrl(String configured, RepositoryProvider provider, URI repositoryUri) {
        String value = configured;
        if (value == null || value.isBlank()) {
            value = provider == RepositoryProvider.GITHUB
                    ? "https://api.github.com"
                    : repositoryUri.getScheme() + "://" + repositoryUri.getAuthority() + "/api/v4";
        }
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("apiBaseUrl must use http or https");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("apiBaseUrl must not contain credentials, query, or fragment");
            }
            return trimTrailingSlash(uri.toString());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("apiBaseUrl is invalid", exception);
        }
    }

    private static String trimSlashes(String value) {
        return value.replaceAll("^/+|/+$", "");
    }

    private static String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
