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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 订单后端的 JDBC 参考实现：从 {@code cw_order} 表读真实订单，写操作（改址 / 取消 / 催发货）真实 UPDATE。
 *
 * <p>输出文案对齐 {@link MockOrderBackend}，且种子数据包含 Mock 里的示例订单号（20260613001 /
 * 20260613002，状态/金额/下单日期与 Mock 完全一致），保证从 Mock 切到 JDBC 后系统提示词示例连续。
 * 本类<b>不注册为 Spring Bean</b>——由 app 层按 {@code @ConditionalOnProperty} 装配，starter 只提供实现。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcOrderBackend implements OrderBackend {

    private static final Logger log = LoggerFactory.getLogger(JdbcOrderBackend.class);

    private static final String SELECT_SQL =
        "SELECT order_id, status, amount, receiver_addr, logistics_trace, created_at_ms "
        + "FROM cw_order WHERE order_id = ?";

    private final DataSource dataSource;

    public JdbcOrderBackend(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public Mono<String> queryOrder(String orderId) {
        return Mono.fromSupplier(() -> doQueryOrder(orderId));
    }

    @Override
    public Mono<String> queryLogistics(String orderId) {
        return Mono.fromSupplier(() -> doQueryLogistics(orderId));
    }

    @Override
    public Mono<String> modifyAddress(String orderId, String newAddress) {
        return Mono.fromSupplier(() -> doModifyAddress(orderId, newAddress));
    }

    @Override
    public Mono<String> cancelOrder(String orderId, String reason) {
        return Mono.fromSupplier(() -> doCancelOrder(orderId, reason));
    }

    @Override
    public Mono<String> urgeShipment(String orderId) {
        return Mono.fromSupplier(() -> doUrgeShipment(orderId));
    }

    private String doQueryOrder(String orderId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "未查询到订单 " + orderId + "，请用户核对订单号。";
                }
                return String.format("订单 %s：状态=%s，金额=%s 元，下单时间=%s。",
                    orderId, rs.getString("status"), rs.getBigDecimal("amount").toPlainString(),
                    formatDate(rs.getLong("created_at_ms")));
            }
        } catch (Exception e) {
            log.error("order query failed, code={}, orderId={}", "ORDER-BACKEND-QUERY-FAIL", orderId, e);
            return "订单系统暂时不可用，建议稍后再试或转人工。";
        }
    }

    private String doQueryLogistics(String orderId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "未查询到订单 " + orderId + " 的物流信息。";
                }
                String trace = rs.getString("logistics_trace");
                if (trace == null || trace.isBlank()) {
                    return "订单 " + orderId + " 暂无物流轨迹。";
                }
                return "订单 " + orderId + " 物流：" + trace;
            }
        } catch (Exception e) {
            log.error("logistics query failed, code={}, orderId={}", "ORDER-BACKEND-LOGISTICS-FAIL", orderId, e);
            return "物流系统暂时不可用，建议稍后再试。";
        }
    }

    private String doModifyAddress(String orderId, String newAddress) {
        int affected = update("UPDATE cw_order SET receiver_addr = ? WHERE order_id = ?",
            newAddress, orderId, "ORDER-BACKEND-MODIFYADDR-FAIL");
        return affected > 0
            ? "订单 " + orderId + " 收货地址已更新为「" + newAddress + "」，将按新地址派送。"
            : "未查询到订单 " + orderId + "，无法改址，请核对订单号。";
    }

    private String doCancelOrder(String orderId, String reason) {
        int affected = update("UPDATE cw_order SET status = '已取消' WHERE order_id = ?",
            orderId, "ORDER-BACKEND-CANCEL-FAIL");
        return affected > 0
            ? "订单 " + orderId + " 已受理取消申请（原因：" + reason + "）；若已支付，款项原路退回。"
            : "未查询到订单 " + orderId + "，无法取消，请核对订单号。";
    }

    private String doUrgeShipment(String orderId) {
        int affected = update(
            "UPDATE cw_order SET logistics_trace = CONCAT(COALESCE(logistics_trace, ''), '→[已加急]') "
            + "WHERE order_id = ?", orderId, "ORDER-BACKEND-URGE-FAIL");
        return affected > 0
            ? "已为订单 " + orderId + " 提交加急发货标记，仓库将优先处理，预计 24 小时内出库。"
            : "未查询到订单 " + orderId + "，请核对订单号。";
    }

    /** 单参数 UPDATE（order_id）。 */
    private int update(String sql, String orderId, String errorCode) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId);
            return ps.executeUpdate();
        } catch (Exception e) {
            log.error("order update failed, code={}, orderId={}", errorCode, orderId, e);
            return 0;
        }
    }

    /** 双参数 UPDATE（value + order_id）。 */
    private int update(String sql, String value, String orderId, String errorCode) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setString(2, orderId);
            return ps.executeUpdate();
        } catch (Exception e) {
            log.error("order update failed, code={}, orderId={}", errorCode, orderId, e);
            return 0;
        }
    }

    private static String formatDate(long ms) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).toString();
    }

    private static long epochOf(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** 自动建表 + INSERT IGNORE 种子数据（幂等）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_order (
                order_id VARCHAR(32) PRIMARY KEY COMMENT '订单号',
                user_id VARCHAR(64) NOT NULL COMMENT '下单用户',
                product_id VARCHAR(32) NOT NULL COMMENT '商品ID',
                product_name VARCHAR(128) COMMENT '商品名称',
                amount DECIMAL(10,2) NOT NULL COMMENT '订单金额',
                status VARCHAR(32) NOT NULL COMMENT '订单状态',
                receiver_addr VARCHAR(255) COMMENT '收货地址',
                logistics_trace TEXT COMMENT '物流轨迹',
                created_at_ms BIGINT NOT NULL COMMENT '下单时间戳（毫秒）',
                INDEX idx_order_user (user_id, created_at_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单（JDBC 后端演示表）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            seed();
            log.info("order table ready: cw_order");
        } catch (Exception e) {
            log.error("order ensureTable failed, code={}", "ORDER-BACKEND-DDL-FAIL", e);
        }
    }

    /** INSERT IGNORE 种子订单（含 Mock 示例订单 20260613001 / 20260613002，状态/金额/日期与 Mock 一致）。 */
    private void seed() throws SQLException {
        String insert = "INSERT IGNORE INTO cw_order (order_id, user_id, product_id, product_name, amount, "
            + "status, receiver_addr, logistics_trace, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insert)) {
            addSeed(ps, "20260613001", "U-demo-1", "P001", "旗舰款无线降噪耳机", "299.00", "已发货",
                "北京市朝阳区建国路 88 号", "[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。", epochOf(2026, 6, 10));
            addSeed(ps, "20260613002", "U-demo-1", "P003", "商务降噪头戴耳机", "1599.00", "已签收",
                "上海市浦东新区世纪大道 100 号", "[5-18 已揽收]→[5-19 运输中]→[5-20 已签收]。", epochOf(2026, 5, 20));
            addSeed(ps, "20260613003", "U-demo-2", "P002", "运动防汗蓝牙耳机", "199.00", "待发货",
                "广州市天河区体育西路 1 号", "[6-13 已下单，仓库备货中]", epochOf(2026, 6, 13));
            addSeed(ps, "20260613004", "U-demo-2", "P001", "旗舰款无线降噪耳机", "299.00", "已退款",
                "深圳市南山区科技园路 5 号", "[6-08 已揽收]→[6-09 用户取消]→[6-10 已退款]", epochOf(2026, 6, 8));
            ps.executeBatch();
        }
    }

    private void addSeed(PreparedStatement ps, String orderId, String userId, String productId, String productName,
                         String amount, String status, String addr, String trace, long createdAtMs) throws SQLException {
        ps.setString(1, orderId);
        ps.setString(2, userId);
        ps.setString(3, productId);
        ps.setString(4, productName);
        ps.setBigDecimal(5, new java.math.BigDecimal(amount));
        ps.setString(6, status);
        ps.setString(7, addr);
        ps.setString(8, trace);
        ps.setLong(9, createdAtMs);
        ps.addBatch();
    }
}
