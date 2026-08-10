package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 合成监控 / 主动探活（在用户上报前发现故障）。
 *
 * <p>定时用固定探针会话打<b>真实对话链路</b>（{@link CustomerServiceService#chat}），校验：
 * 能返回、非空、且不是兜底回复（{@link CustomerServiceService#FALLBACK_REPLY}——它意味着链路已降级）。
 * 结果发指标 {@code customerwork.synthetic.probe}（tag result=UP/DEGRADED/DOWN）+ 结构化日志，
 * 供 Prometheus 告警。探针会话使用独立 sessionId，探测后清理其状态，避免污染真实会话与堆积。</p>
 *
 * <p><b>默认关闭</b>（{@code synthetic-monitor.enabled=true} 才装配）——每次探测真实调用模型、产生费用。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
@ConditionalOnProperty(prefix = "customer-work.synthetic-monitor", name = "enabled", havingValue = "true")
public class SyntheticMonitor {

    private static final Logger log = LoggerFactory.getLogger(SyntheticMonitor.class);
    private static final String M_PROBE = "customerwork.synthetic.probe";

    private final CustomerServiceService customerService;
    private final CustomerWorkProperties.SyntheticMonitor cfg;
    /** 可为 null：未接入 Micrometer 时仅日志。 */
    private final MeterRegistry meterRegistry;

    public SyntheticMonitor(CustomerServiceService customerService,
                            CustomerWorkProperties properties,
                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.customerService = customerService;
        this.cfg = properties.getSyntheticMonitor();
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    @Scheduled(fixedDelayString = "${customer-work.synthetic-monitor.interval-ms:300000}")
    public void probe() {
        long start = System.nanoTime();
        String result;
        try {
            String reply = customerService.chat(cfg.getSessionId(), cfg.getMessage())
                .block(Duration.ofSeconds(cfg.getTimeoutSeconds()));
            long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
            if (!StringUtils.hasText(reply)) {
                result = "DOWN";
                log.error("[SYNTH] probe empty reply, code={} latencyMs={}", "SYNTHETIC_PROBE_DOWN", ms);
            } else if (CustomerServiceService.FALLBACK_REPLY.equals(reply)) {
                result = "DEGRADED";
                log.error("[SYNTH] probe hit fallback reply, code={} latencyMs={}",
                    "SYNTHETIC_PROBE_DEGRADED", ms);
            } else {
                result = "UP";
                log.info("[SYNTH] probe ok, latencyMs={}", ms);
            }
        } catch (Exception e) {
            result = "DOWN";
            log.error("[SYNTH] probe failed, code={}", "SYNTHETIC_PROBE_DOWN", e);
        } finally {
            // 清理探针会话状态，避免与真实会话共用命名空间时堆积
            try {
                customerService.endSession(cfg.getSessionId());
            } catch (Exception e) {
                log.error("[SYNTH] cleanup probe session failed, code={}", "SYNTHETIC_CLEANUP_ERROR", e);
            }
        }
        if (meterRegistry != null) {
            Counter.builder(M_PROBE).tag("result", result).register(meterRegistry).increment();
        }
    }
}
