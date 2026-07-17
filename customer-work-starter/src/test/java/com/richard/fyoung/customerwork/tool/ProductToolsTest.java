package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MockProductBackend;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售前导购工具组单测：商品咨询 / 推荐 / 库存 / 优惠。
 * @author owlzhangfq@gmail.com
 */
class ProductToolsTest {

    private final ProductTools tools = new ProductTools(new MockProductBackend());

    @Test
    void queryProduct_shouldReturnSpecs() {
        StepVerifier.create(tools.queryProduct("P-1001"))
            .assertNext(r -> assertTrue(r.contains("P-1001")))
            .verifyComplete();
    }

    @Test
    void recommendProducts_shouldReturnRecommendations() {
        StepVerifier.create(tools.recommendProducts("降噪耳机"))
            .assertNext(r -> assertTrue(r.contains("推荐")))
            .verifyComplete();
    }

    @Test
    void checkStock_shouldReturnStockInfo() {
        StepVerifier.create(tools.checkStock("P-1001"))
            .assertNext(r -> assertTrue(r.contains("货")))
            .verifyComplete();
    }

    @Test
    void queryPromotions_shouldReturnPromotions() {
        StepVerifier.create(tools.queryPromotions("P-1001"))
            .assertNext(r -> assertTrue(r.contains("活动") || r.contains("券") || r.contains("减")))
            .verifyComplete();
    }
}
