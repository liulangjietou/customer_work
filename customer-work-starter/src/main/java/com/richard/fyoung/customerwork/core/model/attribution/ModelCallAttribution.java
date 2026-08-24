package com.richard.fyoung.customerwork.core.model.attribution;

import java.math.BigDecimal;

/**
 * 一次真实模型调用的不可变部署与价格快照。
 *
 * <p>快照在构建模型链时生成，在底层模型真正被订阅时绑定到当前调用。路由、重试或主备切换
 * 不能事后用模型名反推供应商和价格，否则同名模型会错配，历史金额也会随价目变化漂移。</p>
 */
public record ModelCallAttribution(String provider,
                                   Long deploymentId,
                                   String model,
                                   Long priceId,
                                   String currency,
                                   BigDecimal inputUnitPrice,
                                   BigDecimal outputUnitPrice,
                                   BigDecimal cachedUnitPrice,
                                   ModelPricingStatus pricingStatus) {

    /** 没有有效价格时仍冻结供应商、部署与模型事实，并显式标记 UNPRICED。 */
    public static ModelCallAttribution unpriced(String provider, Long deploymentId, String model) {
        return new ModelCallAttribution(provider, deploymentId, model, null, null,
            null, null, null, ModelPricingStatus.UNPRICED);
    }
}
