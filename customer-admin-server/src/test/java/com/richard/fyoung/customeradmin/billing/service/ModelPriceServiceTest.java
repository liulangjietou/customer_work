package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.entity.AiModelPrice;
import com.richard.fyoung.customeradmin.billing.mapper.AiModelPriceMapper;
import com.richard.fyoung.customerwork.core.model.attribution.ModelCallAttribution;
import com.richard.fyoung.customerwork.core.model.attribution.ModelPricingStatus;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link ModelPriceService} 的供应商精确取价与发布价格快照测试。 */
class ModelPriceServiceTest {

    private AiModelPriceMapper priceMapper;
    private ModelPriceService service;

    @BeforeEach
    void setUp() {
        priceMapper = mock(AiModelPriceMapper.class);
        service = new ModelPriceService(priceMapper);
    }

    @Test
    void findEffectivePrice_shouldFastFail_whenProviderIsMissing() {
        assertNull(service.findEffectivePrice("", "shared-model", LocalDateTime.now()));
        verify(priceMapper, never()).selectList(any());
    }

    @Test
    void snapshotAndAttribution_shouldFreezeExactProviderPrice() {
        AiModelPrice price = price();
        when(priceMapper.selectList(any())).thenReturn(List.of(price));

        CustomerWorkRuntimeConfig.Pricing snapshot = service.snapshot("provider-a", "shared-model");
        ModelCallAttribution attribution = service.attribution("provider-a", 101L, "shared-model");

        assertEquals("PRICED", snapshot.getStatus());
        assertEquals(91L, snapshot.getPriceId());
        assertEquals(ModelPricingStatus.PRICED, attribution.pricingStatus());
        assertEquals("provider-a", attribution.provider());
        assertEquals(101L, attribution.deploymentId());
        assertEquals(new BigDecimal("2.50"), attribution.inputUnitPrice());

        verify(priceMapper, times(2)).selectList(any());
    }

    private AiModelPrice price() {
        AiModelPrice price = new AiModelPrice();
        price.setId(91L);
        price.setProvider("provider-a");
        price.setModelName("shared-model");
        price.setCurrency("CNY");
        price.setInputPrice(new BigDecimal("2.50"));
        price.setOutputPrice(new BigDecimal("7.50"));
        price.setCachedPrice(new BigDecimal("0.25"));
        price.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        return price;
    }
}
