package com.richard.fyoung.customeradmin.businessoutcome.dto;

import lombok.Data;

/** 可下钻解释的会话级结果原始行。 */
@Data
public class BusinessOutcomeSessionRow {

    private String sessionId;
    private String agentCodes;
    private Long firstCallAtMs;
    private Long lastCallAtMs;
    private Long callCount;
    private Long failedCalls;
    private Long knownTokenCalls;
    private Long unknownTokenCalls;
    private Long knownTotalTokens;
    private Boolean handedOff;
    private Integer csatScore;
}
