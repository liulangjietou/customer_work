package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.springframework.beans.factory.ObjectProvider;

/** 默认从客服端真实生效的强类型配置采集版本；可由下游 Bean 替换知识库等权威版本来源。 */
public class ConfigurationEvalArtifactVersionProvider implements EvalArtifactVersionProvider {

    private final CustomerWorkProperties properties;
    private final ObjectProvider<JudgeModel> judgeModelProvider;

    public ConfigurationEvalArtifactVersionProvider(CustomerWorkProperties properties,
                                                    ObjectProvider<JudgeModel> judgeModelProvider) {
        this.properties = properties;
        this.judgeModelProvider = judgeModelProvider;
    }

    @Override
    public EvalVersionBinding capture(EvalType type, String promptVersion) {
        JudgeModel judge = judgeModelProvider.getIfAvailable();
        String judgeVersion = judge == null ? "UNAVAILABLE" : judge.version();
        String rubricVersion = type == EvalType.QUALITY
            ? QualityEvalRunner.rubricVersion() : IntentEvalRunner.evaluatorVersion();
        return EvalVersionBinding.fromProperties(
            properties, type, promptVersion, judgeVersion, rubricVersion);
    }
}
