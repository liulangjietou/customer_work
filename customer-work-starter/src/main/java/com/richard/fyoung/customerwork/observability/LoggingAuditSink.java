package com.richard.fyoung.customerwork.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 默认审计落地实现：写专用 logger {@code customer-work.audit}（可在 logback 中单独路由到审计文件）。
 *
 * <p>输出为 {@code key=value} 有序拼接，避免引入额外 JSON 依赖；下游需要结构化投递时实现自己的
 * {@link AuditSink} 即可覆盖本默认实现。</p>
 * @author owlzhangfq@gmail.com
 */
public class LoggingAuditSink implements AuditSink {

    private static final Logger audit = LoggerFactory.getLogger("customer-work.audit");

    @Override
    public void record(String type, Map<String, Object> fields) {
        String body = fields.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(" "));
        audit.info("type={} {}", type, body);
    }
}
