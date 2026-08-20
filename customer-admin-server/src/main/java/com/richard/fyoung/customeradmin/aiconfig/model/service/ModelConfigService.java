package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgentBackupModel;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentBackupModelMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customeradmin.workspace.runtime.AgentInstanceCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * AI 模型配置管理：CRUD + AppKey 加密存储 + 默认模型互斥设置 + 连通性测试。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    /** 连通性测试专用线程池：与 Tomcat 请求线程隔离，低频操作，池子不需要大（实施计划 §五）。 */
    private static final ExecutorService MODEL_TEST_EXECUTOR = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "model-test-worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final long TEST_FUTURE_TIMEOUT_SECONDS = 10;

    /** 平台级凭据对租户视角的占位显示（不透出任何真实字符）。 */
    private static final String PLATFORM_KEY_PLACEHOLDER = "********（平台统一配置）";

    private final AiModelConfigMapper modelConfigMapper;
    private final AiAgentMapper agentMapper;
    private final AiAgentBackupModelMapper agentBackupModelMapper;
    private final AesGcmCryptoUtil cryptoUtil;
    private final AdminModelFactory modelFactory;
    private final AgentInstanceCache agentInstanceCache;
    private final CustomerWorkConfigPublisher runtimeConfigPublisher;
    private final AdminTenantProperties tenantProperties;

    public ModelConfigService(AiModelConfigMapper modelConfigMapper, AiAgentMapper agentMapper,
                               AiAgentBackupModelMapper agentBackupModelMapper,
                               AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory,
                               AgentInstanceCache agentInstanceCache,
                               CustomerWorkConfigPublisher runtimeConfigPublisher,
                               AdminTenantProperties tenantProperties) {
        this.modelConfigMapper = modelConfigMapper;
        this.agentMapper = agentMapper;
        this.agentBackupModelMapper = agentBackupModelMapper;
        this.cryptoUtil = cryptoUtil;
        this.modelFactory = modelFactory;
        this.agentInstanceCache = agentInstanceCache;
        this.runtimeConfigPublisher = runtimeConfigPublisher;
        this.tenantProperties = tenantProperties;
    }

    // ---------------------------------------------------------------------
    // 两级可见性（docs/多租户架构设计.md §2.4）
    //
    // 本表承载模型凭据，为支持"平台预置 + 租户自建"两级共享而进了租户忽略清单
    // （TenantInterceptors.PLATFORM_LEVEL_TABLES），SQL 拦截器不会自动加租户条件。
    // 补偿控制因此必须在本 Service 显式实现，且是这张表<b>唯一</b>的一道防线——
    // 它也刻意不在 DataScopeTables 白名单里，没有第二层兜底。
    // ---------------------------------------------------------------------

    /**
     * 读可见范围：本租户 + 平台级。
     *
     * <p>多租户关闭时（单租户部署）不加条件，与拦截器整体不生效的行为保持一致。</p>
     */
    private void applyReadScope(LambdaQueryWrapper<AiModelConfig> wrapper) {
        if (!tenantProperties.isEnabled()) {
            return;
        }
        String tenant = requireTenant();
        wrapper.in(AiModelConfig::getTenantId, tenant, TenantContext.PLATFORM);
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

    /** 平台级记录对非运营方只读：租户能用平台预置的模型，但改不动、删不掉。 */
    private boolean isPlatformRecordReadOnlyFor(AiModelConfig model) {
        return tenantProperties.isEnabled()
            && TenantContext.PLATFORM.equals(model.getTenantId())
            && !TenantSession.isPlatformOperator();
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
        return PageResult.of(page.convert(this::toVo));
    }

    public ModelVO get(Long id) {
        return toVo(requireModel(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(ModelSaveRequest request) {
        if (!StringUtils.hasText(request.apiKey())) {
            throw new BizException(ResultCode.PARAM_MISSING, "新建模型配置必须提供 apiKey");
        }
        AiModelConfig model = new AiModelConfig();
        fillFromRequest(model, request);
        model.setApiKey(cryptoUtil.encrypt(request.apiKey()));
        model.setTestStatus(ModelTestResult.STATUS_UNTESTED);
        // 本表在租户忽略清单里，拦截器不会自动补租户列，必须显式落归属
        if (tenantProperties.isEnabled()) {
            model.setTenantId(requireTenant());
        }
        modelConfigMapper.insert(model);

        if (Boolean.TRUE.equals(request.isDefault())) {
            clearOtherDefaults(model.getId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ModelSaveRequest request) {
        AiModelConfig model = requireWritableModel(id);
        fillFromRequest(model, request);
        if (StringUtils.hasText(request.apiKey())) {
            model.setApiKey(cryptoUtil.encrypt(request.apiKey()));
        }
        modelConfigMapper.updateById(model);

        if (Boolean.TRUE.equals(request.isDefault())) {
            clearOtherDefaults(id);
        }
        evictAgentsReferencingModel(id);
    }

    /**
     * 模型配置变更（baseUrl/apiKey/model 等）会让引用它的智能体运行时用上旧配置构建的实例，需一并失效。
     * 引用关系覆盖两处：作为主模型（{@code ai_agent.model_id}）与作为备用模型（{@code ai_agent_backup_model}）。
     */
    private void evictAgentsReferencingModel(Long modelId) {
        Set<String> agentCodes = new LinkedHashSet<>(agentMapper
            .selectList(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getModelId, modelId))
            .stream().map(AiAgent::getAgentCode).collect(Collectors.toList()));
        agentCodes.addAll(agentCodesReferencingModelAsBackup(modelId));
        agentInstanceCache.evictAll(new ArrayList<>(agentCodes));
        // 模型配置变更后，命中渠道绑定的智能体重新下发运行时配置到 8080（默认关闭，未启用即跳过）
        runtimeConfigPublisher.publishForModelId(modelId);
    }

    /** 查以该模型为备用模型的智能体 code 列表（backup 关联表 -> agentId -> agentCode）。 */
    private List<String> agentCodesReferencingModelAsBackup(Long modelId) {
        List<Long> agentIds = agentBackupModelMapper
            .selectList(new LambdaQueryWrapper<AiAgentBackupModel>().eq(AiAgentBackupModel::getModelId, modelId))
            .stream().map(AiAgentBackupModel::getAgentId).collect(Collectors.toList());
        if (agentIds.isEmpty()) {
            return List.of();
        }
        return agentMapper.selectBatchIds(agentIds).stream().map(AiAgent::getAgentCode).collect(Collectors.toList());
    }

    public void delete(Long id) {
        requireWritableModel(id);
        if (agentMapper.exists(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getModelId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该模型配置正被智能体引用，无法删除");
        }
        if (agentBackupModelMapper.exists(new LambdaQueryWrapper<AiAgentBackupModel>().eq(AiAgentBackupModel::getModelId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该模型配置正被智能体作为备用模型引用，无法删除");
        }
        modelConfigMapper.deleteById(id);
    }

    /**
     * 连通性测试：解密 apiKey 后派发到独立线程池执行，硬性超时兜底，不占用调用方（Tomcat）线程。
     * Controller 侧以 {@link CompletableFuture} 异步返回，Spring MVC 借此在等待期间释放请求线程。
     */
    public CompletableFuture<ModelTestResult> testConnectivity(Long id) {
        AiModelConfig model = requireModel(id);
        String apiKey = cryptoUtil.decrypt(model.getApiKey());

        return CompletableFuture
            .supplyAsync(() -> modelFactory.testConnectivity(model.getProvider(), model.getBaseUrl(), apiKey, model.getModel()), MODEL_TEST_EXECUTOR)
            .orTimeout(TEST_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                if (ex.getCause() instanceof TimeoutException || ex instanceof TimeoutException) {
                    log.error("model connectivity test hard-timeout, code={}, modelId={}", "MODEL-TEST-HARD-TIMEOUT", id, ex);
                } else {
                    log.error("model connectivity test unexpected error, code={}, modelId={}", "MODEL-TEST-UNEXPECTED", id, ex);
                }
                return new ModelTestResult(ModelTestResult.STATUS_FAILED, LocalDateTime.now(), "连通性测试超时或执行异常");
            })
            .thenApply(result -> {
                persistTestResult(id, result);
                return result;
            });
    }

    private void persistTestResult(Long id, ModelTestResult result) {
        AiModelConfig update = new AiModelConfig();
        update.setId(id);
        update.setTestStatus(result.testStatus());
        update.setTestTime(result.testTime());
        modelConfigMapper.updateById(update);
    }

    /**
     * 设为默认模型时，先清空<b>本租户内</b>其余行的 is_default（互斥式设置）。
     *
     * <p>租户条件不可省：没有它，任一租户设默认模型都会把所有租户的 is_default 一并清零，
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

    private void fillFromRequest(AiModelConfig model, ModelSaveRequest request) {
        model.setModelName(request.modelName());
        model.setProvider(StringUtils.hasText(request.provider()) ? request.provider() : "openai");
        model.setBaseUrl(request.baseUrl());
        model.setModel(request.model());
        model.setIsDefault(Boolean.TRUE.equals(request.isDefault()) ? 1 : 0);
        model.setStatus(request.status() == null ? 1 : request.status());
    }

    private ModelVO toVo(AiModelConfig model) {
        ModelVO vo = new ModelVO();
        vo.setId(model.getId());
        vo.setModelName(model.getModelName());
        vo.setProvider(model.getProvider());
        // 平台级记录的凭据对租户视角不回显：脱敏串仍会漏出前后若干位，而这把 key 不属于他们
        vo.setApiKeyMasked(isPlatformRecordReadOnlyFor(model)
            ? PLATFORM_KEY_PLACEHOLDER
            : AesGcmCryptoUtil.mask(cryptoUtil.decrypt(model.getApiKey())));
        vo.setBaseUrl(model.getBaseUrl());
        vo.setModel(model.getModel());
        vo.setIsDefault(model.getIsDefault() != null && model.getIsDefault() == 1);
        vo.setStatus(model.getStatus());
        vo.setTestStatus(model.getTestStatus());
        vo.setTestTime(model.getTestTime());
        vo.setCreateTime(model.getCreateTime());
        return vo;
    }

    /**
     * 读取一条模型配置：可见范围为本租户 + 平台级。
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
     * 取一条<b>可写</b>的模型配置：在可读基础上再要求归属本租户。
     *
     * <p>租户改不动平台记录——否则任一租户都能把平台主模型的 baseUrl 指向自己的服务器，
     * 再触发一次连通性测试就能拿到平台的明文 apiKey。</p>
     */
    private AiModelConfig requireWritableModel(Long id) {
        AiModelConfig model = requireModel(id);
        if (isPlatformRecordReadOnlyFor(model)) {
            throw new BizException(ResultCode.FORBIDDEN, "平台级模型配置不允许租户修改: " + id);
        }
        return model;
    }

    private boolean visible(AiModelConfig model) {
        if (!tenantProperties.isEnabled()) {
            return true;
        }
        String tenant = requireTenant();
        return tenant.equals(model.getTenantId()) || TenantContext.PLATFORM.equals(model.getTenantId());
    }
}
