package com.richard.fyoung.customerworkapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 演示订单播种：新用户注册成功后，为其插入 2 笔演示订单（一笔已发货带物流轨迹、一笔待发货）。
 *
 * <p><b>演示语义</b>：让新注册用户在订单页立即有数据可看，商品名/价格取自 {@code cw_product} 的
 * P001/P002。<b>生产接入真实订单系统时应移除本类及其注册调用点。</b></p>
 *
 * <p>数据源经 {@link ObjectProvider} 注入，mode!=jdbc（Bean 不存在）时静默跳过（info 日志）。播种失败
 * 只 {@code log.error}（错误码 DEMO-ORDER-SEED-FAIL）绝不抛出——注册主流程不受影响，本类是唯一防御点。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class DemoOrderSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoOrderSeeder.class);

    private static final String SEED_ERROR_CODE = "DEMO-ORDER-SEED-FAIL";
    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int RANDOM_SUFFIX_BOUND = 100000;
    private static final int RANDOM_SUFFIX_WIDTH = 5;
    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    private static final String PRODUCT_SHIPPED = "P001";
    private static final String PRODUCT_PENDING = "P002";
    private static final String STATUS_SHIPPED = "已发货";
    private static final String STATUS_PENDING = "待发货";
    private static final String DEMO_ADDR = "北京市海淀区中关村大街 1 号（演示地址）";
    private static final String SHIPPED_TRACE = "[已揽收]→[到达分拨中心]→[派送中]";
    private static final BigDecimal FALLBACK_PRICE = new BigDecimal("0.00");

    private static final String SELECT_PRODUCT_SQL = "SELECT name, price FROM cw_product WHERE product_id = ?";
    private static final String INSERT_ORDER_SQL =
        "INSERT INTO cw_order (order_id, user_id, product_id, product_name, amount, status, "
        + "receiver_addr, logistics_trace, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final DataSource dataSource;

    public DemoOrderSeeder(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSource = dataSourceProvider.getIfAvailable();
    }

    /**
     * 为新用户播种 2 笔演示订单。失败绝不抛出（只 error 日志），不影响注册主流程。
     *
     * @param userId 新注册用户 ID
     */
    public void seedForNewUser(String userId) {
        if (dataSource == null) {
            log.info("demo order seeding skipped (order datasource disabled), userId={}", userId);
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            long now = System.currentTimeMillis();
            insertOrder(conn, userId, PRODUCT_SHIPPED, STATUS_SHIPPED, SHIPPED_TRACE, now - 2 * ONE_DAY_MS);
            insertOrder(conn, userId, PRODUCT_PENDING, STATUS_PENDING, null, now);
            log.info("demo orders seeded for new user, userId={}", userId);
        } catch (Exception e) {
            log.error("demo order seeding failed, code={}, userId={}", SEED_ERROR_CODE, userId, e);
        }
    }

    /** 插入一笔演示订单：订单号冲突时重试一次（yyyyMMdd + 5 位随机数字，重复概率极低）。 */
    private void insertOrder(Connection conn, String userId, String productId, String status,
                             String trace, long createdAtMs) throws Exception {
        Product product = loadProduct(conn, productId);
        try {
            doInsert(conn, generateOrderNo(), userId, productId, product, status, trace, createdAtMs);
        } catch (Exception first) {
            // 唯一冲突或瞬时异常：重试一次（换新单号）
            log.info("demo order insert retry after conflict, userId={}, productId={}", userId, productId);
            doInsert(conn, generateOrderNo(), userId, productId, product, status, trace, createdAtMs);
        }
    }

    private void doInsert(Connection conn, String orderNo, String userId, String productId, Product product,
                          String status, String trace, long createdAtMs) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_ORDER_SQL)) {
            ps.setString(1, orderNo);
            ps.setString(2, userId);
            ps.setString(3, productId);
            ps.setString(4, product.name());
            ps.setBigDecimal(5, product.price());
            ps.setString(6, status);
            ps.setString(7, DEMO_ADDR);
            ps.setString(8, trace);
            ps.setLong(9, createdAtMs);
            ps.executeUpdate();
        }
    }

    /** 从 cw_product 读商品名/价格；缺失时回退到 productId + 0.00（jdbc 模式下 P001/P002 恒被 starter 播种）。 */
    private Product loadProduct(Connection conn, String productId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_PRODUCT_SQL)) {
            ps.setString(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal price = rs.getBigDecimal("price");
                    return new Product(rs.getString("name"), price == null ? FALLBACK_PRICE : price);
                }
            }
        }
        return new Product(productId, FALLBACK_PRICE);
    }

    /** 生成类订单号：yyyyMMdd + 5 位补零随机数字。 */
    private static String generateOrderNo() {
        String date = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC).format(ORDER_NO_DATE);
        int suffix = ThreadLocalRandom.current().nextInt(RANDOM_SUFFIX_BOUND);
        return date + String.format("%0" + RANDOM_SUFFIX_WIDTH + "d", suffix);
    }

    /** 商品名/价格投影。 */
    private record Product(String name, BigDecimal price) {
    }
}
