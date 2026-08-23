package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthErrorCategory;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.config.ModelHealthExecutionConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.config.ModelHealthMonitorProperties;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.LocalDateTime;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 健康探测的 SecretRef 使用、错误分类、脱敏与共享记录只读边界测试。 */
class ModelHealthServiceTest {

    private ModelConfigAccess modelConfigAccess;
    private AiModelConfigMapper modelConfigMapper;
    private SecretRefService secretRefService;
    private AdminModelFactory modelFactory;
    private ModelHealthStore healthStore;
    private AdminTenantProperties tenantProperties;
    private ModelEndpointPolicy endpointPolicy;
    private ModelHealthMonitorProperties monitorProperties;
    private ThreadPoolTaskExecutor probeExecutor;
    private ThreadPoolTaskScheduler timeoutScheduler;

    @BeforeEach
    void setUp() {
        modelConfigAccess = mock(ModelConfigAccess.class);
        modelConfigMapper = mock(AiModelConfigMapper.class);
        secretRefService = mock(SecretRefService.class);
        modelFactory = mock(AdminModelFactory.class);
        healthStore = mock(ModelHealthStore.class);
        tenantProperties = new AdminTenantProperties();
        endpointPolicy = new ModelEndpointPolicy(List::of,
            host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        monitorProperties = new ModelHealthMonitorProperties();
        ModelHealthExecutionConfig executionConfig = new ModelHealthExecutionConfig();
        probeExecutor = executionConfig.modelHealthProbeExecutor(monitorProperties);
        probeExecutor.initialize();
        timeoutScheduler = executionConfig.modelHealthTimeoutScheduler();
        timeoutScheduler.initialize();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        probeExecutor.shutdown();
        timeoutScheduler.shutdown();
    }

    @Test
    void probe_shouldClassifyAuthFailureAndRedactSecretBeforePersistence() throws Exception {
        tenantProperties.setEnabled(false);
        AiModelConfig model = model("default");
        String secret = "sk-never-log-this";
        when(modelConfigAccess.findVisibleAnyStateById(11L)).thenReturn(model);
        when(secretRefService.resolvePlaintext(model)).thenReturn(secret);
        when(modelFactory.testConnectivity("openai", "https://example.test/v1", secret, "gpt-test"))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.FAILED, LocalDateTime.now(),
                "401 invalid credential " + secret));
        when(healthStore.record(eq(model), any(ModelTestResult.class), eq(ModelProbeSource.MANUAL)))
            .thenReturn(recorded(ModelHealthStatus.DEGRADED.name()));
        ModelHealthService service = service();

        ModelTestResult result = service.probe(11L, ModelProbeSource.MANUAL)
            .get(2, TimeUnit.SECONDS);

