package com.richard.fyoung.customerwork.core.model.attribution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelCallCostTest {

    @Test
    void settle_shouldChargeCachedInputOnce_andKeepFourteenDecimalPlaces() {
        ModelCallAttribution attribution = priced("CNY", "2.50000000", "7.50000000", "0.25000000");

        ModelCallCost cost = ModelCallCost.settle(attribution, 100L, 20L, 60L);

        assertEquals(ModelCallCostStatus.SETTLED, cost.status());
        assertEquals("CNY", cost.currency());
        assertEquals(new BigDecimal("0.00026500000000"), cost.amount());
    }

    @Test
    void settle_shouldExposeUnpricedMissingAndInvalidFactsWithoutFakeZero() {
        ModelCallCost unpriced = ModelCallCost.settle(
            ModelCallAttribution.unpriced("dashscope", 1L, "qwen"), 10L, 2L, 0L);
        assertEquals(ModelCallCostStatus.UNPRICED, unpriced.status());
        assertNull(unpriced.amount());

        ModelCallCost missing = ModelCallCost.settle(
            priced("CNY", "2", "8", "0.2"), null, 2L, 0L);
        assertEquals(ModelCallCostStatus.USAGE_MISSING, missing.status());
        assertNull(missing.amount());

        ModelCallCost invalid = ModelCallCost.settle(
            priced("CNY", "2", "8", "0.2"), 10L, 2L, 11L);
        assertEquals(ModelCallCostStatus.USAGE_INVALID, invalid.status());
        assertNull(invalid.amount());
    }

    @Test
    void settle_shouldRejectNegativePriceSnapshot() {
        ModelCallCost cost = ModelCallCost.settle(
            priced("CNY", "-1", "8", "0.2"), 10L, 2L, 0L);

        assertEquals(ModelCallCostStatus.UNPRICED, cost.status());
        assertNull(cost.amount());
    }

    private ModelCallAttribution priced(String currency, String input, String output, String cached) {
        return new ModelCallAttribution("dashscope", 1L, "qwen", 9L, currency,
            new BigDecimal(input), new BigDecimal(output), new BigDecimal(cached),
            ModelPricingStatus.PRICED);
    }
}
