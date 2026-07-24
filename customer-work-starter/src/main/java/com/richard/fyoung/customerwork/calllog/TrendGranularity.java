package com.richard.fyoung.customerwork.calllog;

/**
 * 趋势聚合粒度：按天 / 按小时。{@link #dateFormat} 为 MySQL {@code DATE_FORMAT} 的格式串，
 * 同时用于内存实现的 {@link java.time.format.DateTimeFormatter} 分桶（保持两实现桶标签一致）。
 * @author owlzhangfq@gmail.com
 */
public enum TrendGranularity {

    DAY("%Y-%m-%d", "yyyy-MM-dd"),
    HOUR("%Y-%m-%d %H", "yyyy-MM-dd HH");

    private final String mysqlFormat;
    private final String javaPattern;

    TrendGranularity(String mysqlFormat, String javaPattern) {
        this.mysqlFormat = mysqlFormat;
        this.javaPattern = javaPattern;
    }

    /** MySQL DATE_FORMAT 格式串。 */
    public String mysqlFormat() {
        return mysqlFormat;
    }

    /** Java DateTimeFormatter 模式串（内存实现分桶用）。 */
    public String javaPattern() {
        return javaPattern;
    }
}