        assertEquals(ModelHealthErrorCategory.AUTH.name(), result.errorCategory());
        assertFalse(result.message().contains(secret));
        ArgumentCaptor<ModelTestResult> persisted = ArgumentCaptor.forClass(ModelTestResult.class);
        verify(healthStore).record(eq(model), persisted.capture(), eq(ModelProbeSource.MANUAL));
        assertFalse(persisted.getValue().message().contains(secret));
        verify(modelConfigMapper).updateById(any(AiModelConfig.class));
    }

    @Test
    void probe_shouldClassifyRateLimitWithoutMarkingAuthenticationFailed() throws Exception {
        tenantProperties.setEnabled(false);
        AiModelConfig model = model("default");
        when(modelConfigAccess.findVisibleAnyStateById(11L)).thenReturn(model);
        when(secretRefService.resolvePlaintext(model)).thenReturn("secret");
        when(modelFactory.testConnectivity(any(), any(), any(), any()))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.FAILED, LocalDateTime.now(),
                "429 rate limit exceeded"));
        when(healthStore.record(eq(model), any(ModelTestResult.class), eq(ModelProbeSource.SCHEDULED)))
            .thenReturn(recorded(ModelHealthStatus.DEGRADED.name()));

        ModelTestResult result = service().probe(11L, ModelProbeSource.SCHEDULED)
            .get(2, TimeUnit.SECONDS);

        assertEquals(ModelHealthErrorCategory.RATE_LIMIT.name(), result.errorCategory());
    }

    @Test
    void businessTenantProbeOfSharedDeployment_shouldRemainReadOnly() throws Exception {
        tenantProperties.setEnabled(true);
        TenantContext.set("tenant-a");
        AiModelConfig shared = model(TenantContext.DEFAULT);
        when(modelConfigAccess.findVisibleAnyStateById(11L)).thenReturn(shared);
        when(secretRefService.resolvePlaintext(shared)).thenReturn("secret");
        when(modelFactory.testConnectivity(any(), any(), any(), any()))
            .thenReturn(new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null));

        ModelTestResult result = service().probe(11L, ModelProbeSource.MANUAL)
            .get(2, TimeUnit.SECONDS);

        assertEquals(ModelHealthStatus.HEALTHY.name(), result.healthStatus());
        verify(healthStore, never()).record(any(), any(), any());
        verify(modelConfigMapper, never()).updateById(any(AiModelConfig.class));
    }

    @Test
    void probe_shouldRejectDangerousEndpointBeforeResolvingCredential() throws Exception {
        tenantProperties.setEnabled(false);
        endpointPolicy = new ModelEndpointPolicy(List::of);
        AiModelConfig model = model("default");
        model.setBaseUrl("http://127.0.0.1:11434/v1");
        when(modelConfigAccess.findVisibleAnyStateById(11L)).thenReturn(model);
        when(healthStore.record(eq(model), any(ModelTestResult.class), eq(ModelProbeSource.MANUAL)))
            .thenReturn(recorded(ModelHealthStatus.DEGRADED.name()));

        ModelTestResult result = service().probe(11L, ModelProbeSource.MANUAL)
            .get(2, TimeUnit.SECONDS);

        assertEquals(ModelHealthErrorCategory.CONTRACT.name(), result.errorCategory());
        verify(secretRefService, never()).resolvePlaintext(any());
        verify(modelFactory, never()).testConnectivity(any(), any(), any(), any());
    }

    @Test
    void queuedProbe_shouldStartItsTimeoutOnlyAfterAWorkerActuallyBeginsExecution() throws Exception {
        probeExecutor.shutdown();
        timeoutScheduler.shutdown();
        monitorProperties.setWorkerCount(1);
        monitorProperties.setQueueCapacity(1);
        monitorProperties.setProbeTimeoutSeconds(1);
        ModelHealthExecutionConfig executionConfig = new ModelHealthExecutionConfig();
        probeExecutor = executionConfig.modelHealthProbeExecutor(monitorProperties);
        probeExecutor.initialize();
        timeoutScheduler = executionConfig.modelHealthTimeoutScheduler();
        timeoutScheduler.initialize();
        tenantProperties.setEnabled(true);
        TenantContext.set("tenant-a");
        AiModelConfig first = model(TenantContext.DEFAULT);
        AiModelConfig second = model(TenantContext.DEFAULT);
        second.setId(12L);
        AiModelConfig rejected = model(TenantContext.DEFAULT);
        rejected.setId(13L);
        when(modelConfigAccess.findVisibleAnyStateById(11L)).thenReturn(first);
        when(modelConfigAccess.findVisibleAnyStateById(12L)).thenReturn(second);
        when(modelConfigAccess.findVisibleAnyStateById(13L)).thenReturn(rejected);
        when(secretRefService.resolvePlaintext(any())).thenReturn("secret");
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(modelFactory.testConnectivity(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                while (releaseFirst.getCount() > 0) {
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException ignored) {
                        // 模拟无法由中断立即终止的第三方 SDK；在途上限仍由有界线程池控制。
                    }
                }
            }
            return new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(), null);
        });
        ModelHealthService service = service();

        java.util.concurrent.CompletableFuture<ModelTestResult> firstProbe =
            service.probe(11L, ModelProbeSource.MANUAL);
        java.util.concurrent.CompletableFuture<ModelTestResult> queuedProbe =
            service.probe(12L, ModelProbeSource.MANUAL);
        ModelTestResult rejectedProbe = service.probe(13L, ModelProbeSource.MANUAL)
            .get(200, TimeUnit.MILLISECONDS);
        ModelTestResult timedOut = firstProbe.get(2, TimeUnit.SECONDS);
        releaseFirst.countDown();

        assertEquals(ModelHealthErrorCategory.UNKNOWN.name(), rejectedProbe.errorCategory());
        assertEquals(ModelHealthErrorCategory.TIMEOUT.name(),
            timedOut.errorCategory());
        assertEquals(ModelHealthStatus.HEALTHY.name(),
            queuedProbe.get(2, TimeUnit.SECONDS).healthStatus());
    }

    private ModelHealthService service() {
        return new ModelHealthService(modelConfigAccess, modelConfigMapper, secretRefService,
            modelFactory, healthStore, tenantProperties, endpointPolicy, monitorProperties,
            probeExecutor, timeoutScheduler);
    }

    private AiModelConfig model(String tenantId) {
        AiModelConfig model = new AiModelConfig();
        model.setId(11L);
        model.setTenantId(tenantId);
        model.setProvider("openai");
        model.setProtocolAdapter("openai");
        model.setBaseUrl("https://example.test/v1");
        model.setModel("gpt-test");
        return model;
    }

    private ModelHealthSnapshotVO snapshot(String status) {
        return new ModelHealthSnapshotVO(status, "UNKNOWN", "UNKNOWN", 1, 10L,
            null, null, LocalDateTime.now(), null, null, LocalDateTime.now().plusMinutes(5), 1);
    }

    private ModelHealthStore.RecordResult recorded(String status) {
        return new ModelHealthStore.RecordResult(snapshot(status), true);
    }
}
