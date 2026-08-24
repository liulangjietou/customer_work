package com.richard.fyoung.customerwork.core.model.attribution;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 单个真实模型调用按冻结价格结算出的不可变金额事实。
 *
 * <p>价目单位是“币种/百万 token”。价格列最多 8 位小数，除以一百万后最多需要 14 位
 * 小数，因此金额统一保留 14 位，保证调用、会话与日账单逐层求和时不因中途四舍五入产生差额。</p>
 */
public record ModelCallCost(BigDecimal amount, String currency, ModelCallCostStatus status) {

    public static final int AMOUNT_SCALE = 14;
    private static final int TOKENS_PER_MILLION_SCALE = 6;

    /** 根据一次模型分段的冻结价目与 usage 结算；任何事实缺失都返回明确状态而非零金额。 */
    public static ModelCallCost settle(ModelCallAttribution attribution,
                                       Long inputTokens,
                                       Long outputTokens,
                                       Long cachedTokens) {
        String currency = attribution == null ? null : trimToNull(attribution.currency());
        if (attribution == null || attribution.pricingStatus() != ModelPricingStatus.PRICED
            || currency == null || attribution.inputUnitPrice() == null
            || attribution.outputUnitPrice() == null
            || attribution.inputUnitPrice().signum() < 0
            || attribution.outputUnitPrice().signum() < 0
            || (cachedTokens != null && cachedTokens > 0
                && (attribution.cachedUnitPrice() == null
                    || attribution.cachedUnitPrice().signum() < 0))) {
            return unresolved(currency, ModelCallCostStatus.UNPRICED);
        }
        if (inputTokens == null || outputTokens == null) {
            return unresolved(currency, ModelCallCostStatus.USAGE_MISSING);
        }
        long cached = cachedTokens == null ? 0L : cachedTokens;
        if (inputTokens < 0 || outputTokens < 0 || cached < 0 || cached > inputTokens) {
            return unresolved(currency, ModelCallCostStatus.USAGE_INVALID);
        }

        long uncachedInput = inputTokens - cached;
        BigDecimal amount = charge(uncachedInput, attribution.inputUnitPrice())
            .add(charge(outputTokens, attribution.outputUnitPrice()))
            .add(charge(cached, attribution.cachedUnitPrice()));
        return new ModelCallCost(amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
            currency, ModelCallCostStatus.SETTLED);
    }

    public static ModelCallCost notApplicable() {
        return new ModelCallCost(null, null, ModelCallCostStatus.NOT_APPLICABLE);
    }

    public boolean settled() {
        return status == ModelCallCostStatus.SETTLED;
    }

    private static ModelCallCost unresolved(String currency, ModelCallCostStatus status) {
        return new ModelCallCost(null, currency, status);
    }

    private static BigDecimal charge(long tokens, BigDecimal unitPrice) {
        if (tokens == 0L || unitPrice == null) {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        }
        return unitPrice.multiply(BigDecimal.valueOf(tokens))
            .movePointLeft(TOKENS_PER_MILLION_SCALE)
            .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
