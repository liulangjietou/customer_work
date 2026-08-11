package com.richard.fyoung.customerwork.devtool;

import lombok.Builder;
import lombok.Getter;
import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron 表达式解析纯函数集：校验、逐字段释义、推算后续若干次执行时间。
 *
 * <p><b>为什么必须由后端算</b>：本项目的定时任务最终由 XXL-JOB 调度中心按 Quartz 风格的 6 段
 * cron 触发，而浏览器端 cron 库多按 Unix 5 段语义解析，同一串表达式两边算出的"下次执行时间"
 * 可能不一致——那样的工具会误导排查。这里统一用 Spring 的 {@link CronExpression}（6 段：
 * 秒 分 时 日 月 周，与 XXL-JOB 一致）作为唯一真源，页面与智能体都走它。</p>
 *
 * <p>无 Spring 容器依赖（只用到 spring-context 的纯工具类）、无状态，参数非法入口 fast-fail。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class CronDevToolOps {

    /** 默认时区：与调度中心、业务库时间口径一致。 */
    private static final String DEFAULT_ZONE = "Asia/Shanghai";

    /** 推算执行时间的条数：默认与上限。 */
    private static final int DEFAULT_NEXT_COUNT = 5;
    private static final int MAX_NEXT_COUNT = 20;

    /** 本工具（及 XXL-JOB）采用的 cron 段数。 */
    private static final int SUPPORTED_FIELD_COUNT = 6;
    /** Unix 风格 cron 段数（缺秒段）。 */
    private static final int UNIX_FIELD_COUNT = 5;
    /** Quartz 可选带年份的段数。 */
    private static final int QUARTZ_WITH_YEAR_FIELD_COUNT = 7;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 六个字段的中文名（顺序即 cron 段顺序）。 */
    private static final String[] FIELD_NAMES = {"秒", "分钟", "小时", "日", "月", "星期"};

    /** 各字段的取值范围说明，用于释义里点明合法区间。 */
    private static final String[] FIELD_RANGES = {"0-59", "0-59", "0-23", "1-31", "1-12", "0-7(0和7均为周日)"};

    /**
     * 解析 cron 表达式：校验合法性、逐字段释义、推算后续执行时间。
     *
     * @param expression cron 表达式，6 段（秒 分 时 日 月 周）
     * @param count      推算的执行时间条数，1~20，null 取默认 5
     * @param timezone   时区 ID，null 取 Asia/Shanghai
     * @return 解析结果
     */
    public CronExplainResult explain(String expression, Integer count, String timezone) {
        DevToolArgs.requireNonBlank(expression, "expression");
        int nextCount = resolveCount(count);
        ZoneId zoneId = resolveZone(timezone);
        String normalized = expression.trim().replaceAll("\\s+", " ");
        checkFieldCount(normalized);

        CronExpression cron;
        try {
            cron = CronExpression.parse(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cron 表达式非法：" + e.getMessage(), e);
        }

        return CronExplainResult.builder()
            .expression(normalized)
            .timezone(zoneId.getId())
            .fields(describeFields(normalized))
            .nextTimes(computeNextTimes(cron, zoneId, nextCount))
            .build();
    }

    /** 段数不符时给出可直接照做的修正建议（这是最高频的输入错误）。 */
    private void checkFieldCount(String normalized) {
        int fieldCount = normalized.split(" ").length;
        if (fieldCount == SUPPORTED_FIELD_COUNT) {
            return;
        }
        if (fieldCount == UNIX_FIELD_COUNT) {
            throw new IllegalArgumentException("检测到 5 段的 Unix 风格 cron，本项目（XXL-JOB）用 6 段"
                + "（秒 分 时 日 月 周）：在最前面补一段秒即可，例如 \"0 " + normalized + "\"");
        }
        if (fieldCount == QUARTZ_WITH_YEAR_FIELD_COUNT) {
            throw new IllegalArgumentException("检测到 7 段（末段为年份）的 Quartz cron，本工具与 XXL-JOB "
                + "只用前 6 段，去掉年份段后重试");
        }
        throw new IllegalArgumentException("cron 必须是 6 段（秒 分 时 日 月 周），当前 " + fieldCount + " 段");
    }

    /** 逐段释义。 */
    private List<CronFieldDesc> describeFields(String normalized) {
        String[] parts = normalized.split(" ");
        List<CronFieldDesc> list = new ArrayList<>(SUPPORTED_FIELD_COUNT);
        for (int i = 0; i < SUPPORTED_FIELD_COUNT; i++) {
            list.add(CronFieldDesc.builder()
                .name(FIELD_NAMES[i])
                .value(parts[i])
                .range(FIELD_RANGES[i])
                .description(describeFieldValue(parts[i], FIELD_NAMES[i]))
                .build());
        }
        return list;
    }

    /**
     * 释义单个字段。只做结构化拆解（每个/每隔/枚举/区间/特殊字符），不拼整句自然语言——
     * 整句描述在多字段组合下极易出错，逐段说明配合下方"后续执行时间"已足够定位问题。
     */
    private String describeFieldValue(String value, String fieldName) {
        if ("*".equals(value)) {
            return "每" + fieldName;
        }
        if ("?".equals(value)) {
            return "不指定（由另一个日期字段决定）";
        }
        if (value.startsWith("*/")) {
            return "每隔 " + value.substring(2) + " " + fieldName;
        }
        if (value.contains("/")) {
            String[] step = value.split("/", 2);
            return "从 " + step[0] + " 开始，每隔 " + step[1] + " " + fieldName;
        }
        if ("L".equals(value)) {
            return "最后一" + fieldName;
        }
        if (value.endsWith("L")) {
            return "本月最后一个星期" + value.substring(0, value.length() - 1);
        }
        if (value.endsWith("W")) {
            return "最接近 " + value.substring(0, value.length() - 1) + " 号的工作日";
        }
        if (value.contains("#")) {
            String[] nth = value.split("#", 2);
            return "本月第 " + nth[1] + " 个星期" + nth[0];
        }
        if (value.contains(",")) {
            return "第 " + value.replace(",", "、") + " " + fieldName;
        }
        if (value.contains("-")) {
            String[] range = value.split("-", 2);
            return "第 " + range[0] + " 到 " + range[1] + " " + fieldName;
        }
        return "第 " + value + " " + fieldName;
    }

    /** 从当前时刻起推算后续执行时间。表达式永远不会再触发时返回空列表（如指定了已过去的具体日期）。 */
    private List<String> computeNextTimes(CronExpression cron, ZoneId zoneId, int count) {
        List<String> times = new ArrayList<>(count);
        ZonedDateTime cursor = ZonedDateTime.now(zoneId);
        for (int i = 0; i < count; i++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null) {
                break;
            }
            times.add(next.format(TIME_FORMATTER));
            cursor = next;
        }
        return times;
    }

    /** 条数取值：null 取默认，越界 fast-fail。 */
    private int resolveCount(Integer count) {
        if (count == null) {
            return DEFAULT_NEXT_COUNT;
        }
        if (count < 1 || count > MAX_NEXT_COUNT) {
            throw new IllegalArgumentException("count 必须在 1~" + MAX_NEXT_COUNT + " 之间，当前 " + count);
        }
        return count;
    }

    /** 时区取值：null/空取默认，非法 ID fast-fail。 */
    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.trim().isEmpty()) {
            return ZoneId.of(DEFAULT_ZONE);
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("时区 ID 非法：" + timezone + "，如 Asia/Shanghai、UTC", e);
        }
    }

    /**
     * cron 解析结果。
     */
    @Getter
    @Builder
    public static class CronExplainResult {
        /** 归一化后的表达式（多余空白已压缩）。 */
        private final String expression;
        /** 推算所用时区。 */
        private final String timezone;
        /** 逐字段释义。 */
        private final List<CronFieldDesc> fields;
        /** 后续执行时间（yyyy-MM-dd HH:mm:ss）；表达式不会再触发时为空列表。 */
        private final List<String> nextTimes;
    }

    /**
     * cron 单字段释义。
     */
    @Getter
    @Builder
    public static class CronFieldDesc {
        /** 字段名（秒/分钟/小时/日/月/星期）。 */
        private final String name;
        /** 该字段的原始取值。 */
        private final String value;
        /** 该字段的合法取值范围。 */
        private final String range;
        /** 取值释义。 */
        private final String description;
    }
}
