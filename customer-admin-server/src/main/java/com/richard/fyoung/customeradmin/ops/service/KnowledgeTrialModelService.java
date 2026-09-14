package com.richard.fyoung.customeradmin.ops.service;

import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelAssetService;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.ops.domain.FrozenKnowledgeModel;
import io.agentscope.core.model.Model;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 评测模型的冻结、凭据解析与装配；始终通过现有部署可见性及可运行性检查。 */
@Service
public class KnowledgeTrialModelService {
    private final ModelConfigAccess access;
    private final ModelAssetService assets;
    private final SecretRefService secrets;
    private final AdminModelFactory factory;

    public KnowledgeTrialModelService(ModelConfigAccess access, ModelAssetService assets,
                                       SecretRefService secrets, AdminModelFactory factory) {
        this.access = access; this.assets = assets; this.secrets = secrets; this.factory = factory;
    }

    /** 冻结请求选中的确切部署，不自动改选主备、路由或其它租户模型。 */
    public FrozenKnowledgeModel freeze(Long deploymentId) {
        return describe(requireVisible(deploymentId));
    }

    /** 先核对冻结输入再解析凭据，实际建模只使用冻结值，避免读到一半的新配置。 */
    public Model build(FrozenKnowledgeModel expected) {
        AiModelConfig current = requireVisible(expected.deploymentId());
        requireSame(expected, describe(current));
        String key = secrets.resolvePlaintext(current);
        return factory.buildModel(expected.provider(), expected.baseUrl(), key, expected.model(),
            expected.deploymentId(), expected.contextWindowSize());
    }

    /** 发布前重新核对启用态、认证、端点和窗口；不解析密钥或发起模型请求。 */
    public void requireCurrent(FrozenKnowledgeModel expected) {
        requireSame(expected, freeze(expected.deploymentId()));
    }

    // 试评包含冻结的租户知识；即使通用部署入口处于历史兼容模式，也只允许本租户与共享基线。
    private AiModelConfig requireVisible(Long id) {
        String tenant = TenantContext.require();
        AiModelConfig model = access.findVisibleById(id);
        if (model == null || (!tenant.equals(model.getTenantId())
            && !TenantContext.DEFAULT.equals(model.getTenantId()))) throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "评测模型不存在或当前不可运行");
        return model;
    }

    private FrozenKnowledgeModel describe(AiModelConfig config) {
        return new FrozenKnowledgeModel(config.getId(), config.getEndpointRevision(), config.getProvider(),
            config.getBaseUrl(), config.getModel(), assets.findDeclaredContextWindow(config));
    }

    private void requireSame(FrozenKnowledgeModel expected, FrozenKnowledgeModel current) {
        if (!expected.equals(current)) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "评测模型配置已变化，请重新绑定并评测");
        }
    }
}
