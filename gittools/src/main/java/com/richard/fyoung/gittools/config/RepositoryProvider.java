package com.richard.fyoung.gittools.config;

public enum RepositoryProvider {
    GITHUB,
    GITLAB;

    public static RepositoryProvider parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("provider must be github or gitlab");
        }
    }
}
