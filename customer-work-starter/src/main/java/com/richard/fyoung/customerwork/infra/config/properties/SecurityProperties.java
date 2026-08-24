package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 接入层安全配置。 */
@Data
public class SecurityProperties {
    private final Auth auth = new Auth();
    private final RateLimit rateLimit = new RateLimit();
    private final ApprovalAuth approvalAuth = new ApprovalAuth();

    /** API Key 鉴权。 */
    @Data
    public static class Auth {
        /** 是否启用鉴权（默认关闭，生产开启并配置结构化 credentials）。 */
        private boolean enabled = false;
        /** 携带 API Key 的请求头名。 */
        private String headerName = "X-API-Key";
        /** 携带逻辑 Key ID 的请求头名；结构化凭据必须同时提交 keyId 与 secret。 */
        private String keyIdHeaderName = "X-API-Key-Id";
        /**
         * 结构化 API Key 凭据。只保存 secret 的 SHA-256 十六进制摘要，不保存明文。
         * 同一 keyId 可并存多个 epoch，用于无中断轮换。
         */
        private List<Credential> credentials = new ArrayList<>();
        /** 每个逻辑 keyId 当前允许的最小 epoch；提升后旧 epoch 立即失效。 */
        private Map<String, Long> minimumEpochs = new LinkedHashMap<>();
        /** 旧版明文 API Key 列表，仅兼容非生产配置；prod 门禁拒绝。 */
        private List<String> apiKeys = new ArrayList<>();

        /**
         * 旧版明文 Key→租户映射：{@code key: 租户ID}，仅兼容非生产配置；prod 门禁拒绝。
         *
         * <p>开启多租户后，服务端接入方的租户身份就取自这里——Key 是接入方唯一提供的凭据，
         * 也是唯一不可伪造的租户线索（请求头里的租户参数谁都能改）。</p>
         *
         * <p>与 {@code apiKeys} 并存：本映射里的 Key 同样视为合法凭据，无需再往 {@code apiKeys} 抄一份；
         * 只在 {@code apiKeys} 里的 Key 归入默认租户，保证单租户部署升级后行为不变。</p>
         */
        private Map<String, String> tenantKeys = new LinkedHashMap<>();
    }

    /** 结构化 API Key；scope 表达式支持 {@code *}、路径及 {@code METHOD:/path/**}。 */
    @Data
    public static class Credential {
        /** 稳定的调用方标识；轮换 secret 时保持不变。 */
        private String keyId;
        /** 原始 secret 的完整 SHA-256 小写十六进制摘要（64 字符）。 */
        private String keyHash;
        /** 凭据所属租户。 */
        private String tenantId = "default";
        /** 允许的 HTTP 方法/路径范围；空集合按无权限处理。 */
        private List<String> scopes = new ArrayList<>();
        /** 到期时刻；null 表示不设置到期时间。 */
        private Instant expiresAt;
        /** 轮换版本；必须不小于 auth.minimum-epochs[keyId]。 */
        private long epoch = 1L;
        /** 紧急停用开关。 */
        private boolean enabled = true;
    }

    /**
     * 限流配置：本节参数是<b>全局兜底层</b>（无规则命中时生效），规则层见 {@code rule-enabled}。
     *
     * <p>两层的关系：{@code cw_rate_limit_rule} 里的规则按路径前缀优先匹配，都不命中才落到本节参数。
     * 不开规则层时行为与规则化之前完全一致。</p>
     */
    @Data
    public static class RateLimit {
        /** 是否启用全局兜底限流。 */
        private boolean enabled = false;
        /** 每分钟允许的最大请求数（全局兜底层）。 */
        private int requestsPerMinute = 120;
        /** 限流算法：fixed-window（固定窗口，默认）| sliding-window（滑动窗口，更平滑防突刺）。 */
        private String algorithm = "fixed-window";
        /** 滑动窗口时间窗大小（秒），仅 algorithm=sliding-window 时生效。 */
        private int windowSeconds = 60;
        /** 是否启用规则层（按路径前缀匹配后台维护的限流规则）。默认关闭。 */
        private boolean ruleEnabled = false;
        /** 规则存储模式：memory（进程内空规则，默认）| jdbc（读 cw_rate_limit_rule，后台可维护）。 */
        private String storeMode = "memory";
        /** 是否开启规则定时刷新（后台改规则后自动生效）。默认开启。 */
        private boolean refreshEnabled = true;
        /** 规则刷新轮询间隔（毫秒，默认 60s）；每轮只查一次版本指纹，指纹变了才换快照。 */
        private long refreshIntervalMs = 60_000L;
    }

    /**
     * 审批操作员身份鉴权（把关退款审批 approve/deny 的身份来源）。
     *
     * <p>关闭时，审批操作员身份取自请求方自报的 {@code operator} 参数，任何人可冒充任意坐席放行退款；
     * 生产必须开启并配置 {@code operators}，使操作员身份改由服务端凭 token 解析，而非客户端自由输入。</p>
     */
    @Data
    public static class ApprovalAuth {
        /** 是否启用（默认关闭；生产建议开启，仅作用于 approve/deny 两个资金放行端点）。 */
        private boolean enabled = false;
        /** 携带审批操作员 token 的请求头名。 */
        private String headerName = "X-Approval-Token";
        /** token → 操作员姓名映射；每个坐席一个独立 token，避免共享凭证导致的责任不可追溯。 */
        private Map<String, String> operators = new LinkedHashMap<>();
    }
}
