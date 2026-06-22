package com.richard.fyoung.customerwork.observability;

import java.util.Map;

/**
 * 审计落地 SPI（合规审计轨迹的输出端）。
 *
 * <p>默认实现 {@link LoggingAuditSink} 把审计记录写到专用日志 logger，便于 logback 单独路由到审计文件。
 * 下游可声明自己的 {@code AuditSink} Bean（默认实现以 {@code @ConditionalOnMissingBean} 让位），
 * 把审计轨迹投递到 Kafka / 数据库 / SIEM 等。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AuditSink {

    /**
     * 记录一条审计事件。
     *
     * @param type   事件类型（如 {@code tool-call} / {@code final-answer} / {@code error}）
     * @param fields 结构化字段（有序）
     */
    void record(String type, Map<String, Object> fields);
}
