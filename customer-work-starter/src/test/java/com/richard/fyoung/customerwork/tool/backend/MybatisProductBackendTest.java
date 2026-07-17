package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.tool.backend.mapper.ProductMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis 商品后端测试（对接本机 MySQL；不可达自动跳过）：种子文案断言（描述/推荐/库存/优惠）。
 *
 * <p>P001 描述与 {@link MockProductBackend} 一致；P003 库存 0、无优惠，验证缺货 / 无活动分支。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisProductBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private MybatisProductBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-product-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        backend = new MybatisProductBackend(MybatisTestSupport.mapper(dataSource, ProductMapper.class));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
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

    @Test
    void queryProduct_seeded_shouldMatchMockDescription() {
        String result = backend.queryProduct("P001").block();
        assertTrue(result.contains("旗舰款无线降噪耳机"), "应含 Mock 一致的商品描述");
        assertTrue(result.startsWith("商品 P001："));
    }

    @Test
    void recommendProducts_byKeyword_shouldListMatches() {
        String result = backend.recommendProducts("耳机").block();
        assertTrue(result.contains("为你推荐"));
        assertTrue(result.contains("1)"));
    }

    @Test
    void checkStock_inStock_shouldReportAvailable() {
        String result = backend.checkStock("P001").block();
        assertTrue(result.contains("库存 100 件"));
        assertTrue(result.contains("现货充足"));
    }

    @Test
    void checkStock_outOfStock_shouldReportRestock() {
        String result = backend.checkStock("P003").block();
        assertTrue(result.contains("暂时缺货"), "P003 库存为 0");
    }

    @Test
    void queryPromotions_withPromotion_shouldListActivity() {
        String result = backend.queryPromotions("P001").block();
        assertTrue(result.contains("满 300 减 50"));
    }

    @Test
    void queryPromotions_withoutPromotion_shouldReportNone() {
        String result = backend.queryPromotions("P003").block();
        assertTrue(result.contains("暂无进行中的活动"), "P003 无优惠活动");
    }

    @Test
    void queryProduct_unknown_shouldReturnNotFound() {
        String result = backend.queryProduct("P999").block();
        assertTrue(result.contains("未查询到商品"));
    }
}
