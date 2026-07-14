package com.richard.fyoung.customeradmin.sqlconfig.engine;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参数默认值表达式解析：
 * <ul>
 *   <li>{@code ${now}} → 当前时间 {@code yyyy-MM-dd HH:mm:ss}</li>
 *   <li>{@code ${now-14d}} / {@code ${now+2h}} / {@code ${now-30m}} → 当前时间按 d/h/m 加减</li>
 *   <li>非 {@code ${...}} 表达式 → 原样返回（含 null）</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public final class DefaultValueResolver {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** ${now}、${now±N[d|h|m]}。 */
    private static final Pattern NOW_EXPR = Pattern.compile("^\\$\\{now([+-]\\d+)([dhm])\\}$");
    private static final Pattern NOW_PLAIN = Pattern.compile("^\\$\\{now\\}$");

    private DefaultValueResolver() {
    }

    public static String resolve(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (NOW_PLAIN.matcher(trimmed).matches()) {
            return LocalDateTime.now().format(FORMATTER);
        }
        Matcher matcher = NOW_EXPR.matcher(trimmed);
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            LocalDateTime base = LocalDateTime.now();
            LocalDateTime result = switch (unit) {
                case "d" -> base.plusDays(amount);
                case "h" -> base.plusHours(amount);
                case "m" -> base.plusMinutes(amount);
                default -> base;
            };
            return result.format(FORMATTER);
        }
        return expression;
    }
}
