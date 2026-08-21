package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelAgentReference;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.ModelAgentReferenceMapper;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型配置引用关系的统一读取边界。
 *
 * <p>私有模型只能查询当前租户；{@code default} 共享模型才允许显式跳过租户拦截器，
 * 扫描所有引用租户。调用方不再各自拼主模型、备用模型查询，避免漏掉跨租户引用。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ModelReferenceAccess {

    private final ModelAgentReferenceMapper referenceMapper;
    private final AdminTenantProperties tenantProperties;

    public ModelReferenceAccess(ModelAgentReferenceMapper referenceMapper,
                                AdminTenantProperties tenantProperties) {
        this.referenceMapper = referenceMapper;
        this.tenantProperties = tenantProperties;
    }

    public List<ModelAgentReference> findReferences(AiModelConfig model) {
        if (!tenantProperties.isEnabled()) {
            return referenceMapper.findReferences(model.getId(), null);
        }
        if (TenantContext.isDefaultTenant(model.getTenantId())) {
            return CrossTenantOperations.execute(
                () -> referenceMapper.findReferences(model.getId(), null));
        }

        String currentTenant = TenantContext.require();
        if (!TenantContext.sameTenant(currentTenant, model.getTenantId())) {
            throw new IllegalStateException("private model reference scan must run in owning tenant");
        }
        return referenceMapper.findReferences(model.getId(), currentTenant);
    }
}
