package com.richard.fyoung.gittools.web;

import com.richard.fyoung.gittools.config.GitToolsProperties;
import com.richard.fyoung.gittools.config.RepositoryRef;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final RepositoryRef repositoryRef;

    public HealthController(GitToolsProperties properties, RepositoryRef repositoryRef) {
        this.repositoryRef = repositoryRef;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "gittools-mcp-server",
                "provider", repositoryRef.provider().name().toLowerCase(),
                "repository", repositoryRef.displayName());
    }
}
