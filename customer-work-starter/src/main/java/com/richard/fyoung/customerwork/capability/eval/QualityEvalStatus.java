package com.richard.fyoung.customerwork.capability.eval;

/** 质量评测运行状态：模型判分不可用时必须显式 ERROR，不能伪造中性分。 */
public enum QualityEvalStatus {
    COMPLETED,
    ERROR
}
