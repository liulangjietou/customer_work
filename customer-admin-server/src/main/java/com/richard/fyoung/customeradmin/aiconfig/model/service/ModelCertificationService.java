package com.richard.fyoung.customeradmin.aiconfig.model.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelCertificationCheckStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelCertificationStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationCheckVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertificationRun;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationRunMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.ModelCertificationProbe;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretMetadataVO;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.billing.entity.AiModelPrice;
import com.richard.fyoung.customeradmin.billing.service.ModelPriceService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 模型上线认证：真实能力探测、成本门槛、不可变运行记录和 ACTIVE 门禁。 */
@Service
public class ModelCertificationService {

    private static final int HISTORY_LIMIT = 20;
    private static final int MAX_MESSAGE_LENGTH = 500;

    /** 缺价时的摘要要指得出下一步落点：单价按 (provider, model) 精确匹配，改名或换厂商都要重新维护。 */
    private static final String PRICE_MISSING = "未配置生效模型单价，请在计费管理维护该模型单价后重试";

    private final ModelConfigAccess modelConfigAccess;
    private final ModelAssetService modelAssetService;
    private final SecretRefService secretRefService;
    private final ModelCertificationProbe certificationProbe;
    private final ModelPriceService modelPriceService;
    private final ModelCertificationStore certificationStore;
    private final AiModelCertificationMapper certificationMapper;
    private final AiModelCertificationRunMapper runMapper;
    private final AdminTenantProperties tenantProperties;
    private final CrossTenantAuthority crossTenantAuthority;
    private final ObjectMapper objectMapper;

    public ModelCertificationService(ModelConfigAccess modelConfigAccess,
                                     ModelAssetService modelAssetService,
                                     SecretRefService secretRefService,
                                     ModelCertificationProbe certificationProbe,
                                     ModelPriceService modelPriceService,
                                     ModelCertificationStore certificationStore,
                                     AiModelCertificationMapper certificationMapper,
                                     AiModelCertificationRunMapper runMapper,
                                     AdminTenantProperties tenantProperties,
                                     CrossTenantAuthority crossTenantAuthority,
                                     ObjectMapper objectMapper) {
        this.modelConfigAccess = modelConfigAccess;
        this.modelAssetService = modelAssetService;
        this.secretRefService = secretRefService;
        this.certificationProbe = certificationProbe;
        this.modelPriceService = modelPriceService;
        this.certificationStore = certificationStore;
        this.certificationMapper = certificationMapper;
        this.runMapper = runMapper;
        this.tenantProperties = tenantProperties;
        this.crossTenantAuthority = crossTenantAuthority;
        this.objectMapper = objectMapper;
    }

