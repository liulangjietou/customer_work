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
 * 知识库后端的 JDBC 参考实现：FAQ 条目落库到 {@code cw_knowledge}，检索用 LIKE 关键词匹配
 * （keyword / title / content 任一命中即召回）。
 *
 * <p>种子导入 {@link MockKnowledgeBackend} 的全部 FAQ（退货/发票/运费），输出带来源标注的格式与 Mock 一致，
 * 保证从 Mock 切到 JDBC 后系统提示词示例连续。本类<b>不注册为 Spring Bean</b>——由 app 层按
 * {@code @ConditionalOnProperty} 装配。</p>
 * @author owlzhangfq@gmail.com
 */
public class JdbcKnowledgeBackend implements KnowledgeBackend {

    private static final Logger log = LoggerFactory.getLogger(JdbcKnowledgeBackend.class);

    private static final int RECALL_LIMIT = 5;

    private final DataSource dataSource;

    public JdbcKnowledgeBackend(DataSource dataSource) {
        this.dataSource = dataSource;
        ensureTable();
    }

    @Override
    public Mono<String> searchKnowledge(String query) {
        return Mono.fromSupplier(() -> doSearch(query));
    }

    private String doSearch(String query) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT content, source FROM cw_knowledge "
                 + "WHERE keyword LIKE ? OR title LIKE ? OR content LIKE ? LIMIT " + RECALL_LIMIT)) {
            String like = "%" + query + "%";
            ps.setString(1, like);
            ps.setString(2, like);
            ps.setString(3, like);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> hits = new ArrayList<>();
                while (rs.next()) {
                    hits.add("· " + rs.getString("content") + "（来源：" + rs.getString("source") + "）");
                }
                if (hits.isEmpty()) {
                    return "未在知识库中检索到直接相关条目，建议结合上下文回答或转人工。";
                }
                return "知识库召回如下：\n" + String.join("\n", hits);
            }
        } catch (Exception e) {
            log.error("knowledge search failed, code={}, query={}", "KNOWLEDGE-BACKEND-SEARCH-FAIL", query, e);
            return "知识库检索暂时不可用。";
        }
    }

    /** 自动建表 + INSERT IGNORE 种子数据（幂等）。 */
    private void ensureTable() {
        String ddl = """
            CREATE TABLE IF NOT EXISTS cw_knowledge (
                id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识条目自增主键',
                keyword VARCHAR(255) NOT NULL COMMENT '命中关键词（逗号分隔）',
                title VARCHAR(255) NOT NULL COMMENT '条目标题',
                content TEXT NOT NULL COMMENT '条目内容',
                source VARCHAR(255) COMMENT '来源标注',
                UNIQUE KEY uk_knowledge_title (title)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库 FAQ（JDBC 后端演示表）'
            """;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            seed();
            log.info("knowledge table ready: cw_knowledge");
        } catch (Exception e) {
            log.error("knowledge ensureTable failed, code={}", "KNOWLEDGE-BACKEND-DDL-FAIL", e);
        }
    }

    /** INSERT IGNORE 种子 FAQ（与 Mock 三条一致：退货/发票/运费）。 */
    private void seed() throws SQLException {
        String insert = "INSERT IGNORE INTO cw_knowledge (keyword, title, content, source) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insert)) {
            addSeed(ps, "退货,退款,七天,无理由", "七天无理由退货政策",
                "支持七天无理由退货，商品需保持完好、不影响二次销售；定制类、生鲜类除外。",
                "《售后服务政策》第 3 条");
            addSeed(ps, "发票,开票,报销", "发票开具规则",
                "支持开具电子普通发票与增值税专用发票，可在订单详情页自助申请，1-3 个工作日开具。",
                "《发票管理规则》第 1 条");
            addSeed(ps, "运费,包邮,邮费", "运费说明",
                "单笔订单满 99 元包邮，偏远地区除外；退货运费由责任方承担。",
                "《运费说明》第 2 条");
            ps.executeBatch();
        }
    }

    private void addSeed(PreparedStatement ps, String keyword, String title, String content, String source)
            throws SQLException {
        ps.setString(1, keyword);
        ps.setString(2, title);
        ps.setString(3, content);
        ps.setString(4, source);
        ps.addBatch();
    }
}
