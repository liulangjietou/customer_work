package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationCheckVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelCertificationRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelAsset;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;

import java.util.List;

/** 上线认证的真实模型能力探测 SPI，便于离线单测注入确定性证据。 */
public interface ModelCertificationProbe {

    ProbeResult probe(AiModelConfig deployment,
                      AiModelAsset asset,
                      String secretValue,
                      ModelCertificationRequest request);

    record ProbeResult(List<ModelCertificationCheckVO> checks,
                       Long latencyP95Ms,
                       Integer verifiedContextTokens) {
    }
}
