package com.richard.fyoung.customeradmin.ops.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGap;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapCategory;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapPriority;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapReviewConflictException;
import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapView;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeGapEvidence;
import com.richard.fyoung.customerwork.infra.migration.V2__ReconcileLegacySchema;
import com.richard.fyoung.customerwork.infra.migration.V9__AddAuditTimestamps;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** 真实迁移、SQL 筛选、租户插件与同库回滚，均使用本测试独占的随机数据库。 */
class KnowledgeGapReviewIntegrationTest {
    @Test
    void reviewKeepsOriginalSignalsAndAuditAtomicAcrossTenantsAndConcurrentEditors() throws Exception {
        var properties = new CustomerWorkDbProperties();
        properties.setHost(System.getenv().getOrDefault("MYSQL_HOST", "localhost"));
        properties.setPort(Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306")));
        properties.setUsername(System.getenv().getOrDefault("MYSQL_USERNAME", "root"));
        properties.setPassword(System.getenv().getOrDefault("MYSQL_PASSWORD", "root"));
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(properties.getHost(), properties.getPort()), 500);
        } catch (Exception unavailable) {
            assumeTrue(false, "MySQL 不可达，跳过复核事务集成测试");
        }
        String database = "gap_review_" + UUID.randomUUID().toString().replace("-", "");
        properties.setDatabase("");
        String serverUrl = properties.jdbcUrl();
        execute(serverUrl, properties, "CREATE DATABASE " + database);
        properties.setDatabase(database);
        var tenant = new AdminTenantProperties();
        tenant.setEnabled(true);
        var provider = new OpsGatewayProvider(properties, new AdminCrossDbTenantPlugins(tenant));
        try {
            Flyway.configure().dataSource(properties.jdbcUrl(), properties.getUsername(), properties.getPassword())
                .locations("classpath:db/customerwork/migration")
                .javaMigrations(new V2__ReconcileLegacySchema(), new V9__AddAuditTimestamps())
                .target("24").load().migrate();
            String clock = KnowledgeGap.hashOf("今天星期几？");
            String question = KnowledgeGap.hashOf("退款申请一直报错");
            String truncatedGreeting = "HELLO" + " ".repeat(495);
            String truncatedHash = KnowledgeGap.hashOf(truncatedGreeting);
            // 证明旧信号能迁移并保留频次，新的工作清单先过滤再 LIMIT。
            execute(properties.jdbcUrl(), properties, "INSERT INTO cw_knowledge_gap "
                + "(tenant_id,scope_id,question_hash,question,miss_count,first_seen_at_ms,last_seen_at_ms) VALUES "
                + "('tenant-a','tenant-a','" + clock + "','今天星期几？',100,1,2),"
                + "('tenant-a','tenant-a','" + question + "','退款申请一直报错',1,1,2),"
                + "('tenant-a','tenant-a','" + truncatedHash + "','" + truncatedGreeting + "',1,1,1)");
            TenantContext.set("tenant-a");
            var gateway = provider.get();
            var store = gateway.knowledgeGap();
            var reviews = gateway.knowledgeGapReview();
            assertEquals(3, store.topGaps("tenant-a", 50).size());
            assertEquals(question, store.topGaps("tenant-a", 1, KnowledgeGapView.WORK).get(0).questionHash());
            assertEquals(KnowledgeGapCategory.REALTIME,
                reviews.find("tenant-a", clock).orElseThrow().classification().category());
            assertEquals(100, reviews.find("tenant-a", clock).orElseThrow().missCount());
            assertEquals(KnowledgeGapCategory.PENDING,
                reviews.find("tenant-a", truncatedHash).orElseThrow().classification().category());
            var reviewed = reviews.review("tenant-a", question, 0, KnowledgeGapCategory.DEPENDENCY,
                KnowledgeGapPriority.HIGH, "支付查询接口返回明确异常，交由依赖负责人处理", "42");
            assertEquals(1, reviewed.classification().revision());
            var latestEvidence = new KnowledgeGapEvidence(KnowledgeGapEvidence.Path.TOOL,
                "invoice-agent", "user-ws", "CHAT", KnowledgeGapEvidence.RetrievalResult.EMPTY);
            store.recordMiss("退款申请一直报错", "tenant-a", 4, latestEvidence);
            assertEquals(KnowledgeGapCategory.DEPENDENCY,
                reviews.find("tenant-a", question).orElseThrow().classification().category());
            assertEquals(2, reviews.find("tenant-a", question).orElseThrow().missCount());
            assertEquals(latestEvidence, reviews.find("tenant-a", question).orElseThrow().evidence());
            var lateEvidence = new KnowledgeGapEvidence(KnowledgeGapEvidence.Path.INJECTION,
                "old-agent", "admin", "CHAT", KnowledgeGapEvidence.RetrievalResult.EMPTY);
            store.recordMiss("退款申请一直报错", "tenant-a", 3, lateEvidence);
            assertEquals(latestEvidence, reviews.find("tenant-a", question).orElseThrow().evidence());
            assertEquals(4, reviews.find("tenant-a", question).orElseThrow().lastSeenAtMs());
            store.recordMiss("退款申请一直报错", "tenant-a", 5);
            assertNull(reviews.find("tenant-a", question).orElseThrow().evidence(),
                "新样本未采集来源时，不能继续冒用之前的来源");
            assertEquals(KnowledgeGapPriority.HIGH,
                reviews.find("tenant-a", question).orElseThrow().classification().priority());
            assertEquals(question, store.topGaps("tenant-a", 1, KnowledgeGapView.ALL).get(0).questionHash());
            assertEquals(1, reviews.history("tenant-a", question, Long.MAX_VALUE).get(0).signalCount());
            assertThrows(KnowledgeGapReviewConflictException.class, () -> reviews.review("tenant-a", question, 0,
                KnowledgeGapCategory.KNOWLEDGE, KnowledgeGapPriority.NORMAL, "过期修改", "99"));

            TenantContext.set("tenant-b");
            assertTrue(reviews.find("tenant-a", question).isEmpty());
            assertTrue(reviews.history("tenant-a", question, Long.MAX_VALUE).isEmpty());
            assertThrows(java.util.NoSuchElementException.class, () -> reviews.review("tenant-a", question, 1,
                KnowledgeGapCategory.KNOWLEDGE, KnowledgeGapPriority.NORMAL, "越租户修改", "99"));
            TenantContext.clear();
            assertThrows(RuntimeException.class, () -> reviews.find("tenant-a", question));

            var executor = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            try {
                java.util.concurrent.Callable<Boolean> update = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return TenantContext.callWith("tenant-a", () -> {
                        try {
                            reviews.review("tenant-a", question, 1, KnowledgeGapCategory.PROCESS,
                                KnowledgeGapPriority.HIGH, "已核对流程处理步骤", "43");
                            return true;
                        } catch (KnowledgeGapReviewConflictException expected) {
                            return false;
                        }
                    });
                };
                var first = executor.submit(update);
                var second = executor.submit(update);
                start.countDown();
                assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
            TenantContext.set("tenant-a");
            var before = reviews.find("tenant-a", question).orElseThrow();
            assertEquals(2, before.classification().revision());
            assertEquals(2, reviews.history("tenant-a", question, Long.MAX_VALUE).size());
            assertEquals(1, reviews.history("tenant-a", question, 2).size());
            // 只有审计表写入失败时，前一条分类 UPDATE 也必须回滚。
            execute(properties.jdbcUrl(), properties, "DROP TABLE cw_knowledge_gap_review");
            assertThrows(RuntimeException.class, () -> reviews.review("tenant-a", question, 2,
                KnowledgeGapCategory.KNOWLEDGE, KnowledgeGapPriority.NORMAL, "不得单独提交分类", "44"));
            assertEquals(before.classification(), reviews.find("tenant-a", question).orElseThrow().classification());
        } finally {
            TenantContext.clear();
            provider.close();
            execute(serverUrl, properties, "DROP DATABASE " + database);
        }
    }

    private void execute(String url, CustomerWorkDbProperties properties, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, properties.getUsername(), properties.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
