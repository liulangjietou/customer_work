package com.richard.fyoung.customeradmin.gittools.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RepositoryRefTest {
    @Test
    void parsesGitHubRepositoryAndDefaultApi() {
        GitToolsProperties properties = properties("github", "https://github.com/acme/demo", null);

        RepositoryRef ref = RepositoryRef.from(properties);

        assertEquals(RepositoryProvider.GITHUB, ref.provider());
        assertEquals("acme/demo", ref.displayName());
        assertEquals("https://api.github.com", ref.apiBaseUrl());
    }

    @Test
    void parsesNestedGitLabRepositoryAndDefaultApi() {
        GitToolsProperties properties = properties("gitlab", "https://gitlab.example.com/team/platform/demo.git", null);

        RepositoryRef ref = RepositoryRef.from(properties);

        assertEquals("team/platform/demo", ref.projectPath());
        assertEquals("https://gitlab.example.com/api/v4", ref.apiBaseUrl());
    }

    @Test
    void rejectsRepositoryCredentialsAndQuery() {
        GitToolsProperties properties = properties("github", "https://user:secret@github.com/acme/demo?token=x", null);

        assertThrows(IllegalArgumentException.class, () -> RepositoryRef.from(properties));
    }

    private static GitToolsProperties properties(String provider, String repository, String apiBaseUrl) {
        GitToolsProperties properties = new GitToolsProperties();
        properties.setProvider(provider);
        properties.setRepository(repository);
        properties.setApiBaseUrl(apiBaseUrl);
        properties.setToken("git-token");
        properties.setServerToken("server-token");
        return properties;
    }
}
