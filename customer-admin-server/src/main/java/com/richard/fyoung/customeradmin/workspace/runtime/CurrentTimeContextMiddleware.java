package com.richard.fyoung.customeradmin.workspace.runtime;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * 为模型提供本轮请求的当前时间，避免“今天星期几”等确定性问题先调用工具、再进行第二轮推理。
 *
 * <p>时间只在 {@code onSystemPrompt} 阶段瞬时注入，不写入用户消息和会话历史；格式中同时包含偏移量与
 * ZoneId，部署在不同时区时也不会把服务器本地时间误称为北京时间。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class CurrentTimeContextMiddleware implements MiddlewareBase {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm:ss EEEE XXX '['VV']'", Locale.SIMPLIFIED_CHINESE);

    private final Clock clock;

    CurrentTimeContextMiddleware() {
        this(Clock.systemDefaultZone());
    }

    CurrentTimeContextMiddleware(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String now = ZonedDateTime.now(clock).format(FORMATTER);
        String runtimeNote = String.format(
            "%n%n[运行时上下文] 当前服务器时间=%s。询问当前日期、星期或时间时直接使用该事实；"
                + "除非用户明确要求时区换算，否则不要调用时间工具。",
            now);
        return Mono.just((currentPrompt == null ? "" : currentPrompt) + runtimeNote);
    }
}
