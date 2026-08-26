package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.core.model.ModelResponses;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalCaseMapper;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalDatasetReleaseMapper;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalDatasetSnapshotMapper;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalRunMapper;
import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * 评测能力装配。
 *
 * <p>按 {@code eval.store-mode} 选择运行记录存储：默认 {@code memory}（进程内，离线可测）；
 * {@code jdbc} 落地为 {@link MybatisEvalRunStore}。下游声明自己的 {@link EvalRunStore} Bean 即可整体覆盖。</p>
 *
 * <p>{@link JudgeModel} 复用主对话模型做 LLM-as-Judge。<b>局限要说在前面</b>：用同一个模型给自己的回复打分
 * 存在自评偏高的倾向，要严格评测应另配一个更强的模型——声明自己的 {@code JudgeModel} Bean 即可替换。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class EvalConfig {

    private static final Logger log = LoggerFactory.getLogger(EvalConfig.class);

    @Bean
    @ConditionalOnMissingBean(EvalRunStore.class)
    public EvalRunStore evalRunStore(CustomerWorkProperties properties,
                                     ObjectProvider<EvalRunMapper> mapperProvider) {
        String mode = properties.getEval().getStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("eval run store: jdbc (MyBatis-Plus 实现, table=cw_eval_run)");
            return new MybatisEvalRunStore(mapperProvider.getObject());
        }
        log.info("eval run store: memory (进程内，重启丢对比基线，生产建议 store-mode=jdbc)");
        return new InMemoryEvalRunStore();
    }

    /**
     * 评测用例存储：memory 模式下为空库，评测集即 classpath 种子（与引入本 SPI 前行为一致）。
     *
     * <p>与运行记录共用 {@code eval.store-mode}：两者要么都落库要么都不落。
     * 单独开一个只落用例不落运行记录的组合没有意义——用例长大了却看不到指标随之如何变化。</p>
     */
    @Bean
    @ConditionalOnMissingBean(EvalCaseStore.class)
    public EvalCaseStore evalCaseStore(CustomerWorkProperties properties,
                                       ObjectProvider<EvalCaseMapper> mapperProvider) {
        String mode = properties.getEval().getStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("eval case store: jdbc (MyBatis-Plus 实现, table=cw_eval_case)");
            return new MybatisEvalCaseStore(mapperProvider.getObject());
        }
        log.info("eval case store: memory (空库，评测集仅含 classpath 种子，badcase 无法回流)");
        return new InMemoryEvalCaseStore();
    }

    /** 数据集版本与运行记录使用同一存储模式，保证运行事实始终能回放到确切用例。 */
    @Bean
    @ConditionalOnMissingBean(EvalDatasetSnapshotStore.class)
    public EvalDatasetSnapshotStore evalDatasetSnapshotStore(
        CustomerWorkProperties properties,
        ObjectProvider<EvalDatasetSnapshotMapper> mapperProvider) {
        if (StoreModes.isJdbc(properties.getEval().getStoreMode())) {
            log.info("eval dataset snapshot store: jdbc (table=cw_eval_dataset_version)");
            return new MybatisEvalDatasetSnapshotStore(mapperProvider.getObject());
        }
        log.info("eval dataset snapshot store: memory");
        return new InMemoryEvalDatasetSnapshotStore();
    }

    /** 命名版本与快照使用同一存储模式；memory 模式适合离线测试，生产应使用 JDBC。 */
    @Bean
    @ConditionalOnMissingBean(EvalDatasetReleaseStore.class)
    public EvalDatasetReleaseStore evalDatasetReleaseStore(
        CustomerWorkProperties properties,
        ObjectProvider<EvalDatasetReleaseMapper> mapperProvider) {
        if (StoreModes.isJdbc(properties.getEval().getStoreMode())) {
            log.info("eval dataset release store: jdbc (table=cw_eval_dataset_release)");
            return new MybatisEvalDatasetReleaseStore(mapperProvider.getObject());
        }
        log.info("eval dataset release store: memory");
        return new InMemoryEvalDatasetReleaseStore();
    }

    @Bean
    @ConditionalOnMissingBean(EvalArtifactVersionProvider.class)
    public EvalArtifactVersionProvider evalArtifactVersionProvider(
        CustomerWorkProperties properties, ObjectProvider<JudgeModel> judgeModelProvider) {
        return new ConfigurationEvalArtifactVersionProvider(properties, judgeModelProvider);
    }

    /**
     * LLM-as-Judge 打分模型：把框架的流式 {@code Model} 适配成"一进一出"的同步契约。
     *
     * <p>{@link Model} 用 {@link ObjectProvider} <b>惰性</b>获取而非构造参数直接注入：
     * 本类由 {@code @ComponentScan} 装配，与 infra 域的模型 Bean 之间没有可靠的先后顺序，
     * 用 {@code @ConditionalOnBean(Model.class)} 会因判定时机过早而随机失效。
     * 惰性取则把"有没有模型"的判断推迟到真正打分的那一刻，此时容器早已就绪。</p>
     */
    @Bean
    @ConditionalOnMissingBean(JudgeModel.class)
    public JudgeModel judgeModel(ObjectProvider<Model> modelProvider, CustomerWorkProperties properties) {
        long timeoutSeconds = Math.max(1, properties.getEval().getJudgeTimeoutSeconds());
        return new JudgeModel() {
            @Override
            public Msg chat(Msg message) {
                Model model = modelProvider.getIfAvailable();
                if (model == null) {
                    throw new IllegalStateException("judge unavailable: no Model bean (needs a real model key)");
                }
                List<ChatResponse> responses = model
                    .stream(List.of(message), List.of(), GenerateOptions.builder().build())
                    .collectList()
                    .block(Duration.ofSeconds(timeoutSeconds));
                if (responses == null || responses.isEmpty()) {
                    throw new IllegalStateException("judge model returned empty response");
                }
                String text = ModelResponses.text(responses);
                return Msg.builder()
                    .role(MsgRole.ASSISTANT)
                    .name("judge")
                    .content(TextBlock.builder().text(text).build())
                    .build();
            }

            @Override
            public String version() {
                return EvalFingerprint.of("default-main-model-judge-v1",
                    properties.getModel().getProvider(), properties.getModel().getName(),
                    properties.getModel().getBaseUrl());
            }
        };
    }
}
