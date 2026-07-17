package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 商品/售前后端（扩展点）：对接你自己的商品中心 / 推荐 / 库存 / 营销系统。
 * 默认 {@link MockProductBackend}；提供自定义 Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public interface ProductBackend {

    /** 查询商品基础信息与参数。 */
    Mono<String> queryProduct(String productId);

    /** 按关键词/品类推荐商品（售前导购）。 */
    Mono<String> recommendProducts(String keyword);

    /** 查询商品库存与到货情况。 */
    Mono<String> checkStock(String productId);

    /** 查询商品在售优惠/活动/可用券。 */
    Mono<String> queryPromotions(String productId);
}
