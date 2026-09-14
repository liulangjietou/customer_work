package com.richard.fyoung.customeradmin.ops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;

/** 可持久化的评测模型输入；密钥、SecretRef 和运行时 Model 对象不进入候选证据。 */
public record FrozenKnowledgeModel(Long deploymentId, Integer endpointRevision, String provider,
                                    String baseUrl, String model, Integer contextWindowSize) {
    /** 指纹覆盖实际参与本次构建的全部非密钥参数。 */
    @JsonIgnore
    public String fingerprint() {
        return EvalFingerprint.of("knowledge-trial-model-v1", deploymentId, endpointRevision,
            provider, baseUrl, model, contextWindowSize);
    }
}
