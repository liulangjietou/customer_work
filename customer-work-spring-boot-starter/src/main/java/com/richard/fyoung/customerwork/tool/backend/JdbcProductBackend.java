package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品/售前后端的 JDBC 参考实现：从 {@code cw_product} 表读真实商品数据。
 *
 * <p>输出文案对齐 {@link MockProductBackend}；种子数据含旗舰降噪耳机（与 Mock 商品描述一致），保证从
 * Mock 切到 JDBC 后售前话术连续。本类<b>不注册为 Spring Bean</b>——由 app 层按 {@code @ConditionalOnProperty}
 * 装配，starter 只提供实现。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcProductBackend implements ProductBackend {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductBackend.class);

    private static final int RECOMMEND_LIMIT = 3;

    private final DataSource dataSource;

    public JdbcProductBackend(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT description FROM cw_product WHERE product_id = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "未查询到商品 " + productId + "，请核对商品编号。";
                }
                return "商品 " + productId + "：" + rs.getString("description") + "。";
            }
        } catch (Exception e) {
            log.error("product query failed, code={}, productId={}", "PRODUCT-BACKEND-QUERY-FAIL", productId, e);
            return "商品系统暂时不可用，建议稍后再试。";
        }
    }

    private String doRecommend(String keyword) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name FROM cw_product WHERE status = 'ON_SALE' AND (name LIKE ? OR category LIKE ?) "
                 + "ORDER BY stock DESC LIMIT " + RECOMMEND_LIMIT)) {
            String like = "%" + keyword + "%";
            ps.setString(1, like);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> names = new ArrayList<>();
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
                if (names.isEmpty()) {
                    return "未找到与「" + keyword + "」相关的在售商品，请换个关键词试试。";
                }
                StringBuilder sb = new StringBuilder("围绕「").append(keyword).append("」为你推荐：");
                for (int i = 0; i < names.size(); i++) {
                    sb.append(i + 1).append(") ").append(names.get(i));
                    sb.append(i == names.size() - 1 ? "。" : "；");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.error("product recommend failed, code={}, keyword={}", "PRODUCT-BACKEND-RECOMMEND-FAIL", keyword, e);
            return "商品系统暂时不可用，建议稍后再试。";
        }
    }

    private String doCheckStock(String productId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT stock FROM cw_product WHERE product_id = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "未查询到商品 " + productId + "，请核对商品编号。";
                }
                int stock = rs.getInt("stock");
                String note = stock > 0 ? "现货充足，下单后 24 小时内发货" : "暂时缺货，预计 3 天后补货";
                return "商品 " + productId + "：当前库存 " + stock + " 件（" + note + "）。";
            }
        } catch (Exception e) {
            log.error("product stock failed, code={}, productId={}", "PRODUCT-BACKEND-STOCK-FAIL", productId, e);
            return "库存系统暂时不可用，建议稍后再试。";
        }
    }

    private String doQueryPromotions(String productId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT promotion FROM cw_product WHERE product_id = ?")) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "未查询到商品 " + productId + "，请核对商品编号。";
                }
                String promotion = rs.getString("promotion");
                if (promotion == null || promotion.isBlank()) {
                    return "商品 " + productId + " 当前暂无进行中的活动。";
                }
                return "商品 " + productId + " 当前活动：" + promotion + "。";
            }
        } catch (Exception e) {
            log.error("product promotion failed, code={}, productId={}",
                "PRODUCT-BACKEND-PROMOTION-FAIL", productId, e);
            return "营销系统暂时不可用，建议稍后再试。";
        }
    }

    /** 自动建表 + INSERT IGNORE 种子数据（幂等）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_product (
                product_id VARCHAR(32) PRIMARY KEY COMMENT '商品ID',
                name VARCHAR(128) NOT NULL COMMENT '商品名称',
                category VARCHAR(64) COMMENT '品类',
                price DECIMAL(10,2) NOT NULL COMMENT '价格',
                stock INT NOT NULL DEFAULT 0 COMMENT '库存',
                description VARCHAR(500) COMMENT '商品描述',
                promotion VARCHAR(255) COMMENT '优惠活动',
                status VARCHAR(16) NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE/OFF_SALE',
                INDEX idx_product_category (category)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品（JDBC 后端演示表）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            seed();
            log.info("product table ready: cw_product");
        } catch (Exception e) {
            log.error("product ensureTable failed, code={}", "PRODUCT-BACKEND-DDL-FAIL", e);
        }
    }

    /** INSERT IGNORE 种子商品（P001 描述与 Mock queryProduct 一致，保证售前话术连续）。 */
    private void seed() throws SQLException {
        String insert = "INSERT IGNORE INTO cw_product (product_id, name, category, price, stock, "
            + "description, promotion, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insert)) {
            addSeed(ps, "P001", "旗舰款无线降噪耳机", "耳机", "299.00", 100,
                "旗舰款无线降噪耳机，蓝牙 5.3，续航 30 小时，支持多点连接，颜色 黑/白，质保 1 年",
                "满 300 减 50；可叠加新人券 20 元；下单送收纳包");
            addSeed(ps, "P002", "运动防汗蓝牙耳机", "耳机", "199.00", 50,
                "运动防汗蓝牙耳机，IPX5 级防水，佩戴稳固，适合健身运动", "限时直降 30 元，晒单再返 10 元");
            addSeed(ps, "P003", "商务降噪头戴耳机", "耳机", "599.00", 0,
                "商务降噪头戴耳机，主动降噪，麦克风通话清晰，续航 40 小时", "");
            ps.executeBatch();
        }
    }

    private void addSeed(PreparedStatement ps, String productId, String name, String category, String price,
                         int stock, String description, String promotion) throws SQLException {
        ps.setString(1, productId);
        ps.setString(2, name);
        ps.setString(3, category);
        ps.setBigDecimal(4, new java.math.BigDecimal(price));
        ps.setInt(5, stock);
        ps.setString(6, description);
        ps.setString(7, promotion);
        ps.setString(8, "ON_SALE");
        ps.addBatch();
    }
}
