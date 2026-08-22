package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelAssetLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAssetOptionVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelAssetMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.core.constant.ModelProviders;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 模型目录资产管理；部署端点仍由 ModelConfigService 负责。 */
@Service
public class ModelAssetService {

    private static final String DEFAULT_MODALITY = "TEXT";

    private final AiModelAssetMapper assetMapper;

    public ModelAssetService(AiModelAssetMapper assetMapper) {
        this.assetMapper = assetMapper;
    }

    public AiModelAsset resolveOrCreate(String ownerTenant, String visibleTenant,
                                        ModelSaveRequest request, boolean tenantEnabled) {
        if (request.assetId() != null) {
            return requireVisible(request.assetId(), visibleTenant, tenantEnabled);
        }
        AiModelAsset asset = new AiModelAsset();
        asset.setTenantId(ownerTenant);
        asset.setAssetCode(StringUtils.hasText(request.assetCode())
            ? request.assetCode().trim()
            : "asset-" + UUID.randomUUID().toString().replace("-", ""));
        asset.setAssetName(StringUtils.hasText(request.assetName())
            ? request.assetName().trim() : request.modelName());
        asset.setVendor(StringUtils.hasText(request.vendor())
            ? request.vendor().trim().toUpperCase() : vendorOf(request.provider()));
        asset.setModelKey(request.model());
        asset.setFamily(request.family());
        asset.setAssetVersion(request.assetVersion());
        asset.setModality(StringUtils.hasText(request.modality())
            ? request.modality().trim().toUpperCase() : DEFAULT_MODALITY);
        asset.setContextWindow(request.contextWindow());
        asset.setMaxOutputTokens(request.maxOutputTokens());
        asset.setSupportsStream(flag(request.supportsStream(), true));
        asset.setSupportsTool(flag(request.supportsTool(), true));
        asset.setSupportsJsonSchema(flag(request.supportsJsonSchema(), false));
        asset.setSupportsMultimodal(flag(request.supportsMultimodal(), false));
        asset.setLifecycleStatus(ModelAssetLifecycle.ACTIVE.name());
        ensureCodeAvailable(ownerTenant, asset.getAssetCode());
        CrossTenantOperations.run(() -> assetMapper.insert(asset));
        return asset;
    }

    public List<ModelAssetOptionVO> listVisible(String currentTenant, boolean tenantEnabled) {
        QueryWrapper<AiModelAsset> wrapper = new QueryWrapper<AiModelAsset>()
            .in("lifecycle_status", ModelAssetLifecycle.ACTIVE.name(), ModelAssetLifecycle.DEPRECATED.name())
            .orderByAsc("asset_name");
        if (tenantEnabled) {
            wrapper.in("tenant_id", visibleTenants(currentTenant));
        }
        List<AiModelAsset> assets = CrossTenantOperations.execute(() -> assetMapper.selectList(wrapper));
        return assets.stream().map(this::toOption).toList();
    }

    public Map<Long, AiModelAsset> findByModels(Collection<AiModelConfig> models) {
        if (CollectionUtils.isEmpty(models)) {
            return Collections.emptyMap();
        }
        List<Long> ids = models.stream().map(AiModelConfig::getAssetId)
            .filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> tenants = models.stream().map(AiModelConfig::getTenantId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        // 租户部署允许引用 default 共享资产；批量回显不能只按部署归属租户过滤。
        tenants.add(TenantContext.DEFAULT);
        List<AiModelAsset> assets = CrossTenantOperations.execute(() -> assetMapper.selectList(
            new QueryWrapper<AiModelAsset>().in("id", ids).in("tenant_id", tenants)));
        return assets.stream().collect(Collectors.toMap(AiModelAsset::getId, asset -> asset,
            (left, right) -> left));
    }

    public AiModelAsset requireVisible(Long id, String currentTenant, boolean tenantEnabled) {
        QueryWrapper<AiModelAsset> wrapper = new QueryWrapper<AiModelAsset>().eq("id", id);
        if (tenantEnabled) {
            wrapper.in("tenant_id", visibleTenants(currentTenant));
        }
        AiModelAsset asset = CrossTenantOperations.execute(() -> assetMapper.selectOne(wrapper));
        if (asset == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "模型资产不存在: " + id);
        }
        return asset;
    }

    private void ensureCodeAvailable(String tenantId, String assetCode) {
        Long count = CrossTenantOperations.execute(() -> assetMapper.selectCount(
            new QueryWrapper<AiModelAsset>().eq("tenant_id", tenantId).eq("asset_code", assetCode)));
        if (count != null && count > 0) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "模型资产编码已存在: " + assetCode);
        }
    }

    private List<String> visibleTenants(String currentTenant) {
        if (TenantContext.isDefaultTenant(currentTenant)) {
            return List.of(TenantContext.DEFAULT);
        }
        return List.of(currentTenant, TenantContext.DEFAULT);
    }

    private int flag(Boolean value, boolean defaultValue) {
        return Boolean.TRUE.equals(value == null ? defaultValue : value) ? 1 : 0;
    }

    private String vendorOf(String provider) {
        if (ModelProviders.DASHSCOPE.equals(provider)) {
            return "ALIBABA";
        }
        if (ModelProviders.ANTHROPIC.equals(provider)) {
            return "ANTHROPIC";
        }
        if (ModelProviders.GEMINI.equals(provider)) {
            return "GOOGLE";
        }
        return "CUSTOM";
    }

    private ModelAssetOptionVO toOption(AiModelAsset asset) {
        return new ModelAssetOptionVO(asset.getId(), asset.getAssetCode(), asset.getAssetName(),
            asset.getVendor(), asset.getModelKey(), asset.getFamily(), asset.getAssetVersion(),
            asset.getLifecycleStatus());
    }
}
