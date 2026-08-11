package com.richard.fyoung.customerwork.infra.config;

import com.richard.fyoung.customerwork.core.model.failover.FailoverModel;
import com.richard.fyoung.customerwork.core.model.failover.ModelCircuitBreakerRegistry;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import com.richard.fyoung.customerwork.infra.config.properties.ModelProperties;

/**
 * 模型层配置（对应「模型层 - 统一模型抽象 + 多模型 + 私有化兜底」）。
 *
 * <p>按 {@code customer-work.model.provider} 接入 dashscope（百炼）/ openai / anthropic / gemini /
 * ollama 任一厂商；开启 {@code model.fallback} 后用 {@link FailoverModel} 包一层私有化兜底
 * （主备有序候选 + 熔断记忆）。所有差异被 {@link Model} 抽象屏蔽，业务代码零改动。</p>
 *
 * <p>API Key 来源（按优先级）：配置项 {@code customer-work.model.api-key} → 环境变量 {@code DASHSCOPE_API_KEY}。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(CustomerWorkProperties.class)
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    /** 主模型在 {@link FailoverModel} 候选表里的固定标识（yml 建链无数据库模型 id，用序号占位）。 */
    private static final Long PRIMARY_CANDIDATE_ID = 0L;
    /** 私有化兜底模型在候选表里的固定标识。 */
    private static final Long FALLBACK_CANDIDATE_ID = 1L;
    /** 主备熔断阈值：连续失败该次数后跳过该候选（与 admin 侧默认值一致，yml 不额外暴露配置项）。 */
    private static final int BREAKER_FAILURE_THRESHOLD = 3;
    /** 主备熔断打开时长（秒）。 */
    private static final int BREAKER_OPEN_DURATION_SECONDS = 60;

    /**
     * 客服机器人模型 Bean：以 {@link MutableDelegatingModel} 暴露，内部包裹启动期构建的模型链。
     *
     * <p>返回类型声明为 {@link MutableDelegatingModel}（而非 {@link Model}）：消费方注入 {@code Model}
     * 仍能唯一命中（本模块唯一的 Model Bean）；同时 {@code RuntimeConfigApplier} 可直接注入本具体类型
     * 调 {@link MutableDelegatingModel#swap(Model)} 做热替换，无需 instanceof 向下转型。</p>
     */
    @Bean
    public MutableDelegatingModel chatModel(CustomerWorkProperties properties) {
        return new MutableDelegatingModel(buildChain(properties.getModel()));
    }

    /**
     * 按模型配置构建完整模型链：primary → 可选 {@link FailoverModel}（主备容错）→ 可选 {@link ResilientChatModel}。
     *
     * <p>抽出为可复用方法：启动期 {@link #chatModel} 与运行期热替换（{@code RuntimeConfigApplier}）
     * 共用同一构建逻辑，保证冷启动与热更新产出的链结构一致。</p>
     *
     * <p>{@code midStreamFailoverEnabled=false}：客服主链路是逐字上屏的流式输出，主模型已经吐过分片
     * 再切兜底会把两段输出拼在一起，用户看到重复错乱的文字——此时宁可让错误透传给上层做截断/兜底文案。</p>
     */
    Model buildChain(ModelProperties cfg) {
        Model primary = buildPrimary(cfg);

        Model model = primary;
        ModelProperties.Fallback fb = cfg.getFallback();
        if (fb.isEnabled()) {
            Model fallback = buildByProvider(fb.getProvider(), fb.getName(),
                fb.getApiKey(), fb.getBaseUrl(), cfg);
            log.info("已启用私有化兜底：主 {} -> 兜底 {}({})",
                primary.getModelName(), fb.getProvider(), fb.getName());
            model = new FailoverModel(
                List.of(new FailoverModel.Candidate(PRIMARY_CANDIDATE_ID, primary),
                    new FailoverModel.Candidate(FALLBACK_CANDIDATE_ID, fallback)),
                new ModelCircuitBreakerRegistry(BREAKER_FAILURE_THRESHOLD, BREAKER_OPEN_DURATION_SECONDS),
                false);
        }

        ModelProperties.Retry retry = cfg.getRetry();
        if (retry.isEnabled()) {
            log.info("已启用模型调用重试：maxAttempts={}, backoffMs={}",
                retry.getMaxAttempts(), retry.getBackoffMs());
            model = new ResilientChatModel(model, retry.getMaxAttempts(), retry.getBackoffMs());
        }
        return model;
    }

    private Model buildPrimary(ModelProperties cfg) {
        String apiKey = "dashscope".equalsIgnoreCase(cfg.getProvider())
            ? ChatModelFactory.resolveDashScopeKey(cfg.getApiKey())
            : cfg.getApiKey();
        Model model = buildByProvider(cfg.getProvider(), cfg.getName(), apiKey, cfg.getBaseUrl(), cfg);
        log.info("模型层初始化完成：provider={}, model={}, stream={}",
            cfg.getProvider(), cfg.getName(), cfg.isStream());
        return model;
    }

    /** 高级生成参数（跨厂商统一）：委托 {@link ChatModelFactory#buildOptions}。 */
    GenerateOptions buildOptions(ModelProperties cfg) {
        return ChatModelFactory.buildOptions(cfg);
    }

    /** 按厂商构建模型：委托 {@link ChatModelFactory#build}，stream 与高级生成参数取自 {@code cfg}。 */
    Model buildByProvider(String provider, String name, String apiKey, String baseUrl,
                          ModelProperties cfg) {
        return ChatModelFactory.build(provider, name, apiKey, baseUrl, cfg.isStream(),
            ChatModelFactory.buildOptions(cfg), cfg.getEnableSearch(), cfg.getEnableThinking());
    }
}
