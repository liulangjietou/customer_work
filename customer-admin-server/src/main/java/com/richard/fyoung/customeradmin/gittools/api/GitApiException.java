package com.richard.fyoung.customeradmin.gittools.api;

public class GitApiException extends RuntimeException {
    private final int statusCode;

    public GitApiException(String message) {
        this(message, 0, null);
    }

    public GitApiException(String message, Throwable cause) {
        this(message, 0, cause);
    }

    public GitApiException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public GitApiException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
