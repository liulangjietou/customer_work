package com.richard.fyoung.customerwork.core.model.attribution;

/** 模型调用冻结价格的可结算状态。 */
public enum ModelPricingStatus {

    /** 已冻结明确价目。 */
    PRICED,

    /** 调用时没有匹配到同供应商、同模型的有效价格。 */
    UNPRICED
}