    public ModelCertificationVO certify(Long modelId, ModelCertificationRequest request) {
        AiModelConfig model = requireWritable(modelId);
        Long attemptId = IdWorker.getId();
        LocalDateTime started = LocalDateTime.now();
        AiModelAsset asset = modelAssetService.requireVisible(model.getAssetId(), currentTenant(),
            tenantProperties.isEnabled());
        SecretMetadataVO secret = secretRefService.metadata(model.getSecretRefId(), model.getTenantId());
        String secretValue = secretRefService.resolvePlaintext(model);

        ModelCertificationProbe.ProbeResult evidence = certificationProbe.probe(model, asset, secretValue, request);
        List<ModelCertificationCheckVO> checks = new ArrayList<>(evidence.checks());
        AiModelPrice price = modelPriceService.findEffectivePrice(model.getProvider(), model.getModel(), started);
        appendCostChecks(checks, price, request);

        int passed = (int) checks.stream()
            .filter(check -> ModelCertificationCheckStatus.PASSED.name().equals(check.status())).count();
        int failed = (int) checks.stream()
            .filter(check -> ModelCertificationCheckStatus.FAILED.name().equals(check.status())).count();
        ModelCertificationCheckVO firstFailure = checks.stream()
            .filter(check -> ModelCertificationCheckStatus.FAILED.name().equals(check.status()))
            .findFirst().orElse(null);
        LocalDateTime completed = LocalDateTime.now();
        String status = failed == 0 ? ModelCertificationStatus.PASSED.name()
            : ModelCertificationStatus.FAILED.name();

        AiModelCertificationRun run = new AiModelCertificationRun();
        run.setId(attemptId);
        run.setTenantId(model.getTenantId());
        run.setModelConfigId(model.getId());
        run.setStatus(status);
        run.setEndpointRevision(model.getEndpointRevision() == null ? 1 : model.getEndpointRevision());
        run.setSecretVersion(secret == null ? null : secret.currentVersion());
        run.setRequiredContextTokens(request.requiredContextTokens());
        run.setMaxLatencyMs(request.maxLatencyMs());
        run.setMaxInputPrice(request.maxInputPrice());
        run.setMaxOutputPrice(request.maxOutputPrice());
        run.setLatencyP95Ms(evidence.latencyP95Ms());
        run.setVerifiedContextTokens(evidence.verifiedContextTokens());
        run.setInputPrice(price == null ? null : price.getInputPrice());
        run.setOutputPrice(price == null ? null : price.getOutputPrice());
        run.setCurrency(price == null ? null : price.getCurrency());
        run.setChecksJson(writeChecks(checks));
        run.setFailureCode(firstFailure == null ? null : firstFailure.code());
        run.setFailureMessage(firstFailure == null ? null : truncate(firstFailure.message()));
        run.setTriggeredBy(currentUserId());
        run.setStartedAt(started);
        run.setCompletedAt(completed);
        LocalDateTime requestedValidUntil = completed.plusDays(request.validDays());
        run.setValidUntil(failed == 0
            ? earlier(requestedValidUntil, secret == null ? null : secret.expiresAt()) : null);
        ModelCertificationStore.RecordResult recorded = certificationStore.record(run, passed, failed,
            model.getSecretRefId());
        AiModelConfig currentModel = requireVisible(modelId);
        SecretMetadataVO currentSecret = secretRefService.metadata(currentModel.getSecretRefId(),
            currentModel.getTenantId());
        ModelCertificationVO result = toVo(currentModel, run, checks, currentSecret);
        if (!recorded.promoted()) {
            result.setEffectiveStatus(ModelCertificationStatus.STALE.name());
            result.setStaleReason("认证期间配置、凭据或更新认证运行已发生变化，本次结果未晋级");
        }
        return result;
    }

    public ModelCertificationVO current(Long modelId) {
        AiModelConfig model = requireVisible(modelId);
        AiModelCertification snapshot = findSnapshot(model);
        if (snapshot == null) {
            return empty(model);
        }
        AiModelCertificationRun run = findRun(snapshot.getCurrentRunId(), model.getTenantId());
        SecretMetadataVO secret = secretRefService.metadata(model.getSecretRefId(), model.getTenantId());
        return toVo(model, run, readChecks(run == null ? null : run.getChecksJson()), secret);
    }

    public List<ModelCertificationVO> history(Long modelId) {
        AiModelConfig model = requireVisible(modelId);
        List<AiModelCertificationRun> runs = CrossTenantOperations.execute(() -> runMapper.selectList(
            new QueryWrapper<AiModelCertificationRun>()
                .eq("tenant_id", model.getTenantId())
                .eq("model_config_id", model.getId())
                .orderByDesc("completed_at")
                .last("LIMIT " + HISTORY_LIMIT)));
        if (runs.isEmpty()) {
            return List.of();
        }
        SecretMetadataVO secret = secretRefService.metadata(model.getSecretRefId(), model.getTenantId());
        return runs.stream().map(run -> toVo(model, run, readChecks(run.getChecksJson()), secret)).toList();
    }

    /** ACTIVE 与路由发布共用的权威认证门禁。 */
    public void requireCurrent(AiModelConfig model) {
        if (!Integer.valueOf(1).equals(model.getCertificationRequired())) {
            return;
        }
        ModelCertificationVO certification = current(model.getId());
        if (!ModelCertificationStatus.PASSED.name().equals(certification.getEffectiveStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "模型部署未通过当前配置的有效认证: " + certification.getEffectiveStatus());
        }
    }

