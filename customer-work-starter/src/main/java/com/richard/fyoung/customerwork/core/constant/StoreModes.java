package com.richard.fyoung.customerwork.core.constant;

/**
 * 存储 / 实现模式取值常量。
 *
 * <p>本项目所有"选实现"的开关共用同一套取值词汇：各业务域的 {@code store-mode}、
 * {@code tool-backend.mode}、分布式设施的 {@code counter-mode} / {@code session-lock-mode}
 * 都在 {@code memory} / {@code jdbc} / {@code redis} 之间取值。此前这套词汇在 30 余处
 * 以 {@code STORE_MODE_JDBC} / {@code MODE_JDBC} / {@code JDBC} 三种命名各写一遍，
 * 还有几处直接写字面量比较——同一个概念有多个真相来源，改起来必漏。</p>
 *
 * <p>判定一律走 {@link #isJdbc(String)} 等方法：装配侧的语义是"没配就按默认走"，
 * 大小写不敏感由这里统一兜住，不要在调用处再各写一遍 {@code equalsIgnoreCase}。
 * 需要编译期常量的场合（{@code @ConditionalOnProperty} 的 {@code havingValue}、
 * {@code switch} 分支）直接引用字段本身。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class StoreModes {

    /** 进程内实现：重启即清零，仅适合离线测试与开发期。 */
    public static final String MEMORY = "memory";

    /** MyBatis-Plus 落库实现：生产默认形态。 */
    public static final String JDBC = "jdbc";

    /** Redis 实现：仅分布式设施（计数器 / 会话锁）使用。 */
    public static final String REDIS = "redis";

    private StoreModes() {
    }

    /** 是否 jdbc 模式（大小写不敏感，null 视为否）。 */
    public static boolean isJdbc(String mode) {
        return JDBC.equalsIgnoreCase(mode);
    }

    /** 是否 memory 模式（大小写不敏感，null 视为否）。 */
    public static boolean isMemory(String mode) {
        return MEMORY.equalsIgnoreCase(mode);
    }

    /** 是否 redis 模式（大小写不敏感，null 视为否）。 */
    public static boolean isRedis(String mode) {
        return REDIS.equalsIgnoreCase(mode);
    }
}
