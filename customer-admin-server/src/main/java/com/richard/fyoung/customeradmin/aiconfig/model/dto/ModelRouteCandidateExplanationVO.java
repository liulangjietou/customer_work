package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import java.util.List;

/** dry-run 中每条规则的逐维命中解释。 */
public record ModelRouteCandidateExplanationVO(Long ruleId,
                                               String purpose,
                                               Long deploymentId,
                                               Integer priority,
                                               boolean matched,
                                               List<String> reasons) {
}
