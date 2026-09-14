package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantInterceptors;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 在自有 MySQL 库比较正式与候选检索，证明相同召回规则、无正式写入、精确租户及冻结语料。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeSnapshotIntegrationTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "root");
    private final String database = "cw_faq_snapshot_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper json = new ObjectMapper();
    private boolean created;
    private HikariDataSource source;
    private JdbcTemplate jdbc;
    private KnowledgeMapper mapper;

    @BeforeAll
    void open() throws Exception {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 1000); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，跳过 FAQ 快照集成测试"); }
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            created = true;
        }
        source = new HikariDataSource();
        source.setJdbcUrl(url(database)); source.setUsername(USER); source.setPassword(PASSWORD);
        source.setMaximumPoolSize(2);
        MybatisTestSupport.ensureSchema(source);
        jdbc = new JdbcTemplate(source);
        mapper = MybatisTestSupport.mapper(source, KnowledgeMapper.class);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM cw_knowledge");
        seed("TenantA", 11, "开票,CAFE", "电子发票", "发票规则与 Café 说明");
        seed("TenantA", 23, "退款", "售后政策", "七天内可申请退货");
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @AfterAll
    void close() throws Exception {
        if (source != null) source.close();
        if (!created) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) { statement.execute("DROP DATABASE " + database); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotScopeRemainsExactRegardlessOfTenantPlugin(boolean pluginEnabled) throws Exception {
        seed("tenanta", 30, "仅小写租户", "隔离记录", "不能泄露");
        var template = MybatisTestSupport.template(source);
        if (pluginEnabled) template.getConfiguration().getInterceptors().stream()
            .filter(MybatisPlusInterceptor.class::isInstance).map(MybatisPlusInterceptor.class::cast)
            .forEach(plugin -> plugin.addInnerInterceptor(TenantInterceptors.build()));
        var scoped = template.getMapper(KnowledgeMapper.class);
        TenantContext.set("different-context");
        var entries = scoped.snapshotForTenant("TenantA");
        assertEquals(List.of(11L, 23L), entries.stream().map(KnowledgeDO::getId).toList());
        assertEquals(List.of(30L), scoped.snapshotForTenant("tenanta").stream().map(KnowledgeDO::getId).toList());
        assertEquals(List.of(), scoped.searchSnapshot("不能泄露", json.writeValueAsString(entries)));
        assertEquals(11L, scoped.searchSnapshot("发票", json.writeValueAsString(entries)).get(0).getId());
        TenantContext.set("TenantA");
        assertEquals(11L, scoped.search("发票").get(0).getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"发票", "CAFE", "café", "七天", "%", "_", "没有匹配", "' OR 1=1 --"})
    void frozenAndFormalSearchUseIdenticalMatchingAndFormatting(String query) throws Exception {
        String snapshot = json.writeValueAsString(mapper.snapshotForTenant("TenantA"));
        assertEquals(mapper.search(query), mapper.searchSnapshot(query, snapshot));
        assertEquals(new MybatisKnowledgeBackend(mapper).searchKnowledge(query).block(),
            new SnapshotKnowledgeBackend(mapper, snapshot).searchKnowledge(query).block());
    }

    @Test
    void candidateParticipatesInRecallWithoutWritingFormalRows() throws Exception {
        var rows = new ArrayList<>(mapper.snapshotForTenant("TenantA"));
        rows.add(candidate(24));
        var backend = new SnapshotKnowledgeBackend(mapper, json.writeValueAsString(rows));
        assertEquals(KnowledgeBackend.NO_HIT_REPLY, backend.searchKnowledge("不可命中的问题").block());
        assertFalse(backend.recalled(24));
        assertTrue(backend.searchKnowledge("纸质票").block().contains("候选政策正文"));
        assertTrue(backend.recalled(24));
        backend.requireSuccessfulRetrieval();
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM cw_knowledge", Integer.class));
        assertTrue(mapper.search("纸质票").isEmpty());
    }

    @Test
    void candidateIsExcludedWhenFiveEarlierMatchingRowsFillTheRecallLimit() throws Exception {
        for (int i = 1; i <= 6; i++) seed("TenantA", 100 + i, "纸质票", "旧规则" + i, "旧政策" + i);
        var rows = new ArrayList<>(mapper.snapshotForTenant("TenantA"));
        rows.add(candidate(107));
        var backend = new SnapshotKnowledgeBackend(mapper, json.writeValueAsString(rows));
        String reply = backend.searchKnowledge("纸质票").block();
        assertFalse(backend.recalled(107));
        assertFalse(reply.contains("候选政策正文"));
        assertEquals(5, mapper.searchSnapshot("纸质票", json.writeValueAsString(rows)).size());
        assertEquals(List.of(101L, 102L, 103L, 104L, 105L),
            mapper.search("纸质票").stream().map(KnowledgeDO::getId).toList());
    }

    @Test
    void formalEditsAfterFreezeDoNotChangeTheTestedKnowledge() throws Exception {
        var backend = new SnapshotKnowledgeBackend(mapper, json.writeValueAsString(mapper.snapshotForTenant("TenantA")));
        jdbc.update("UPDATE cw_knowledge SET content='已修改的规则' WHERE id=11");
        assertTrue(backend.searchKnowledge("发票").block().contains("发票规则与 Café 说明"));
        assertFalse(backend.searchKnowledge("发票").block().contains("已修改的规则"));
    }

    @Test
    void snapshotSqlPreservesChineseAndEmojiAtTheCandidateCapacityLimit() throws Exception {
        for (String content : List.of("内".repeat(20000), "😀".repeat(10000))) {
            var entry = candidate(24); entry.setContent(content);
            String snapshot = json.writeValueAsString(List.of(entry));
            assertEquals(content, mapper.searchSnapshot("纸质票", snapshot).get(0).getContent());
        }
    }

    @Test
    void malformedSnapshotFailureCannotBeHiddenByToolErrorRecovery() {
        var backend = new SnapshotKnowledgeBackend(mapper, "not-json");
        assertThrows(RuntimeException.class, () -> backend.searchKnowledge("发票").block());
        assertThrows(IllegalStateException.class, backend::requireSuccessfulRetrieval);
        assertFalse(backend.recalled(11));
    }

    private KnowledgeDO candidate(long id) {
        var row = new KnowledgeDO(); row.setId(id); row.setKeyword("纸质票"); row.setTitle("候选政策");
        row.setContent("候选政策正文"); row.setSource("候选版本 1"); return row;
    }

    private void seed(String tenant, long id, String keyword, String title, String content) {
        jdbc.update("INSERT INTO cw_knowledge(tenant_id,id,keyword,title,content,source) VALUES(?,?,?,?,?,?)",
            tenant, id, keyword, title, content, "已发布规则");
    }

    private String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    }
}
