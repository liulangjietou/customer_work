package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 会员/账户后端的 JDBC 参考实现：积分/等级从 {@code cw_member} 表读取；账户问题处置沿用固定话术
 * 并将处理记录落库到 {@code cw_member_account_log}。
 *
 * <p>种子会员（U-demo-1 积分 1280 / 黄金会员）与 {@link MockMemberBackend} 数据一致，保证从 Mock 切到
 * JDBC 后系统提示词示例连续。本类<b>不注册为 Spring Bean</b>——由 app 层按 {@code @ConditionalOnProperty} 装配。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcMemberBackend implements MemberBackend {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemberBackend.class);

    /** 积分抵扣比例：100 积分抵 1 元。 */
    private static final BigDecimal POINTS_PER_YUAN = new BigDecimal("100");

    /** 账户问题固定处置话术（与 Mock 一致）。 */
    private static final String ACCOUNT_ISSUE_GUIDE =
        "若无法登录，请尝试『验证码登录』或重置密码；若提示账号异常，可能触发风控，"
      + "请提供注册手机号由人工核验解封。";

    private final DataSource dataSource;

    public JdbcMemberBackend(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public Mono<String> queryPoints(String userId) {
        return Mono.fromSupplier(() -> doQueryPoints(userId));
    }

    @Override
    public Mono<String> queryMemberLevel(String userId) {
        return Mono.fromSupplier(() -> doQueryMemberLevel(userId));
    }

    @Override
    public Mono<String> resolveAccountIssue(String issue) {
        return Mono.fromSupplier(() -> doResolveAccountIssue(issue));
    }

    private String doQueryPoints(String userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT points, points_expiring FROM cw_member WHERE member_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "未查询到会员 " + userId + " 的积分信息，请确认账号是否已注册会员。";
                }
                int points = rs.getInt("points");
                int expiring = rs.getInt("points_expiring");
                String deductible = new BigDecimal(points)
                    .divide(POINTS_PER_YUAN, 2, RoundingMode.HALF_UP).toPlainString();
                String head = "会员 " + userId + " 当前积分 " + points + " 分，可抵扣 " + deductible + " 元；";
                return expiring > 0
                    ? head + "本月底将有 " + expiring + " 分到期，建议尽快使用。"
                    : head + "近期无积分到期。";
            }
        } catch (Exception e) {
            log.error("member points query failed, code={}, userId={}", "MEMBER-BACKEND-POINTS-FAIL", userId, e);
            return "会员系统暂时不可用，建议稍后再试。";
        }
    }

    private String doQueryMemberLevel(String userId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT level, benefits, next_level, upgrade_gap FROM cw_member WHERE member_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "未查询到会员 " + userId + " 的等级信息，请确认账号是否已注册会员。";
                }
                String level = rs.getString("level");
                String benefits = rs.getString("benefits");
                String nextLevel = rs.getString("next_level");
                String gap = rs.getBigDecimal("upgrade_gap").toPlainString();
                return "会员 " + userId + " 等级：" + level + "；权益：" + benefits + "；"
                    + "再消费 " + gap + " 元可升级" + nextLevel + "。";
            }
        } catch (Exception e) {
            log.error("member level query failed, code={}, userId={}", "MEMBER-BACKEND-LEVEL-FAIL", userId, e);
            return "会员系统暂时不可用，建议稍后再试。";
        }
    }

    /** 账户问题：固定话术 + 落一条处理日志（真实落库）。 */
    private String doResolveAccountIssue(String issue) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO cw_member_account_log (issue, handling, created_at_ms) VALUES (?, ?, ?)")) {
            ps.setString(1, issue);
            ps.setString(2, ACCOUNT_ISSUE_GUIDE);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("member account issue log failed, code={}, issue={}", "MEMBER-BACKEND-ACCOUNT-FAIL", issue, e);
        }
        return "关于「" + issue + "」：" + ACCOUNT_ISSUE_GUIDE;
    }

    /** 自动建表 + INSERT IGNORE 种子数据（幂等）。 */
    private void ensureTable() {
        String memberDdl = """
            CREATE TABLE IF NOT EXISTS cw_member (
                member_id VARCHAR(64) PRIMARY KEY COMMENT '会员ID（对应用户ID）',
                level VARCHAR(32) NOT NULL COMMENT '会员等级',
                points INT NOT NULL DEFAULT 0 COMMENT '当前积分',
                points_expiring INT NOT NULL DEFAULT 0 COMMENT '本月底到期积分',
                benefits VARCHAR(255) COMMENT '等级权益',
                next_level VARCHAR(32) COMMENT '下一等级',
                upgrade_gap DECIMAL(10,2) DEFAULT 0 COMMENT '升级所需再消费金额',
                phone VARCHAR(32) COMMENT '注册手机号'
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员（JDBC 后端演示表）'
            """;
        String logDdl = """
            CREATE TABLE IF NOT EXISTS cw_member_account_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '处理日志自增主键',
                issue VARCHAR(255) NOT NULL COMMENT '账户问题描述',
                handling VARCHAR(500) COMMENT '处置话术',
                created_at_ms BIGINT NOT NULL COMMENT '创建时间戳（毫秒）',
                INDEX idx_account_log_created (created_at_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员账户问题处理日志（JDBC 后端演示表）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(memberDdl);
            stmt.execute(logDdl);
            seed();
            log.info("member tables ready: cw_member, cw_member_account_log");
        } catch (Exception e) {
            log.error("member ensureTable failed, code={}", "MEMBER-BACKEND-DDL-FAIL", e);
        }
    }

    /** INSERT IGNORE 种子会员（U-demo-1 与 Mock 数据一致：黄金会员 / 积分 1280 / 到期 200 / 差 500 升铂金）。 */
    private void seed() throws SQLException {
        String insert = "INSERT IGNORE INTO cw_member (member_id, level, points, points_expiring, benefits, "
            + "next_level, upgrade_gap, phone) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insert)) {
            addSeed(ps, "U-demo-1", "黄金会员", 1280, 200, "免运费、专属客服、生日双倍积分",
                "铂金", "500.00", "138****0001");
            addSeed(ps, "U-demo-2", "白银会员", 320, 0, "满额包邮、积分商城兑换",
                "黄金", "800.00", "139****0002");
            ps.executeBatch();
        }
    }

    private void addSeed(PreparedStatement ps, String memberId, String level, int points, int expiring,
                         String benefits, String nextLevel, String upgradeGap, String phone) throws SQLException {
        ps.setString(1, memberId);
        ps.setString(2, level);
        ps.setInt(3, points);
        ps.setInt(4, expiring);
        ps.setString(5, benefits);
        ps.setString(6, nextLevel);
        ps.setBigDecimal(7, new BigDecimal(upgradeGap));
        ps.setString(8, phone);
        ps.addBatch();
    }
}
