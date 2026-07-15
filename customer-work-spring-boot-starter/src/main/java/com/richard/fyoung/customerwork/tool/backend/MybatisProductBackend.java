package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.tool.backend.entity.ProductDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 商品/售前后端的 MyBatis-Plus 实现：从 {@code cw_product} 表读真实商品数据。
 *
 * <p>输出文案对齐 {@link MockProductBackend}；单条查询走 {@link ProductMapper}（BaseMapper），仅关键词推荐
 * 因 {@code LIKE + 限额} 走 XML。本类由 starter 的 {@code ToolBackendConfig} 在 {@code tool-backend.mode=jdbc}
 * 时装配（种子数据集中在 {@code customer-work-schema.sql}）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisProductBackend implements ProductBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisProductBackend.class);

    private final ProductMapper productMapper;

    public MybatisProductBackend(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Override
    public Mono<String> queryProduct(String productId) {
        return Mono.fromSupplier(() -> doQueryProduct(productId));
    }

    @Override
    public Mono<String> recommendProducts(String keyword) {
        return Mono.fromSupplier(() -> doRecommend(keyword));
    }

    @Override
    public Mono<String> checkStock(String productId) {
        return Mono.fromSupplier(() -> doCheckStock(productId));
    }

    @Override
    public Mono<String> queryPromotions(String productId) {
        return Mono.fromSupplier(() -> doQueryPromotions(productId));
    }

    private String doQueryProduct(String productId) {
        try {
            ProductDO product = productMapper.selectById(productId);
            if (product == null) {
                return "未查询到商品 " + productId + "，请核对商品编号。";
            }
            return "商品 " + productId + "：" + product.getDescription() + "。";
        } catch (Exception e) {
            log.error("product query failed, code={}, productId={}", "PRODUCT-BACKEND-QUERY-FAIL", productId, e);
            return "商品系统暂时不可用，建议稍后再试。";
        }
    }

    private String doRecommend(String keyword) {
        try {
            List<ProductDO> products = productMapper.recommend(keyword);
            if (products.isEmpty()) {
                return "未找到与「" + keyword + "」相关的在售商品，请换个关键词试试。";
            }
            StringBuilder sb = new StringBuilder("围绕「").append(keyword).append("」为你推荐：");
            for (int i = 0; i < products.size(); i++) {
                sb.append(i + 1).append(") ").append(products.get(i).getName());
                sb.append(i == products.size() - 1 ? "。" : "；");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("product recommend failed, code={}, keyword={}", "PRODUCT-BACKEND-RECOMMEND-FAIL", keyword, e);
            return "商品系统暂时不可用，建议稍后再试。";
        }
    }

    private String doCheckStock(String productId) {
        try {
            ProductDO product = productMapper.selectById(productId);
            if (product == null) {
                return "未查询到商品 " + productId + "，请核对商品编号。";
            }
            int stock = product.getStock();
            String note = stock > 0 ? "现货充足，下单后 24 小时内发货" : "暂时缺货，预计 3 天后补货";
            return "商品 " + productId + "：当前库存 " + stock + " 件（" + note + "）。";
        } catch (Exception e) {
            log.error("product stock failed, code={}, productId={}", "PRODUCT-BACKEND-STOCK-FAIL", productId, e);
            return "库存系统暂时不可用，建议稍后再试。";
        }
    }

    private String doQueryPromotions(String productId) {
        try {
            ProductDO product = productMapper.selectById(productId);
            if (product == null) {
                return "未查询到商品 " + productId + "，请核对商品编号。";
            }
            String promotion = product.getPromotion();
            if (promotion == null || promotion.isBlank()) {
                return "商品 " + productId + " 当前暂无进行中的活动。";
            }
            return "商品 " + productId + " 当前活动：" + promotion + "。";
        } catch (Exception e) {
            log.error("product promotion failed, code={}, productId={}",
                "PRODUCT-BACKEND-PROMOTION-FAIL", productId, e);
            return "营销系统暂时不可用，建议稍后再试。";
        }
    }
}
