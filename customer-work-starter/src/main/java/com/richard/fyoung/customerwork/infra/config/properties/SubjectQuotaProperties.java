package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主体级速率配额配置（每个用户 / 每个匿名 IP / 每把 API Key 的滚动窗口限流）。
 *
 * <p>默认关闭——没开时行为与引入本功能之前完全一致。与租户配额（{@link QuotaProperties}）
 * 是两件事：那个管"这个客户这个月能花多少钱"，这个管"单个调用者半小时内能用多少"，
 * 一个是计费上限、一个是防滥用闸门，同时生效互不替代。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class SubjectQuotaProperties {

    /** 是否开启主体配额判定。关闭时一律放行，也不记账。 */
    private boolean enabled = false;

    /** 存储：{@code memory} 进程内 / {@code jdbc} 落 {@code cw_subject_quota_level}。 */
    private String storeMode = "memory";

    /** 等级快照轮询间隔（毫秒）。同时被 {@code @Scheduled} 的占位符引用，改这里即改刷新频率。 */
    private long refreshIntervalMs = 60000L;

    /** 注册用户的默认等级编码：新用户落这一档，后台可单独改人。 */
    private String defaultUserLevel = "free";

    /** 匿名调用方（无登录态，按 IP 计）的等级编码。 */
    private String anonymousLevel = "anonymous";

    /** 服务端接入方（API Key，按 Key 指纹计）的等级编码。 */
    private String apiKeyLevel = "api-key";

    /**
     * 用户等级绑定的本地缓存有效期（毫秒）。
     *
     * <p>不缓存的话每个请求都要查一次用户表——限流本身成了系统里最重的一段。
     * 代价是后台改等级后最多滞后这么久生效，默认 60 秒是可接受的折中。</p>
     */
    private long levelCacheTtlMs = 60000L;

    /** 用户等级缓存的最大条目数：超出即清理过期项，避免用户量大时无界增长。 */
    private int levelCacheMaxSize = 10000;

    /**
     * 参与判定的请求路径前缀。
     *
     * <p><b>次数口径是"HTTP 请求数"，不是"提问数"</b>：默认清单覆盖了整个
     * {@code /api/customer/user/} 面，因此翻历史消息、查工单也会占额度。这是刻意的——
     * 登录用户的所有接口都该有防刷闸门，而不是只防对话。代价是次数上限必须配得比
     * "纯提问次数"宽，见 {@link #defaultLevels()} 的取值。</p>
     *
     * <p>只想限对话时，把 {@code /api/customer/user/} 从清单里去掉即可；
     * 反过来，想给某条查询路径单独设更严的阈值，用既有的
     * {@code customer-work.security.rate-limit} 规则层（按路径配），两者正交共存。</p>
     */
    private List<String> paths = List.of(
        "/api/customer/chat",
        "/api/customer/intent",
        "/api/customer/consult",
        "/api/customer/agui",
        "/api/customer/user/");

    /**
     * 内置兜底档：等级表里查不到时用它，保证"没建表、没配库"也能开箱工作。
     *
     * <p>库里的同名等级<b>优先</b>——内置档只是地板，不是覆盖层。</p>
     */
    private Map<String, Level> builtinLevels = defaultLevels();

    /** 一档内置额度。 */
    @Data
    public static class Level {

        /** 适用主体类型：USER / IP / API_KEY。 */
        private String subjectType = "USER";

        /** 滚动窗口长度（秒），1800 = 30 分钟。 */
        private int windowSeconds = 1800;

        /** 窗口内 token 上限，0 = 不限。 */
        private long tokenLimit;

        /** 窗口内请求次数上限，0 = 不限。 */
        private int requestLimit;

        /** 超限处置：BLOCK 拦截 / WARN 仅记录。 */
        private String exceedAction = "BLOCK";

        /** 等级显示名。 */
        private String levelName;
    }

    /**
     * 出厂默认三档。
     *
     * <p>数值取向：匿名最紧（谁都能打，且同一 NAT 后共享一份额度）、注册用户居中、
     * 接入方最宽（一把 Key 背后是一个系统而非一个人）。</p>
     *
     * <p>次数看起来偏宽是因为口径含查询请求（见 {@link #paths}）：一次对话往往伴随
     * 若干次工单/消息查询，按"纯提问数"配会让用户在正常使用中就被拦。token 上限才是
     * 真正卡成本的那一维——它只在模型调用后累加，不受查询请求影响。</p>
     */
    private static Map<String, Level> defaultLevels() {
        Map<String, Level> levels = new LinkedHashMap<>();
        levels.put("free", level("USER", "免费用户", 1800, 50000L, 100));
        levels.put("anonymous", level("IP", "匿名访客", 1800, 10000L, 20));
        levels.put("api-key", level("API_KEY", "接入方", 3600, 1000000L, 2000));
        return levels;
    }

    private static Level level(String subjectType, String name, int windowSeconds, long tokenLimit, int requestLimit) {
        Level level = new Level();
        level.setSubjectType(subjectType);
        level.setLevelName(name);
        level.setWindowSeconds(windowSeconds);
        level.setTokenLimit(tokenLimit);
        level.setRequestLimit(requestLimit);
        return level;
    }
}
