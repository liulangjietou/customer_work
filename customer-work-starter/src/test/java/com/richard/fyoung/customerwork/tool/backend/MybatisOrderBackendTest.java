package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.NoSuchElementException;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis 订单后端测试（对接本机 MySQL；不可达自动跳过）：种子文案断言 + modifyAddress 真实落库。
 *
 * <p>认证主体与正式迁移种子一致；保留查询字段契约，拒绝结果通过 Mono 错误信号返回。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisOrderBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private OrderMapper orderMapper;
    private MybatisOrderBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-order-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        orderMapper = MybatisTestSupport.mapper(dataSource, OrderMapper.class);
        backend = new MybatisOrderBackend(orderMapper);
        authenticate("U-demo-1");
    }

    @AfterEach
    void tearDown() {
        AgentInvocationIdentityContext.clear();
        TenantContext.clear();
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
    void queryOrder_seededDemoOrder_shouldMatchMockText() {
        String result = backend.queryOrder("20260613001").block();
        assertEquals("订单 20260613001：状态=已发货，金额=299.00 元，下单时间=2026-06-10。", result);
    }

    @Test
    void queryLogistics_seededDemoOrder_shouldReturnTrace() {
        String result = backend.queryLogistics("20260613001").block();
        assertEquals("订单 20260613001 物流：[6-11 已揽收]→[6-12 到达分拨中心]→[6-13 派送中]。", result);
    }

    @Test
    void queryOrder_unknownOrder_shouldReturnErrorSignal() {
        assertThrows(NoSuchElementException.class, () -> backend.queryOrder("99999999999").block());
    }

    @Test
    void modifyAddress_shouldReallyPersist() {
        authenticate("U-demo-2");
        // 不污染种子订单：改址前记录原值，断言后恢复（种子数据同时被演示环境使用）
        String orderId = "20260613003";
        String originalAddr = readAddr(orderId);
        String newAddr = "验证地址-" + UUID.randomUUID();
        try {
            String result = backend.modifyAddress(orderId, newAddr).block();
            assertTrue(result.contains("已更新为「" + newAddr + "」"), "应返回更新成功文案");
            assertEquals(newAddr, readAddr(orderId), "收货地址应真实落库");
        } finally {
            if (originalAddr != null) {
                backend.modifyAddress(orderId, originalAddr).block();
            }
        }
    }

    private void authenticate(String userId) {
        TenantContext.set(TenantContext.DEFAULT);
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(TenantContext.DEFAULT, QuotaSubjectType.USER, userId, true));
    }

    private String readAddr(String orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        return order == null ? null : order.getReceiverAddr();
    }
}
