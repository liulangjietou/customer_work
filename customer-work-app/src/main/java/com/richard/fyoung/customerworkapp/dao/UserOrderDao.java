package com.richard.fyoung.customerworkapp.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户订单查询 Dao：基于 {@code cw_order} 表读当前用户订单（列表 / 详情）。
 *
 * <p>数据源由 {@code JdbcBackendConfig} 在 {@code tool-backend.mode=jdbc} 时以 {@code @Bean} 暴露，
 * 本 Dao 通过 {@link ObjectProvider} 注入并在 Bean 不存在时优雅降级（{@link #isEnabled()} 返回 false，
 * 控制器据此返回 503）——这样 mode!=jdbc 的部署下订单接口语义化不可用，而非启动即失败。</p>
 * @author owlzhangfq@gmail.com
 */
@Repository
public class UserOrderDao {

    private static final Logger log = LoggerFactory.getLogger(UserOrderDao.class);

    /** 列表查询不取 logistics_trace（列表页无需物流轨迹，减少行传输）。 */
    private static final String LIST_SQL =
        "SELECT order_id, product_id, product_name, amount, status, receiver_addr, created_at_ms "
        + "FROM cw_order WHERE user_id = ? ORDER BY created_at_ms DESC";

    /** 详情查询含 user_id（供归属校验）与 logistics_trace。 */
    private static final String DETAIL_SQL =
        "SELECT user_id, order_id, product_id, product_name, amount, status, receiver_addr, "
        + "logistics_trace, created_at_ms FROM cw_order WHERE order_id = ?";

    private final DataSource dataSource;

    public UserOrderDao(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSource = dataSourceProvider.getIfAvailable();
    }

    /** 订单数据源是否已启用（mode=jdbc 才有）。 */
    public boolean isEnabled() {
        return dataSource != null;
    }

    /** 当前用户全部订单（按下单时间倒序）；列表页 {@code logisticsTrace} 恒为 null（不查）。 */
    public List<OrderView> listByUser(String userId) {
        List<OrderView> orders = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(LIST_SQL)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    orders.add(new OrderView(
                        rs.getString("order_id"),
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        formatAmount(rs.getBigDecimal("amount")),
                        rs.getString("status"),
                        rs.getString("receiver_addr"),
                        null,
                        rs.getLong("created_at_ms")));
                }
            }
        } catch (Exception e) {
            log.error("user order list query failed, code={}, userId={}", "USER-ORDER-LIST-FAIL", userId, e);
            throw new IllegalStateException("order query failed", e);
        }
        return orders;
    }

    /** 按订单号查详情（含物流轨迹与归属 userId，供控制器判定 403/404）。 */
    public Optional<OwnedOrder> findById(String orderId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DETAIL_SQL)) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                OrderView view = new OrderView(
                    rs.getString("order_id"),
                    rs.getString("product_id"),
                    rs.getString("product_name"),
                    formatAmount(rs.getBigDecimal("amount")),
                    rs.getString("status"),
                    rs.getString("receiver_addr"),
                    rs.getString("logistics_trace"),
                    rs.getLong("created_at_ms"));
                return Optional.of(new OwnedOrder(rs.getString("user_id"), view));
            }
        } catch (Exception e) {
            log.error("user order detail query failed, code={}, orderId={}", "USER-ORDER-DETAIL-FAIL", orderId, e);
            throw new IllegalStateException("order query failed", e);
        }
    }

    /** 金额统一格式化为两位小数字符串（对外契约固定精度，避免前端二次处理）。 */
    private static String formatAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 订单视图（对外契约结构）。
     *
     * @param orderId      订单号
     * @param productId    商品ID
     * @param productName  商品名称
     * @param amount       金额（两位小数字符串）
     * @param status       订单状态
     * @param receiverAddr 收货地址
     * @param logisticsTrace 物流轨迹（列表页为 null，仅详情返回）
     * @param createdAtMs  下单时间戳（毫秒）
     */
    public record OrderView(String orderId, String productId, String productName, String amount,
                            String status, String receiverAddr, String logisticsTrace, Long createdAtMs) {
    }

    /** 订单归属 + 视图（详情查询单次返回，供控制器做 403/404 判定，userId 不外泄给前端）。 */
    public record OwnedOrder(String userId, OrderView view) {
    }
}
