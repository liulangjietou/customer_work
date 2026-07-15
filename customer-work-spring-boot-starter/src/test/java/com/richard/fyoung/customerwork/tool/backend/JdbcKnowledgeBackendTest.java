package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JDBC 知识库后端测试（对接本机 MySQL；不可达自动跳过）：LIKE 关键词召回、来源标注格式、未命中文案。
 *
 * <p>种子三条 FAQ 与 {@link MockKnowledgeBackend} 一致，纯查询用例，不产生写入。</p>
 * @author owlzhangfq@gmail.com
 */
class JdbcKnowledgeBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private DataSource dataSource;
    private JdbcKnowledgeBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = buildDataSource();
        backend = new JdbcKnowledgeBackend(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private DataSource buildDataSource() {
        CustomerWorkProperties.Session.Mysql cfg = new CustomerWorkProperties().getSession().getMysql();
        cfg.setDatabase("agent_scope_customer_work");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(cfg.resolveJdbcUrl());
        ds.setUsername("root");
        ds.setPassword("root");
        ds.setMaximumPoolSize(3);
        ds.setPoolName("test-knowledge-pool");
        return ds;
    }

    @Test
    void searchKnowledge_returnHit_shouldMatchMockFormat() {
        String result = backend.searchKnowledge("退货").block();
        assertTrue(result.startsWith("知识库召回如下："), "应对齐 Mock 召回前缀");
        assertTrue(result.contains("七天无理由退货"), "应命中退货 FAQ");
        assertTrue(result.contains("（来源：《售后服务政策》第 3 条）"), "应带来源标注");
    }

    @Test
    void searchKnowledge_invoiceKeyword_shouldHitInvoiceFaq() {
        String result = backend.searchKnowledge("发票").block();
        assertTrue(result.contains("电子普通发票"), "应命中发票 FAQ");
    }

    @Test
    void searchKnowledge_freightKeyword_shouldHitFreightFaq() {
        String result = backend.searchKnowledge("运费").block();
        assertTrue(result.contains("满 99 元包邮"), "应命中运费 FAQ");
    }

    @Test
    void searchKnowledge_noHit_shouldReturnFallback() {
        String result = backend.searchKnowledge("量子纠缠售后政策").block();
        assertTrue(result.contains("未在知识库中检索到直接相关条目"));
    }
}
