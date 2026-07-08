package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelVO;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelConfigMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.runtime.AdminModelFactory;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private final AiModelConfigMapper modelConfigMapper;
    private final AiAgentMapper agentMapper;
    private final AesGcmCryptoUtil cryptoUtil;
    private final AdminModelFactory modelFactory;

    public ModelConfigService(AiModelConfigMapper modelConfigMapper, AiAgentMapper agentMapper,
                               AesGcmCryptoUtil cryptoUtil, AdminModelFactory modelFactory) {
        this.modelConfigMapper = modelConfigMapper;
        this.agentMapper = agentMapper;
        this.cryptoUtil = cryptoUtil;
        this.modelFactory = modelFactory;
    }

    public PageResult<ModelVO> page(PageQuery query) {
        LambdaQueryWrapper<AiModelConfig> wrapper = new LambdaQueryWrapper<>();
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
        modelConfigMapper.insert(model);

        if (Boolean.TRUE.equals(request.isDefault())) {
            clearOtherDefaults(model.getId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ModelSaveRequest request) {
        AiModelConfig model = requireModel(id);
        fillFromRequest(model, request);
        if (StringUtils.hasText(request.apiKey())) {
            model.setApiKey(cryptoUtil.encrypt(request.apiKey()));
        }
        modelConfigMapper.updateById(model);

        if (Boolean.TRUE.equals(request.isDefault())) {
            clearOtherDefaults(id);
        }
    }

    public void delete(Long id) {
        requireModel(id);
        if (agentMapper.exists(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getModelId, id))) {
            throw new BizException(ResultCode.RESOURCE_IN_USE, "该模型配置正被智能体引用，无法删除");
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
            .supplyAsync(() -> modelFactory.testConnectivity(model.getBaseUrl(), apiKey, model.getModel()), MODEL_TEST_EXECUTOR)
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

    /** 设为默认模型时，先清空其余行的 is_default（互斥式设置，需求文档"可设置一个默认模型"）。 */
    private void clearOtherDefaults(Long keepId) {
        LambdaUpdateWrapper<AiModelConfig> wrapper = new LambdaUpdateWrapper<AiModelConfig>()
            .ne(AiModelConfig::getId, keepId)
            .eq(AiModelConfig::getIsDefault, 1)
            .set(AiModelConfig::getIsDefault, 0);
        modelConfigMapper.update(null, wrapper);
    }

    private void fillFromRequest(AiModelConfig model, ModelSaveRequest request) {
        model.setModelName(request.modelName());
        model.setProvider(StringUtils.hasText(request.provider()) ? request.provider() : "openai");
        model.setBaseUrl(request.baseUrl());
        model.setModel(request.model());
        model.setTemperature(request.temperature());
        model.setMaxTokens(request.maxTokens());
        model.setIsDefault(Boolean.TRUE.equals(request.isDefault()) ? 1 : 0);
        model.setStatus(request.status() == null ? 1 : request.status());
    }

    private ModelVO toVo(AiModelConfig model) {
        ModelVO vo = new ModelVO();
        vo.setId(model.getId());
        vo.setModelName(model.getModelName());
        vo.setProvider(model.getProvider());
        vo.setApiKeyMasked(AesGcmCryptoUtil.mask(cryptoUtil.decrypt(model.getApiKey())));
        vo.setBaseUrl(model.getBaseUrl());
        vo.setModel(model.getModel());
        vo.setTemperature(model.getTemperature());
        vo.setMaxTokens(model.getMaxTokens());
        vo.setIsDefault(model.getIsDefault() != null && model.getIsDefault() == 1);
        vo.setStatus(model.getStatus());
        vo.setTestStatus(model.getTestStatus());
        vo.setTestTime(model.getTestTime());
        vo.setCreateTime(model.getCreateTime());
        return vo;
    }

    private AiModelConfig requireModel(Long id) {
        AiModelConfig model = modelConfigMapper.selectById(id);
        if (model == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "模型配置不存在: " + id);
        }
        return model;
    }
}
