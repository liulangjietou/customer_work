package com.richard.fyoung.customeradmin.improvement.jdbc;

import lombok.Data;

/** 客服库原始信号的最小观测投影，不复制回答正文到 Admin 库。 */
@Data
public class ImprovementSourceFact {
    private String sourceKey;
    private String question;
    private String signalHash;
    private Long signalCount;
    private String evalCaseId;
}
