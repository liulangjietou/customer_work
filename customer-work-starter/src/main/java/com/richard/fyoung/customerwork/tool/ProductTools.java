package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.ProductBackend;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 售前导购工具组：商品咨询 / 推荐 / 库存 / 优惠。业务委托给可替换的 {@link ProductBackend}。
 *
 * <p>补全客服旅程的"售前"半段——直接关联下单转化；与售后工具组并列由 {@code ToolRegistrar} 注册。</p>
 * @author owlzhangfq@gmail.com
 */
public class ProductTools {

    private final ProductBackend backend;

    public ProductTools(ProductBackend backend) {
        this.backend = backend;
    }

    @Tool(description = "查询商品的基础信息与参数（规格、续航、颜色、质保等）。用户咨询某商品详情/参数时调用。")
    public Mono<String> queryProduct(
            @ToolParam(name = "productId", description = "商品编号或名称")
            String productId) {
        return backend.queryProduct(productId);
    }

    @Tool(description = "根据用户的需求关键词或品类推荐合适的商品（售前导购）。用户说'有没有推荐''适合XX的'时调用。")
    public Mono<String> recommendProducts(
            @ToolParam(name = "keyword", description = "需求关键词或品类，如 '降噪耳机'、'适合健身'")
            String keyword) {
        return backend.recommendProducts(keyword);
    }

    @Tool(description = "查询商品库存与到货情况。用户问'有货吗''什么时候补货''多久发货'时调用。")
    public Mono<String> checkStock(
            @ToolParam(name = "productId", description = "商品编号或名称")
            String productId) {
        return backend.checkStock(productId);
    }

    @Tool(description = "查询商品当前的优惠活动与可用券。用户问'有什么优惠''能用券吗''活动价'时调用。")
    public Mono<String> queryPromotions(
            @ToolParam(name = "productId", description = "商品编号或名称")
            String productId) {
        return backend.queryPromotions(productId);
    }
}
