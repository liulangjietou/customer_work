package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 模型配置运行时读取入口。
 *
 * <p>{@code ai_model_config} 为租户忽略表，MyBatis 租户拦截器不会补查询条件。运行时若直接使用
 * {@link AiModelConfigMapper}，一次按主键读取就能越过租户边界拿到其它租户的模型密钥。本类统一补上
 * “当前生效租户自有配置 + default 共享基线”的只读边界；写入与管理权限仍由
 * {@link ModelConfigService} 负责。</p>
 *
 * <p>自动选择模型时，同类配置始终优先当前租户，其次才回退 default；显式按 ID 引用则只校验该行
 * 是否可见，不擅自替换成另一个模型。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ModelConfigAccess {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigAccess.class);
    private static final String CODE_TENANT_MISSING = "MODEL-TENANT-MISSING";
    private static final int DEFAULT_MODEL = 1;

    private final AiModelConfigMapper modelConfigMapper;
    private final AiModelCertificationMapper certificationMapper;
    private final AdminTenantProperties tenantProperties;

    public ModelConfigAccess(AiModelConfigMapper modelConfigMapper,
                             AiModelCertificationMapper certificationMapper,
                             AdminTenantProperties tenantProperties) {
        this.modelConfigMapper = modelConfigMapper;
        this.certificationMapper = certificationMapper;
        this.tenantProperties = tenantProperties;
    }

    /**
     * 按 ID 读取当前租户可运行的部署。新部署还必须持有未过期且未漂移的认证；存量行按迁移兼容位放行。
     */
    public AiModelConfig findVisibleById(Long modelId) {
        AiModelConfig model = findVisibleAnyStateById(modelId);
        return runnable(model) ? model : null;
    }

    /** 管理面、健康与认证读取任意生命周期的可见部署，不作为运行时建模入口。 */
    public AiModelConfig findVisibleAnyStateById(Long modelId) {
        if (modelId == null) {
            return null;
        }
        LambdaQueryWrapper<AiModelConfig> wrapper = new LambdaQueryWrapper<AiModelConfig>()
            .eq(AiModelConfig::getId, modelId);
        applyVisibleScope(wrapper, currentTenant());
        return modelConfigMapper.selectOne(wrapper);
    }

    /** 批量读取当前租户可见的模型；输入去重但不承诺结果顺序。 */
    public List<AiModelConfig> listVisibleByIds(Collection<Long> modelIds) {
        if (CollectionUtils.isEmpty(modelIds)) {
            return List.of();
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(modelIds));
        LambdaQueryWrapper<AiModelConfig> wrapper = new LambdaQueryWrapper<AiModelConfig>()
            .in(AiModelConfig::getId, distinctIds);
        applyVisibleScope(wrapper, currentTenant());
        return modelConfigMapper.selectList(wrapper).stream().filter(this::runnable).toList();
    }

    /**
     * 返回当前租户可用的启用模型，排序为“本租户优先、default 回退；同一层默认模型优先、ID 升序”。
     *
     * @param provider 厂商编码；空值表示不限厂商
     */
    public List<AiModelConfig> listPreferredEnabled(String provider) {
        String tenant = currentTenant();
        LambdaQueryWrapper<AiModelConfig> wrapper = new LambdaQueryWrapper<AiModelConfig>()
            .eq(AiModelConfig::getStatus, StatusFlags.ENABLED);
        if (StringUtils.hasText(provider)) {
            wrapper.eq(AiModelConfig::getProvider, provider);
        }
        applyVisibleScope(wrapper, tenant);
        List<AiModelConfig> models = modelConfigMapper.selectList(wrapper).stream()
            .filter(this::runnable).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        models.sort(Comparator
            .comparingInt((AiModelConfig model) -> tenantPriority(model, tenant))
            .thenComparingInt(this::defaultPriority)
            .thenComparing(AiModelConfig::getId, Comparator.nullsLast(Long::compareTo)));
        return models;
    }

    private void applyVisibleScope(LambdaQueryWrapper<AiModelConfig> wrapper, String tenant) {
        if (!tenantProperties.isEnabled()) {
            return;
        }
        if (TenantContext.isDefaultTenant(tenant)) {
            wrapper.eq(AiModelConfig::getTenantId, TenantContext.DEFAULT);
            return;
        }
        wrapper.in(AiModelConfig::getTenantId, tenant, TenantContext.DEFAULT);
    }

    /** 多租户开启时缺上下文必须 fail-closed；关闭时保留历史无过滤行为。 */
    private String currentTenant() {
        if (!tenantProperties.isEnabled()) {
            return null;
        }
        String tenant = TenantContext.get();
        if (!StringUtils.hasText(tenant)) {
            log.error("model config runtime access without tenant context, code={}", CODE_TENANT_MISSING);
            throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文，无法访问模型配置");
        }
        return tenant;
    }

    private int tenantPriority(AiModelConfig model, String tenant) {
        if (!tenantProperties.isEnabled() || TenantContext.sameTenant(tenant, model.getTenantId())) {
            return 0;
        }
        return 1;
    }

    private int defaultPriority(AiModelConfig model) {
        return Integer.valueOf(DEFAULT_MODEL).equals(model.getIsDefault()) ? 0 : 1;
    }

    private boolean runnable(AiModelConfig model) {
        if (model == null
            || !Integer.valueOf(StatusFlags.ENABLED).equals(model.getStatus())
            || !ModelDeploymentLifecycle.ACTIVE.name().equals(model.getLifecycleStatus())) {
            return false;
        }
        if (!Integer.valueOf(1).equals(model.getCertificationRequired())) {
            return true;
        }
        AiModelCertification certification = CrossTenantOperations.execute(() -> certificationMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiModelCertification>()
                .eq("model_config_id", model.getId())
                .eq("tenant_id", model.getTenantId())));
        return certification != null
            && "PASSED".equals(certification.getStatus())
            && Objects.equals(model.getEndpointRevision(), certification.getCertifiedEndpointRevision())
            && certification.getValidUntil() != null
            && certification.getValidUntil().isAfter(LocalDateTime.now());
    }
}
