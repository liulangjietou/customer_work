package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 商品/售前后端的默认演示实现。生产替换为真实商品中心 / 推荐 / 库存 / 营销系统。
 * @author owlzhangfq@gmail.com
 */
public class MockProductBackend implements ProductBackend {

    private static final Logger log = LoggerFactory.getLogger(MockProductBackend.class);

    @Override
    public Mono<String> queryProduct(String productId) {
        log.info("[MockProductBackend] query product: {}", productId);
        return Mono.just("商品 " + productId + "：旗舰款无线降噪耳机，蓝牙 5.3，续航 30 小时，支持多点连接，"
                + "颜色 黑/白，质保 1 年。")
            .delayElement(Duration.ofMillis(60));
    }

    @Override
    public Mono<String> recommendProducts(String keyword) {
        log.info("[MockProductBackend] recommend by keyword: {}", keyword);
        return Mono.just("围绕「" + keyword + "」为你推荐：1) 旗舰降噪款（性价比高）；2) 运动防汗款（适合健身）；"
                + "3) 商务通话款（麦克风清晰）。需要我对比参数吗？")
            .delayElement(Duration.ofMillis(70));
    }

    @Override
    public Mono<String> checkStock(String productId) {
        log.info("[MockProductBackend] check stock: {}", productId);
        return Mono.just("商品 " + productId + "：现货充足，下单后 24 小时内发货；黑色有货，白色预计 3 天后补货。")
            .delayElement(Duration.ofMillis(50));
    }

    @Override
    public Mono<String> queryPromotions(String productId) {
        log.info("[MockProductBackend] query promotions: {}", productId);
        return Mono.just("商品 " + productId + " 当前活动：满 300 减 50；可叠加新人券 20 元；下单送收纳包。")
            .delayElement(Duration.ofMillis(50));
    }
}
