package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAgentReference;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAssetOptionVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthEventVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthOverrideRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelImpactVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretMetadataVO;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretRotationRequest;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import com.richard.fyoung.customerwork.safety.security.HttpTargetForbiddenException;
import com.richard.fyoung.customerwork.safety.security.ModelEndpointPolicy;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * AI 模型配置管理：CRUD + AppKey 加密存储 + 默认模型互斥设置 + 连通性测试。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    private static final String CREDENTIAL_PLACEHOLDER = "********";
    private static final String UNSAFE_ENDPOINT_PLACEHOLDER = "__MODEL_ENDPOINT_REDACTED__";
    private static final String DEFAULT_ENVIRONMENT = "PRODUCTION";

    private final AiModelConfigMapper modelConfigMapper;
    private final ModelReferenceAccess modelReferenceAccess;
    private final ModelAssetService modelAssetService;
    private final SecretRefService secretRefService;
    private final ModelHealthService modelHealthService;
    private final ModelImpactService modelImpactService;
    private final ModelCertificationService modelCertificationService;
    private final AgentInstanceCache agentInstanceCache;
    private final CustomerWorkConfigPublisher runtimeConfigPublisher;
    private final AdminTenantProperties tenantProperties;
    private final CrossTenantAuthority crossTenantAuthority;
    private final ModelEndpointPolicy endpointPolicy;

    public ModelConfigService(AiModelConfigMapper modelConfigMapper,
                               ModelReferenceAccess modelReferenceAccess,
                               ModelAssetService modelAssetService,
                               SecretRefService secretRefService,
                               ModelHealthService modelHealthService,
                               ModelImpactService modelImpactService,
                               ModelCertificationService modelCertificationService,
                               AgentInstanceCache agentInstanceCache,
                               CustomerWorkConfigPublisher runtimeConfigPublisher,
                               AdminTenantProperties tenantProperties,
                               CrossTenantAuthority crossTenantAuthority,
                               ModelEndpointPolicy endpointPolicy) {
        this.modelConfigMapper = modelConfigMapper;
        this.modelReferenceAccess = modelReferenceAccess;
        this.modelAssetService = modelAssetService;
        this.secretRefService = secretRefService;
        this.modelHealthService = modelHealthService;
        this.modelImpactService = modelImpactService;
        this.modelCertificationService = modelCertificationService;
        this.agentInstanceCache = agentInstanceCache;
        this.runtimeConfigPublisher = runtimeConfigPublisher;
        this.tenantProperties = tenantProperties;
        this.crossTenantAuthority = crossTenantAuthority;
        this.endpointPolicy = endpointPolicy;
    }

    // ---------------------------------------------------------------------
    // 两级可见性（docs/多租户架构设计.md §2.4）
    //
    // 本表承载模型凭据，为支持"default 共享基线 + 租户自建"两级共享而进了租户忽略清单
    // （TenantInterceptors.TENANT_IGNORED_TABLES），SQL 拦截器不会自动加租户条件。
    // 管理面补偿控制必须在本 Service 显式实现；运行时读取统一由 ModelConfigAccess 补偿。
    // 该表也刻意不在 DataScopeTables 白名单里，业务代码不得绕过这两个入口直接调用 Mapper。
    // ---------------------------------------------------------------------

    /**
     * 读可见范围：当前租户 + default 共享基线。
     *
     * <p>多租户关闭时（单租户部署）不加条件，与拦截器整体不生效的行为保持一致。</p>
     */
    private void applyReadScope(LambdaQueryWrapper<AiModelConfig> wrapper) {
        if (!tenantProperties.isEnabled()) {
            return;
        }
        String tenant = requireTenant();
        if (TenantContext.isDefaultTenant(tenant)) {
            wrapper.eq(AiModelConfig::getTenantId, TenantContext.DEFAULT);
            return;
        }
        wrapper.in(AiModelConfig::getTenantId, tenant, TenantContext.DEFAULT);
    }

    /**
     * 当前生效租户；多租户开启却拿不到上下文时 fail-closed。
     *
     * <p>与租户维度整体的取舍一致：宁可让请求失败，也不能让一次缺上下文的查询看到全量凭据。</p>
     */
    private String requireTenant() {
        String tenant = TenantSession.effectiveTenant();
        if (!StringUtils.hasText(tenant)) {
            log.error("model config access without tenant context, code={}", "MODEL-TENANT-MISSING");
            throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文，无法访问模型配置");
        }
        return tenant;
    }

    private boolean canManageSharedRecords() {
        return !tenantProperties.isEnabled() || crossTenantAuthority.hasCurrentUserAuthority();
    }

    public PageResult<ModelVO> page(PageQuery query) {
        LambdaQueryWrapper<AiModelConfig> wrapper = new LambdaQueryWrapper<>();
        applyReadScope(wrapper);
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(AiModelConfig::getModelName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(AiModelConfig::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), AiModelConfig::getCreateTime);

        IPage<AiModelConfig> page = modelConfigMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        Collection<AiModelConfig> models = page.getRecords();
        Map<Long, AiModelAsset> assets = modelAssetService.findByModels(models);
        Map<Long, SecretMetadataVO> credentials = secretRefService.metadataBatch(models);
        Map<Long, ModelHealthSnapshotVO> health = modelHealthService.findSnapshots(models);
        return PageResult.of(page.convert(model -> toVo(model, assets.get(model.getAssetId()),
            credentials.get(model.getSecretRefId()), health.get(model.getId()),
            modelCertificationService.current(model.getId()))));
    }

    public ModelVO get(Long id) {
        AiModelConfig model = requireModel(id);
        AiModelAsset asset = model.getAssetId() == null ? null
            : modelAssetService.requireVisible(model.getAssetId(), currentReadTenant(), tenantProperties.isEnabled());
        return toVo(model, asset, secretRefService.metadata(model.getSecretRefId(), model.getTenantId()),
            modelHealthService.findSnapshots(List.of(model)).get(id), modelCertificationService.current(id));
    }

    public List<ModelAssetOptionVO> assetOptions() {
        return modelAssetService.listVisible(currentReadTenant(), tenantProperties.isEnabled());
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(ModelSaveRequest request) {
        if (!StringUtils.hasText(request.apiKey())) {
            throw new BizException(ResultCode.PARAM_MISSING, "新建模型配置必须提供 apiKey");
        }
        String normalizedBaseUrl = validateEndpoint(request.baseUrl());
        String ownerTenant = ownerTenant();
        if (tenantProperties.isEnabled()
            && TenantContext.isDefaultTenant(ownerTenant)
            && !canManageSharedRecords()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有控制面角色可以新建 default 共享模型配置");
        }
        AiModelAsset asset = modelAssetService.resolveOrCreate(
            ownerTenant, ownerTenant, request, tenantProperties.isEnabled());
        SecretRefService.SecretWriteResult secret = secretRefService.createLocal(
            ownerTenant, request.modelName() + " 凭据", request.apiKey(), request.secretExpiresAt());
        AiModelConfig model = new AiModelConfig();
        fillFromRequest(model, request, normalizedBaseUrl);
        model.setTenantId(ownerTenant);
        model.setAssetId(asset.getId());
        model.setSecretRefId(secret.refId());
        // 旧运行时仍读取 api_key；与 SecretRef 当前版本写入同一份密文，直到运行时协议完成升级。
        model.setApiKey(secret.cipherText());
        model.setTestStatus(0);
        model.setEndpointRevision(1);
        // 新部署必须先完成认证，再显式激活；存量行由迁移的 certification_required=0 保持兼容。
        model.setCertificationRequired(1);
        model.setLifecycleStatus(ModelDeploymentLifecycle.DRAFT.name());
        model.setStatus(0);
        // 本表在租户忽略清单里，拦截器不会自动补租户列，必须显式落归属
        modelConfigMapper.insert(model);

        // 新部署先进入 DRAFT；只有真正可运行后才能切走当前线上默认模型。
        if (Boolean.TRUE.equals(request.isDefault()) && isActive(model)) {
            clearOtherDefaults(model.getId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ModelSaveRequest request) {
        AiModelConfig model = requireWritableModel(id);
        String normalizedBaseUrl = validateEndpoint(request.baseUrl());
        boolean baseUrlChanged = !endpointPolicy.sameEndpoint(model.getBaseUrl(), normalizedBaseUrl);
        boolean hasExistingCredential = model.getSecretRefId() != null || StringUtils.hasText(model.getApiKey());
        if (baseUrlChanged && hasExistingCredential && !StringUtils.hasText(request.apiKey())) {
            throw new BizException(ResultCode.PARAM_MISSING, "修改 baseUrl 必须同时重新提交模型凭据");
        }
        boolean wasActive = isActive(model);
        boolean endpointChanged = endpointChanged(model, request, normalizedBaseUrl);
        boolean assetChanged = model.getAssetId() == null
            || request.assetId() != null && !request.assetId().equals(model.getAssetId());
        boolean credentialChanged = StringUtils.hasText(request.apiKey());
        boolean configurationChanged = endpointChanged || assetChanged || credentialChanged;
        String requestedLifecycle = lifecycle(request.lifecycleStatus(), model.getLifecycleStatus());
        int requestedStatus = request.status() == null
            ? (model.getStatus() == null ? 0 : model.getStatus()) : request.status();
        if (wasActive && (configurationChanged
            || requestedStatus == 0
            || !ModelDeploymentLifecycle.ACTIVE.name().equals(requestedLifecycle))) {
            modelImpactService.requireAllowed(model, impactScope(model), "DISABLE");
        }
        if (request.assetId() != null && !request.assetId().equals(model.getAssetId())) {
            AiModelAsset asset = modelAssetService.resolveOrCreate(
                model.getTenantId(), currentReadTenant(), request, tenantProperties.isEnabled());
            model.setAssetId(asset.getId());
        } else if (model.getAssetId() == null) {
            model.setAssetId(modelAssetService.resolveOrCreate(
                model.getTenantId(), currentReadTenant(), request, tenantProperties.isEnabled()).getId());
        }
        fillFromRequest(model, request, normalizedBaseUrl);
        if (StringUtils.hasText(request.apiKey())) {
            SecretRefService.SecretWriteResult secret = secretRefService.rotateOrCreate(
                model, request.apiKey(), request.secretExpiresAt());
            model.setSecretRefId(secret.refId());
            model.setApiKey(secret.cipherText());
        }
        if (endpointChanged) {
            model.setEndpointRevision(model.getEndpointRevision() == null ? 1 : model.getEndpointRevision() + 1);
        }
        if (configurationChanged) {
            model.setCertificationRequired(1);
            model.setLifecycleStatus(ModelDeploymentLifecycle.DRAFT.name());
            model.setStatus(0);
        } else if (!ModelDeploymentLifecycle.ACTIVE.name().equals(model.getLifecycleStatus())) {
            model.setStatus(0);
        } else if (!wasActive && Integer.valueOf(1).equals(model.getStatus())) {
            modelCertificationService.requireCurrent(model);
        }
        modelConfigMapper.updateById(model);

        if (Boolean.TRUE.equals(request.isDefault()) && isActive(model)) {
            clearOtherDefaults(id);
        }
        propagateModelChange(model);
    }

    /**
     * 模型配置变更（baseUrl/apiKey/model 等）会让引用它的智能体运行时用上旧配置构建的实例，需一并失效。
     * 引用关系覆盖主模型、备用模型，以及计划中、运行中或尚未确认撤流的在线实验双臂。
     */
    private void propagateModelChange(AiModelConfig model) {
        List<ModelAgentReference> references = modelReferenceAccess.findReferences(model);
        for (ModelAgentReference reference : references) {
            Runnable propagate = () -> {
                agentInstanceCache.invalidate(reference.getAgentCode());
                // 可靠任务与模型修改同事务写入；Publisher 会把兼容的 afterCommit 路径也绑定到当前租户。
                runtimeConfigPublisher.publishForAgentId(reference.getAgentId());
            };
            if (tenantProperties.isEnabled()) {
                TenantContext.runWith(reference.getTenantId(), propagate);
            } else {
                propagate.run();
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiModelConfig model = requireWritableModel(id);
        modelImpactService.requireAllowed(model, impactScope(model), "DELETE");
        modelConfigMapper.deleteById(id);
    }

    public ModelImpactVO impact(Long id, String action) {
        AiModelConfig model = requireModel(id);
        return modelImpactService.query(model, impactScope(model), normalizeAction(action));
    }

    @Transactional(rollbackFor = Exception.class)
    public SecretMetadataVO rotateCredential(Long id, SecretRotationRequest request) {
        AiModelConfig model = requireWritableModel(id);
        // 不以部署表的 status 作为唯一依据：撤流未确认时，运行时仍可能持有旧凭据。
        modelImpactService.requireAllowed(model, impactScope(model), "ROTATE");
        SecretRefService.SecretWriteResult secret = secretRefService.rotateOrCreate(
            model, request.secretValue(), request.expiresAt());
        AiModelConfig update = new AiModelConfig();
        update.setId(id);
        update.setSecretRefId(secret.refId());
        update.setApiKey(secret.cipherText());
        update.setTestStatus(0);
        update.setCertificationRequired(1);
        update.setLifecycleStatus(ModelDeploymentLifecycle.DRAFT.name());
        update.setStatus(0);
        modelConfigMapper.updateById(update);
        model.setSecretRefId(secret.refId());
        model.setApiKey(secret.cipherText());
        model.setCertificationRequired(1);
        model.setLifecycleStatus(ModelDeploymentLifecycle.DRAFT.name());
        model.setStatus(0);
        propagateModelChange(model);
        return secret.metadata();
    }

    /**
     * 连通性测试：解密 apiKey 后派发到独立线程池执行，硬性超时兜底，不占用调用方（Tomcat）线程。
     * Controller 侧以 {@link CompletableFuture} 异步返回，Spring MVC 借此在等待期间释放请求线程。
     */
    public CompletableFuture<ModelTestResult> testConnectivity(Long id) {
        return modelHealthService.probe(id, ModelProbeSource.MANUAL);
    }

    public ModelHealthSnapshotVO health(Long id) {
        return modelHealthService.getSnapshot(id);
    }

    public List<ModelHealthEventVO> healthEvents(Long id, Integer limit) {
        return modelHealthService.listEvents(id, limit);
    }

    public ModelHealthSnapshotVO overrideHealth(Long id, ModelHealthOverrideRequest request,
                                                Long operatorId, String operatorName) {
        return modelHealthService.override(id, request, operatorId, operatorName);
    }

    public ModelCertificationVO certification(Long id) {
        return modelCertificationService.current(id);
    }

    public List<ModelCertificationVO> certificationHistory(Long id) {
        return modelCertificationService.history(id);
    }

    public ModelCertificationVO certify(Long id, ModelCertificationRequest request) {
        return modelCertificationService.certify(id, request);
    }

    /**
     * 已认证候选激活为默认模型时，清空<b>本租户内</b>其余行的 is_default（互斥式切换）。
     *
     * <p>DRAFT 候选可保留默认意图，但不能提前影响线上优先级。租户条件不可省：没有它，
     * 任一租户设默认模型都会把所有租户的 is_default 一并清零，
     * 别人的智能体下一次构建模型时就取不到默认模型了。</p>
     */
    private void clearOtherDefaults(Long keepId) {
        LambdaUpdateWrapper<AiModelConfig> wrapper = new LambdaUpdateWrapper<AiModelConfig>()
            .ne(AiModelConfig::getId, keepId)
            .eq(AiModelConfig::getIsDefault, 1)
            .set(AiModelConfig::getIsDefault, 0);
        if (tenantProperties.isEnabled()) {
            wrapper.eq(AiModelConfig::getTenantId, requireTenant());
        }
        modelConfigMapper.update(null, wrapper);
    }

    private void fillFromRequest(AiModelConfig model, ModelSaveRequest request, String normalizedBaseUrl) {
        model.setModelName(request.modelName());
        if (!StringUtils.hasText(model.getDeploymentCode())) {
            model.setDeploymentCode(StringUtils.hasText(request.deploymentCode())
                ? request.deploymentCode().trim()
                : "deployment-" + UUID.randomUUID().toString().replace("-", ""));
        } else if (StringUtils.hasText(request.deploymentCode())) {
            model.setDeploymentCode(request.deploymentCode().trim());
        }
        String provider = StringUtils.hasText(request.provider()) ? request.provider() : ModelProviders.OPENAI;
        model.setProvider(provider);
        model.setProtocolAdapter(provider);
        model.setBaseUrl(normalizedBaseUrl);
        model.setRegion(request.region());
        if (StringUtils.hasText(request.environment())) {
            model.setEnvironment(request.environment().trim().toUpperCase());
        } else if (!StringUtils.hasText(model.getEnvironment())) {
            model.setEnvironment(DEFAULT_ENVIRONMENT);
        }
        if (StringUtils.hasText(request.lifecycleStatus())) {
            model.setLifecycleStatus(request.lifecycleStatus().trim().toUpperCase());
        } else if (!StringUtils.hasText(model.getLifecycleStatus())) {
            model.setLifecycleStatus(ModelDeploymentLifecycle.DRAFT.name());
        }
        model.setModel(request.model());
        model.setIsDefault(Boolean.TRUE.equals(request.isDefault()) ? 1 : 0);
        if (request.status() != null || model.getStatus() == null) {
            model.setStatus(request.status() == null ? 1 : request.status());
        }
    }

    private ModelVO toVo(AiModelConfig model, AiModelAsset asset, SecretMetadataVO credential,
                         ModelHealthSnapshotVO health, ModelCertificationVO certification) {
        ModelVO vo = new ModelVO();
        vo.setId(model.getId());
        vo.setAssetId(model.getAssetId());
        if (asset != null) {
            vo.setAssetCode(asset.getAssetCode());
            vo.setAssetName(asset.getAssetName());
            vo.setVendor(asset.getVendor());
            vo.setFamily(asset.getFamily());
            vo.setAssetVersion(asset.getAssetVersion());
            vo.setModality(asset.getModality());
            vo.setContextWindow(asset.getContextWindow());
            vo.setMaxOutputTokens(asset.getMaxOutputTokens());
            vo.setSupportsStream(Integer.valueOf(1).equals(asset.getSupportsStream()));
            vo.setSupportsTool(Integer.valueOf(1).equals(asset.getSupportsTool()));
            vo.setSupportsJsonSchema(Integer.valueOf(1).equals(asset.getSupportsJsonSchema()));
            vo.setSupportsMultimodal(Integer.valueOf(1).equals(asset.getSupportsMultimodal()));
            vo.setAssetLifecycleStatus(asset.getLifecycleStatus());
        }
        vo.setModelName(model.getModelName());
        vo.setDeploymentCode(model.getDeploymentCode());
        vo.setProvider(model.getProvider());
        vo.setProtocolAdapter(model.getProtocolAdapter());
        // 不再为脱敏而解密；固定占位符不会泄漏真实凭据的任意字符。
        vo.setApiKeyMasked(model.getSecretRefId() != null || StringUtils.hasText(model.getApiKey())
            ? CREDENTIAL_PLACEHOLDER : null);
        vo.setCredential(credential);
        vo.setBaseUrl(redactUnsafeEndpoint(model.getBaseUrl()));
        vo.setRegion(model.getRegion());
        vo.setEnvironment(model.getEnvironment());
        vo.setEndpointRevision(model.getEndpointRevision());
        vo.setLifecycleStatus(model.getLifecycleStatus());
        vo.setCertificationRequired(Integer.valueOf(1).equals(model.getCertificationRequired()));
        vo.setCertification(certification);
        vo.setModel(model.getModel());
        vo.setIsDefault(model.getIsDefault() != null && model.getIsDefault() == 1);
        vo.setStatus(model.getStatus());
        vo.setTestStatus(model.getTestStatus());
        vo.setTestTime(model.getTestTime());
        vo.setHealth(health);
        vo.setCreateTime(model.getCreateTime());
        return vo;
    }

    private String redactUnsafeEndpoint(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return baseUrl;
        }
        try {
            URI uri = new URI(baseUrl);
            if (uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return baseUrl;
            }
        } catch (Exception ignored) {
            // 历史非法端点可能把凭据藏在畸形 URL 中，展示侧必须整体隐藏并要求重新填写。
        }
        return UNSAFE_ENDPOINT_PLACEHOLDER;
    }

    private String ownerTenant() {
        return tenantProperties.isEnabled() ? requireTenant() : TenantContext.DEFAULT;
    }

    private String currentReadTenant() {
        return tenantProperties.isEnabled() ? requireTenant() : TenantContext.DEFAULT;
    }

    private String impactScope(AiModelConfig model) {
        String currentTenant = tenantProperties.isEnabled() ? requireTenant() : null;
        if (tenantProperties.isEnabled()
            && TenantContext.isDefaultTenant(model.getTenantId())
            && TenantContext.isDefaultTenant(currentTenant)
            && canManageSharedRecords()) {
            return null;
        }
        return currentTenant;
    }

    private String normalizeAction(String action) {
        if ("DISABLE".equalsIgnoreCase(action)) {
            return "DISABLE";
        }
        return "ROTATE".equalsIgnoreCase(action) ? "ROTATE" : "DELETE";
    }

    private boolean endpointChanged(AiModelConfig model, ModelSaveRequest request, String normalizedBaseUrl) {
        String provider = StringUtils.hasText(request.provider()) ? request.provider() : ModelProviders.OPENAI;
        String environment = StringUtils.hasText(request.environment())
            ? request.environment().trim().toUpperCase() : model.getEnvironment();
        return !java.util.Objects.equals(model.getProvider(), provider)
            || !endpointPolicy.sameEndpoint(model.getBaseUrl(), normalizedBaseUrl)
            || !java.util.Objects.equals(model.getModel(), request.model())
            || !java.util.Objects.equals(model.getRegion(), request.region())
            || !java.util.Objects.equals(model.getEnvironment(), environment);
    }

    /** 保存前与真实探测共用同一端点策略，安全拦截统一转成稳定业务错误码。 */
    private String validateEndpoint(String baseUrl) {
        try {
            return endpointPolicy.validateAndNormalizeBaseUrl(baseUrl);
        } catch (HttpTargetForbiddenException e) {
            throw new BizException(ResultCode.MODEL_ENDPOINT_FORBIDDEN, e.getMessage());
        }
    }

    private String lifecycle(String requested, String current) {
        String value = StringUtils.hasText(requested) ? requested.trim().toUpperCase() : current;
        if (!StringUtils.hasText(value)) {
            return ModelDeploymentLifecycle.DRAFT.name();
        }
        try {
            return ModelDeploymentLifecycle.valueOf(value).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "lifecycleStatus 仅支持 DRAFT/ACTIVE/DEPRECATED/RETIRED");
        }
    }

    private boolean isActive(AiModelConfig model) {
        return Integer.valueOf(1).equals(model.getStatus())
            && ModelDeploymentLifecycle.ACTIVE.name().equals(model.getLifecycleStatus());
    }

    /**
     * 读取一条模型配置：可见范围为当前租户 + default 共享基线。
     *
     * <p>不可见与不存在统一报 404，不泄漏"这个 id 确实存在但属于别人"。</p>
     */
    private AiModelConfig requireModel(Long id) {
        AiModelConfig model = modelConfigMapper.selectById(id);
        if (model == null || !visible(model)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "模型配置不存在: " + id);
        }
        return model;
    }

    /**
     * 取一条<b>可写</b>的模型配置：在可读基础上再要求归属当前视角租户。
     *
     * <p>普通用户改不动 default 共享记录——否则可以把共享模型的 baseUrl 指向自己的服务器，
     * 再触发一次连通性测试间接滥用共享 apiKey。控制面用户也必须先回到 default 视角才能修改共享记录，
     * 避免在目标租户视角误改全局基线。</p>
     */
    private AiModelConfig requireWritableModel(Long id) {
        AiModelConfig model = requireModel(id);
        if (!tenantProperties.isEnabled()) {
            return model;
        }
        String tenant = requireTenant();
        if (!TenantContext.sameTenant(tenant, model.getTenantId())) {
            throw new BizException(ResultCode.FORBIDDEN, "只能修改当前租户视角的模型配置: " + id);
        }
        if (TenantContext.isDefaultTenant(model.getTenantId()) && !canManageSharedRecords()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有控制面角色可以修改 default 共享模型配置: " + id);
        }
        return model;
    }

    private boolean visible(AiModelConfig model) {
        if (!tenantProperties.isEnabled()) {
            return true;
        }
        String tenant = requireTenant();
        return TenantContext.sameTenant(tenant, model.getTenantId())
            || TenantContext.isDefaultTenant(model.getTenantId());
    }
}
