package com.richard.fyoung.customerwork.safety.tenant;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 当前请求的租户上下文（全链路唯一真源）。
 *
 * <p>由接入层（API Key 鉴权 / H5 登录态 / admin 登录用户）在入口写入，持久层
 * {@link CustomerWorkTenantLineHandler} 在拼 SQL 时读取。中间的业务代码不感知租户——
 * 这正是把隔离做在拦截器而非各 Store 里的意义。</p>
 *
 * <p><b>为什么是 ThreadLocal 而不是 Reactor Context</b>：MyBatis 拦截器是同步 API，
 * 拿不到 Reactor Context。反过来让 WebFlux 侧把值同步到 ThreadLocal 才是可行方向，
 * 跨线程边界由 {@link TenantContextThreadLocalAccessor} + Reactor 自动传播兜住。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class TenantContext {

    /** 系统唯一保留租户：承接升级前存量数据，并作为共享配置的默认基线。 */
    public static final String DEFAULT = "default";

    /** 与管理端租户编码入参共用：首字符必须是字母或数字，总长度不超过 64。 */
    public static final String TENANT_ID_REGEX = "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$";

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile(TENANT_ID_REGEX);
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 写入当前租户；传空视为清理，避免半初始化状态被后续 SQL 读到。 */
    public static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT.remove();
            return;
        }
        if (!isValidTenantId(tenantId)) {
            throw new IllegalArgumentException("tenantId format is invalid");
        }
        CURRENT.set(canonicalizeTenantId(tenantId));
    }

    /**
     * 恢复一个<b>此前已经过 {@link #set} 校验</b>的租户值：跳过格式校验，保留归一。
     *
     * <p><b>只给上下文传播机制用</b>（{@link TenantContextThreadLocalAccessor}），业务代码一律用
     * {@link #set}。语义上这不是"写入一个新租户"，而是"把快照里的值放回当前线程"——值的唯一来源
     * 是某次 {@code set} 成功后被 Reactor Context 捕获的快照，格式在那一刻已经校验过，
     * 这里再验一遍纯属重复，且重复的位置恰恰是全链路最热的一点。</p>
     *
     * <p><b>为什么这一点这么热</b>：开启 {@code Hooks.enableAutomaticContextPropagation()} 后，
     * Reactor 会给链上<b>每个</b>算子包一层 {@code ContextWriteRestoringThreadLocals}，每层在
     * {@code onNext} 时都要把全部 ThreadLocal 快照恢复一遍。AI 流式链实测有 110+ 层，于是模型每吐
     * 一个增量，这个方法就要被调用上百次；原先走 {@code set} 时那上百次正则匹配会把一整颗核烧满，
     * 表现为流式输出一卡一卡（实测吞吐被压到约 5 字符/秒）。</p>
     *
     * <p>校验并未因此减弱：所有<b>入口</b>（鉴权过滤器、拦截器、{@link #callWith}）仍走 {@link #set}
     * 的全量校验，符合"整条链路只做一处防御式编程"的约定。</p>
     *
     * <p><b>归一仍然保留</b>，只跳过校验：并非所有写入方放进 Reactor Context 的都是归一后的值——
     * {@code UserAuthWebFilter}/{@code AgentAuthWebFilter} 放的是原始入参（{@code ApiKeyAuthWebFilter}
     * 放的才是 {@code canonicalTenant}）。跳过归一会让 {@code "DEFAULT"} 这类写法在传播后变成与
     * {@link #set} 不同的值，破坏"保留租户只有一份"的约定。而 {@link #canonicalizeTenantId} 只是一次
     * {@code equalsIgnoreCase}（长度不等即短路），与被去掉的正则不在一个量级上。</p>
     */
    static void restore(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(canonicalizeTenantId(tenantId));
    }

    /** 统一校验所有会话、JWT、API Key 与后台请求带入的租户编码。 */
    public static boolean isValidTenantId(String tenantId) {
        return tenantId != null && TENANT_ID_PATTERN.matcher(tenantId).matches();
    }

    /** 只归一系统保留租户；业务租户保留原大小写，避免改变已有外部资源命名空间。 */
    public static String canonicalizeTenantId(String tenantId) {
        return isDefaultTenant(tenantId) ? DEFAULT : tenantId;
    }

    /** 数据库大小写不敏感且可重建的内部键使用此规范形；不得用于对象存储、Nacos、工作区等外部命名空间。 */
    public static String normalizedTenantKey(String tenantId) {
        return tenantId == null ? null : tenantId.toLowerCase(Locale.ROOT);
    }

    /** 两个租户编码是否指向数据库里的同一租户。 */
    public static boolean sameTenant(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    /** default 在数据库大小写不敏感排序规则下只有一份，Java 边界必须采用相同判定语义。 */
    public static boolean isDefaultTenant(String tenantId) {
        return sameTenant(DEFAULT, tenantId);
    }

    /** 读取当前租户，未设置返回 {@code null}（判空由调用方按场景决定 fail-open 还是 fail-closed）。 */
    public static String get() {
        return CURRENT.get();
    }

    /** 读取当前租户，未设置直接抛出——持久层等安全边界用这个，绝不允许"没租户也查得到数据"。 */
    public static String require() {
        String tenantId = CURRENT.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantContextMissingException();
        }
        return tenantId;
    }

    /** 是否已设置租户。 */
    public static boolean isPresent() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在指定租户下执行一段逻辑，结束后恢复原值。
     *
     * <p>给三类没有请求上下文的场景用：定时任务、启动期初始化、控制面用户切换目标租户后的跨租户操作。
     * 恢复而非清理，是因为调用可能嵌套（控制面用户在原上下文里临时进入某租户）。</p>
     */
    public static <T> T callWith(String tenantId, Supplier<T> action) {
        String previous = CURRENT.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            set(previous);
        }
    }

    /** {@link #callWith(String, Supplier)} 的无返回值版本。 */
    public static void runWith(String tenantId, Runnable action) {
        callWith(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
