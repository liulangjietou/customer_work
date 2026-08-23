package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthErrorCategory;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.config.ModelHealthMonitorProperties;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthEventVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 模型健康探测、错误分类和健康历史读取。 */
@Service
public class ModelHealthService {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthService.class);
    private static final long MIN_TIMEOUT_SECONDS = 1L;
    private static final long MAX_TIMEOUT_SECONDS = 300L;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final ModelConfigAccess modelConfigAccess;
    private final AiModelConfigMapper modelConfigMapper;
    private final SecretRefService secretRefService;
    private final AdminModelFactory modelFactory;
    private final ModelHealthStore healthStore;
    private final AdminTenantProperties tenantProperties;
    private final ModelEndpointPolicy endpointPolicy;
    private final ModelHealthMonitorProperties monitorProperties;
    private final ThreadPoolTaskExecutor probeExecutor;
    private final ThreadPoolTaskScheduler timeoutScheduler;

    public ModelHealthService(ModelConfigAccess modelConfigAccess,
                              AiModelConfigMapper modelConfigMapper,
                              SecretRefService secretRefService,
                              AdminModelFactory modelFactory,
                              ModelHealthStore healthStore,
                              AdminTenantProperties tenantProperties,
                              ModelEndpointPolicy endpointPolicy,
                              ModelHealthMonitorProperties monitorProperties,
                              @Qualifier("modelHealthProbeExecutor") ThreadPoolTaskExecutor probeExecutor,
                              @Qualifier("modelHealthTimeoutScheduler") ThreadPoolTaskScheduler timeoutScheduler) {
        this.modelConfigAccess = modelConfigAccess;
        this.modelConfigMapper = modelConfigMapper;
        this.secretRefService = secretRefService;
        this.modelFactory = modelFactory;
        this.healthStore = healthStore;
        this.tenantProperties = tenantProperties;
        this.endpointPolicy = endpointPolicy;
        this.monitorProperties = monitorProperties;
        this.probeExecutor = probeExecutor;
        this.timeoutScheduler = timeoutScheduler;
    }

    public CompletableFuture<ModelTestResult> probe(Long id, ModelProbeSource source) {
        AiModelConfig model = requireVisible(id);
        boolean persist = canPersist(model);
        CompletableFuture<ModelTestResult> resultFuture = new CompletableFuture<>();
        AtomicReference<Future<?>> taskReference = new AtomicReference<>();
        try {
            Future<?> submitted = probeExecutor.submit(() -> executeWithDeadline(model, resultFuture,
                taskReference));
            taskReference.set(submitted);
        } catch (TaskRejectedException e) {
            log.error("model health probe rejected, code={}, modelId={}",
                "MODEL-HEALTH-QUEUE-FULL", id);
            return CompletableFuture.completedFuture(failedResult(ModelHealthErrorCategory.UNKNOWN,
                "模型健康探测任务繁忙，请稍后重试", null));
        }
        return resultFuture.thenApply(result -> persist ? persist(model, result, source) : result);
    }

    /** 任务真正获得工作线程后才启动超时钟，排队时间不会误判为模型超时。 */
    private void executeWithDeadline(AiModelConfig model,
                                     CompletableFuture<ModelTestResult> resultFuture,
                                     AtomicReference<Future<?>> taskReference) {
        LocalDateTime probeStartedAt = truncateToMicros(LocalDateTime.now());
        long timeoutSeconds = Math.max(MIN_TIMEOUT_SECONDS,
            Math.min(monitorProperties.getProbeTimeoutSeconds(), MAX_TIMEOUT_SECONDS));
        ScheduledFuture<?> timeout = null;
        try {
            timeout = timeoutScheduler.schedule(() -> {
                if (resultFuture.complete(failedResult(ModelHealthErrorCategory.TIMEOUT,
                    "模型健康探测超时", null, probeStartedAt))) {
                    Future<?> running = taskReference.get();
                    if (running != null) {
                        running.cancel(true);
                    }
                }
            }, Instant.now().plusSeconds(timeoutSeconds));
            resultFuture.complete(executeProbe(model, probeStartedAt));
        } catch (Exception e) {
            log.error("model health probe failed, code={}, modelId={}",
                "MODEL-HEALTH-PROBE-FAILED", model.getId());
            ModelHealthErrorCategory category = classify(e.getMessage());
            resultFuture.complete(failedResult(category, "模型健康探测执行失败", null,
                probeStartedAt));
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }

    public ModelHealthSnapshotVO getSnapshot(Long id) {
        return healthStore.get(requireVisible(id));
    }

    public List<ModelHealthEventVO> listEvents(Long id, Integer limit) {
        return healthStore.listEvents(requireVisible(id), limit);
    }

    public Map<Long, ModelHealthSnapshotVO> findSnapshots(Collection<AiModelConfig> models) {
        return healthStore.findByModels(models);
    }

    private ModelTestResult executeProbe(AiModelConfig model, LocalDateTime probeStartedAt) {
        long started = System.nanoTime();
        String secretValue = null;
        try {
            // 在读取凭据之前先拦截危险端点；Prober 建连时还会用同一策略固定 DNS 解析结果。
            endpointPolicy.validateAndNormalizeBaseUrl(model.getBaseUrl());
            secretValue = secretRefService.resolvePlaintext(model);
            ModelTestResult raw = modelFactory.testConnectivity(
                model.getProtocolAdapter() == null ? model.getProvider() : model.getProtocolAdapter(),
                model.getBaseUrl(), secretValue, model.getModel());
            long latency = elapsedMillis(started);
            String message = sanitize(raw.message(), secretValue);
            if (raw.testStatus() == ConnectivityTestStatus.SUCCESS) {
                return new ModelTestResult(raw.testStatus(), probeStartedAt, message,
                    ModelHealthStatus.HEALTHY.name(), null, latency);
            }
            ModelHealthErrorCategory category = classify(message);
            return new ModelTestResult(raw.testStatus(), probeStartedAt, message,
                ModelHealthStatus.DEGRADED.name(), category.name(), latency);
        } catch (HttpTargetForbiddenException e) {
            return failedResult(ModelHealthErrorCategory.CONTRACT,
                "模型端点被出网安全策略拒绝", elapsedMillis(started), probeStartedAt);
        } catch (Exception e) {
            ModelHealthErrorCategory category = classify(e.getMessage());
            String message = category == ModelHealthErrorCategory.AUTH
                ? "模型凭据不可用" : "模型健康探测执行失败";
            return failedResult(category, sanitize(message, secretValue), elapsedMillis(started),
                probeStartedAt);
        }
    }

    private ModelTestResult persist(AiModelConfig model, ModelTestResult result, ModelProbeSource source) {
        ModelHealthStore.RecordResult recorded = healthStore.record(model, result, source);
        ModelHealthSnapshotVO snapshot = recorded.snapshot();
        if (recorded.applied()) {
            AiModelConfig compatibility = new AiModelConfig();
            compatibility.setId(model.getId());
            compatibility.setTestStatus(result.testStatus());
            compatibility.setTestTime(result.testTime());
            // 异步线程不继承请求 TenantContext；ID 已由可见性检查锁定，显式内部边界只更新兼容列。
            CrossTenantOperations.run(() -> modelConfigMapper.updateById(compatibility));
        }
        return new ModelTestResult(result.testStatus(), result.testTime(), result.message(),
            snapshot.healthStatus(), result.errorCategory(), result.latencyMs());
    }

    private AiModelConfig requireVisible(Long id) {
        AiModelConfig model = modelConfigAccess.findVisibleAnyStateById(id);
        if (model == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "模型配置不存在: " + id);
        }
        return model;
    }

    private boolean canPersist(AiModelConfig model) {
        if (!tenantProperties.isEnabled()) {
            return true;
        }
        String tenant = TenantContext.require();
        return TenantContext.sameTenant(tenant, model.getTenantId());
    }

    private ModelTestResult failedResult(ModelHealthErrorCategory category, String message, Long latency) {
        return failedResult(category, message, latency, truncateToMicros(LocalDateTime.now()));
    }

    private ModelTestResult failedResult(ModelHealthErrorCategory category, String message, Long latency,
                                         LocalDateTime probeStartedAt) {
        return new ModelTestResult(ConnectivityTestStatus.FAILED, probeStartedAt, message,
            ModelHealthStatus.DEGRADED.name(), category.name(), latency);
    }

    private LocalDateTime truncateToMicros(LocalDateTime time) {
        return time.withNano(time.getNano() / 1000 * 1000);
    }

    private ModelHealthErrorCategory classify(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (containsAny(normalized, "401", "403", "unauthorized", "api key", "credential", "鉴权", "认证", "凭据")) {
            return ModelHealthErrorCategory.AUTH;
        }
        if (containsAny(normalized, "429", "rate limit", "quota", "限流", "额度")) {
            return ModelHealthErrorCategory.RATE_LIMIT;
        }
        if (containsAny(normalized, "timeout", "timed out", "超时")) {
            return ModelHealthErrorCategory.TIMEOUT;
        }
        if (containsAny(normalized, "400", "404", "contract", "protocol", "schema", "协议", "格式")) {
            return ModelHealthErrorCategory.CONTRACT;
        }
        return ModelHealthErrorCategory.UNKNOWN;
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String sanitize(String message, String secretValue) {
        if (message == null) {
            return null;
        }
        String sanitized = secretValue == null || secretValue.isEmpty()
            ? message : message.replace(secretValue, "[redacted]");
        return sanitized.length() <= MAX_MESSAGE_LENGTH
            ? sanitized : sanitized.substring(0, MAX_MESSAGE_LENGTH);
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

}
