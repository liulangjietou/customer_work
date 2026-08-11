package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 合成监控探测单测：正常回复计 UP、兜底回复计 DEGRADED、空/异常计 DOWN，且每次探测后清理探针会话。
 * @author owlzhangfq@gmail.com
 */
class SyntheticMonitorTest {

    private static final String METRIC = "customerwork.synthetic.probe";
    private static final String PROBE_SESSION = "synthetic:healthcheck";

    @SuppressWarnings("unchecked")
    private SyntheticMonitor monitor(CustomerServiceService service, SimpleMeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new SyntheticMonitor(service, new CustomerWorkProperties(), provider);
    }

    private double probeCount(SimpleMeterRegistry registry, String result) {
        return registry.get(METRIC).tag("result", result).counter().count();
    }

    @Test
    void normalReplyCountsUp() {
        CustomerServiceService service = mock(CustomerServiceService.class);
        when(service.chat(anyString(), anyString())).thenReturn(Mono.just("您好，有什么可以帮您？"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        monitor(service, registry).probe();

        assertEquals(1.0, probeCount(registry, "UP"));
        verify(service, times(1)).endSession(eq(PROBE_SESSION));   // 探测后清理探针会话
    }

    @Test
    void fallbackReplyCountsDegraded() {
        CustomerServiceService service = mock(CustomerServiceService.class);
        when(service.chat(anyString(), anyString()))
            .thenReturn(Mono.just(CustomerServiceService.FALLBACK_REPLY));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        monitor(service, registry).probe();

        assertEquals(1.0, probeCount(registry, "DEGRADED"), "走兜底回复应判定为链路降级");
    }

    @Test
    void emptyReplyCountsDown() {
        CustomerServiceService service = mock(CustomerServiceService.class);
        when(service.chat(anyString(), anyString())).thenReturn(Mono.just(""));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        monitor(service, registry).probe();

        assertEquals(1.0, probeCount(registry, "DOWN"));
    }

    @Test
    void errorCountsDownAndStillCleansUp() {
        CustomerServiceService service = mock(CustomerServiceService.class);
        when(service.chat(anyString(), anyString())).thenReturn(Mono.error(new RuntimeException("model down")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        monitor(service, registry).probe();

        assertEquals(1.0, probeCount(registry, "DOWN"));
        verify(service, times(1)).endSession(anyString());   // 出错也要清理
    }
}
