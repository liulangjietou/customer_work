package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 可观测性配置。 */
@Data
public class ObservabilityProperties {
    /** 是否把全链路 trace 导出为 JSONL 文件（数据飞轮采集）。 */
    private boolean traceEnabled = false;
    private String traceFile = "./data/traces/agent-trace.jsonl";
    /** 是否启用框架原生链路追踪 Hook（TracerRegistry），按模型/工具/Agent 调用打 span。 */
    private boolean tracingEnabled = false;
    /**
     * 是否把 Reactor Context 中的 requestId / sessionId 同步到 SLF4J MDC，
     * 使反应式链路上任意线程的日志都能带上全链路关联字段（配合 logback pattern 的 %X{requestId}）。
     * 生产建议开启——线上故障定位的最短路径（用户报障给 requestId，日志按之聚合）。
     */
    private boolean mdcEnabled = true;
    /**
     * 是否解析上游 W3C {@code traceparent} 头，把 trace-id 关联进日志 MDC（键 traceId），
     * 使本服务日志与外部分布式链路（Jaeger/Tempo）共享同一 traceId。零外部依赖，默认开启。
     */
    private boolean traceCorrelationEnabled = true;
    /** Studio 可视化调试对接。 */
    private final Studio studio = new Studio();
    /** OpenTelemetry SDK 接入（真正采集并导出 span 到 Collector/Tempo）。 */
    private final Otel otel = new Otel();

    @Data
    public static class Studio {
        private boolean enabled = false;
        private String url = "";
        private String project = "customer-work";
        private String runName = "customer-work-run";
    }

    /**
     * OpenTelemetry SDK 配置（{@code customer-work.observability.otel.*}）。
     *
     * <p>与上面的 {@code tracingEnabled}（框架 TracerRegistry + LoggingTracer，只打日志）、
     * {@code traceCorrelationEnabled}（只解析 traceparent 做日志关联，零外部依赖）是三个不同层次：
     * 本组开启后才真正在进程内采集 span 并经 OTLP 导出，是"最后一公里"。</p>
     */
    @Data
    public static class Otel {
        /** 是否接入 OTel SDK（注册 GlobalOpenTelemetry + 挂 OtelTracingMiddleware + HTTP server span）。 */
        private boolean enabled = false;
        /** OTLP gRPC 接收端地址（Collector / Tempo）。 */
        private String endpoint = "http://localhost:4317";
        /** 资源属性 service.name，链路后台按之区分服务。 */
        private String serviceName = "customer-work";
        /** 采样比例 [0,1]：1.0 全采，生产高流量建议下调（父级已采样的链路始终跟随父级）。 */
        private double samplerRatio = 1.0d;
    }
}
