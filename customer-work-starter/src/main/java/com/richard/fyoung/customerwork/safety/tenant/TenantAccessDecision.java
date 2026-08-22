package com.richard.fyoung.customerwork.safety.tenant;

/** 一次运行时租户访问判定；错误码直接作为 HTTP/WS 可观测契约。 */
public record TenantAccessDecision(
    Kind kind,
    long accessEpoch
) {

    public enum Kind {
        ALLOWED(true, 200, "TENANT_ACCESS_ALLOWED", "tenant access allowed"),
        SNAPSHOT_UNAVAILABLE(false, 503, "TENANT_ACCESS_STATE_UNAVAILABLE",
            "tenant access state is unavailable"),
        SNAPSHOT_STALE(false, 503, "TENANT_ACCESS_STATE_STALE",
            "tenant access state is stale"),
        ACCESS_DENIED(false, 403, "TENANT_ACCESS_DENIED", "tenant access is disabled"),
        CREDENTIAL_REVOKED(false, 401, "TENANT_CREDENTIAL_REVOKED",
            "tenant credential has been revoked");

        private final boolean allowed;
        private final int httpStatus;
        private final String code;
        private final String message;

        Kind(boolean allowed, int httpStatus, String code, String message) {
            this.allowed = allowed;
            this.httpStatus = httpStatus;
            this.code = code;
            this.message = message;
        }

        public boolean isAllowed() {
            return allowed;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    public boolean isAllowed() {
        return kind.isAllowed();
    }

    public int httpStatus() {
        return kind.getHttpStatus();
    }

    public String code() {
        return kind.getCode();
    }

    public String message() {
        return kind.getMessage();
    }

    public static TenantAccessDecision allowed(long epoch) {
        return new TenantAccessDecision(Kind.ALLOWED, epoch);
    }

    public static TenantAccessDecision unavailable() {
        return new TenantAccessDecision(Kind.SNAPSHOT_UNAVAILABLE, -1L);
    }
}