    /** 路由策略发布不接受存量豁免：被策略引用的每个部署都必须有真实、当前、未过期的 PASSED 认证。 */
    public void requirePassedCurrent(AiModelConfig model) {
        AiModelCertification snapshot = findSnapshot(model);
        AiModelCertificationRun run = snapshot == null ? null
            : findRun(snapshot.getCurrentRunId(), model.getTenantId());
        SecretMetadataVO secret = secretRefService.metadata(model.getSecretRefId(), model.getTenantId());
        EffectiveStatus effective = effective(model, run, secret, true);
        if (!ModelCertificationStatus.PASSED.name().equals(effective.status())) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "路由引用的模型部署未通过有效认证: " + model.getId() + " (" + effective.status() + ")");
        }
    }

    private void appendCostChecks(List<ModelCertificationCheckVO> checks, AiModelPrice price,
                                  ModelCertificationRequest request) {
        if (price == null) {
            checks.add(failedCost("INPUT_COST", "输入成本", null, request.maxInputPrice(), PRICE_MISSING));
            checks.add(failedCost("OUTPUT_COST", "输出成本", null, request.maxOutputPrice(), PRICE_MISSING));
            return;
        }
        checks.add(costCheck("INPUT_COST", "输入成本", price.getInputPrice(), request.maxInputPrice()));
        checks.add(costCheck("OUTPUT_COST", "输出成本", price.getOutputPrice(), request.maxOutputPrice()));
    }

    private ModelCertificationCheckVO costCheck(String code, String name,
                                                BigDecimal actual, BigDecimal maximum) {
        boolean passed = actual != null && actual.compareTo(maximum) <= 0;
        return new ModelCertificationCheckVO(code, name, passed
            ? ModelCertificationCheckStatus.PASSED.name() : ModelCertificationCheckStatus.FAILED.name(),
            actual == null ? null : actual.toPlainString(), "≤ " + maximum.toPlainString(),
            passed ? "生效单价满足成本门槛" : "生效单价超过成本门槛");
    }

    private ModelCertificationCheckVO failedCost(String code, String name,
                                                 BigDecimal actual, BigDecimal maximum, String message) {
        return new ModelCertificationCheckVO(code, name, ModelCertificationCheckStatus.FAILED.name(),
            actual == null ? null : actual.toPlainString(), "≤ " + maximum.toPlainString(), message);
    }

    private ModelCertificationVO empty(AiModelConfig model) {
        ModelCertificationVO vo = new ModelCertificationVO();
        vo.setStatus(ModelCertificationStatus.UNKNOWN.name());
        vo.setEffectiveStatus(Integer.valueOf(1).equals(model.getCertificationRequired())
            ? ModelCertificationStatus.UNKNOWN.name() : ModelCertificationStatus.NOT_REQUIRED.name());
        vo.setStaleReason(Integer.valueOf(1).equals(model.getCertificationRequired())
            ? "尚未执行上线认证" : "存量部署保持兼容；配置变更后将自动纳入认证门禁");
        vo.setChecks(List.of());
        return vo;
    }

    private ModelCertificationVO toVo(AiModelConfig model,
                                      AiModelCertificationRun run,
                                      List<ModelCertificationCheckVO> checks,
                                      SecretMetadataVO secret) {
        if (run == null) {
            return empty(model);
        }
        ModelCertificationVO vo = new ModelCertificationVO();
        vo.setRunId(run.getId());
        vo.setStatus(run.getStatus());
        EffectiveStatus effective = effective(model, run, secret,
            Integer.valueOf(1).equals(model.getCertificationRequired()));
        vo.setEffectiveStatus(effective.status());
        vo.setStaleReason(effective.reason());
        vo.setCertifiedEndpointRevision(run.getEndpointRevision());
        vo.setCertifiedSecretVersion(run.getSecretVersion());
        vo.setValidUntil(run.getValidUntil());
        vo.setCompletedAt(run.getCompletedAt());
        vo.setPassedChecks((int) checks.stream()
            .filter(check -> ModelCertificationCheckStatus.PASSED.name().equals(check.status())).count());
        vo.setFailedChecks((int) checks.stream()
            .filter(check -> ModelCertificationCheckStatus.FAILED.name().equals(check.status())).count());
        vo.setLatencyP95Ms(run.getLatencyP95Ms());
        vo.setVerifiedContextTokens(run.getVerifiedContextTokens());
        vo.setInputPrice(run.getInputPrice());
        vo.setOutputPrice(run.getOutputPrice());
        vo.setCurrency(run.getCurrency());
        vo.setFailureCode(run.getFailureCode());
        vo.setFailureMessage(run.getFailureMessage());
        vo.setChecks(checks);
        return vo;
    }

    private EffectiveStatus effective(AiModelConfig model,
                                      AiModelCertificationRun run,
                                      SecretMetadataVO secret,
                                      boolean enforce) {
        if (!enforce) {
            return new EffectiveStatus(ModelCertificationStatus.NOT_REQUIRED.name(),
                "存量部署保持兼容；配置变更后将自动纳入认证门禁");
        }
        if (run == null) {
            return new EffectiveStatus(ModelCertificationStatus.UNKNOWN.name(), "尚未执行上线认证");
        }
        if (!ModelCertificationStatus.PASSED.name().equals(run.getStatus())) {
            return new EffectiveStatus(ModelCertificationStatus.FAILED.name(), run.getFailureMessage());
        }
        if (!Objects.equals(model.getEndpointRevision(), run.getEndpointRevision())) {
            return new EffectiveStatus(ModelCertificationStatus.STALE.name(), "端点配置已变化，需要重新认证");
        }
        Integer currentSecretVersion = secret == null ? null : secret.currentVersion();
        if (!Objects.equals(currentSecretVersion, run.getSecretVersion())) {
            return new EffectiveStatus(ModelCertificationStatus.STALE.name(), "凭据版本已变化，需要重新认证");
        }
        if (secret != null && !"ACTIVE".equals(secret.status())) {
            return new EffectiveStatus(ModelCertificationStatus.STALE.name(), "当前凭据不可用，需要轮换后重新认证");
        }
        if (run.getValidUntil() == null || !run.getValidUntil().isAfter(LocalDateTime.now())) {
            return new EffectiveStatus(ModelCertificationStatus.EXPIRED.name(), "认证已过期，需要复检");
        }
        return new EffectiveStatus(ModelCertificationStatus.PASSED.name(), null);
    }

    private AiModelConfig requireVisible(Long modelId) {
        AiModelConfig model = modelConfigAccess.findVisibleAnyStateById(modelId);
        if (model == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "模型配置不存在: " + modelId);
        }
        return model;
    }

    private AiModelConfig requireWritable(Long modelId) {
        AiModelConfig model = requireVisible(modelId);
        if (!tenantProperties.isEnabled()) {
            return model;
        }
        String tenant = currentTenant();
        if (!TenantContext.sameTenant(tenant, model.getTenantId())) {
            throw new BizException(ResultCode.FORBIDDEN, "只能认证当前租户视角的模型部署: " + modelId);
        }
        if (TenantContext.isDefaultTenant(model.getTenantId())
            && !crossTenantAuthority.hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有控制面角色可以认证 default 共享模型部署");
        }
        return model;
    }

    private AiModelCertification findSnapshot(AiModelConfig model) {
        return CrossTenantOperations.execute(() -> certificationMapper.selectOne(
            new QueryWrapper<AiModelCertification>()
                .eq("model_config_id", model.getId())
                .eq("tenant_id", model.getTenantId())));
    }

    private AiModelCertificationRun findRun(Long runId, String tenantId) {
        if (runId == null) {
            return null;
        }
        return CrossTenantOperations.execute(() -> runMapper.selectOne(
            new QueryWrapper<AiModelCertificationRun>()
                .eq("id", runId)
                .eq("tenant_id", tenantId)));
    }

    private String currentTenant() {
        if (!tenantProperties.isEnabled()) {
            return TenantContext.DEFAULT;
        }
        String tenant = TenantSession.effectiveTenant();
        if (!StringUtils.hasText(tenant)) {
            throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文，无法访问模型认证");
        }
        return tenant;
    }

    private String writeChecks(List<ModelCertificationCheckVO> checks) {
        try {
            return objectMapper.writeValueAsString(checks);
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "认证证据序列化失败");
        }
    }

    private List<ModelCertificationCheckVO> readChecks(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ModelCertificationCheckVO>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (SaTokenException e) {
            return null;
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH);
    }

    private LocalDateTime earlier(LocalDateTime left, LocalDateTime right) {
        return right != null && right.isBefore(left) ? right : left;
    }

    private record EffectiveStatus(String status, String reason) {
    }
}
