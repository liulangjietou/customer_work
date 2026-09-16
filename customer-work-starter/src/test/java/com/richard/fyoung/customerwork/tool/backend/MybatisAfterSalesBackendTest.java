package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.entity.InvoiceRequestDO;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis 售后后端测试（对接本机 MySQL；不可达自动跳过）：退款资格联查 cw_order、工单落库往返、种子进度查询。
 *
 * <p>写操作先创建本用户的随机订单；用例清理申请和订单。运行时必须用 CUSTOMER_WORK_TEST_DATABASE
 * 指定独占库，种子读取与 MyBatis 装配仍使用正式迁移。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisAfterSalesBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;
    private static final String USER_ID = "U-demo-2";

    private HikariDataSource dataSource;
    private RefundMapper refundMapper;
    private InvoiceRequestMapper invoiceRequestMapper;
    private MybatisAfterSalesBackend backend;
    private JdbcTemplate jdbc;
    private final List<String> createdOrders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-aftersales-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        refundMapper = MybatisTestSupport.mapper(dataSource, RefundMapper.class);
        invoiceRequestMapper = MybatisTestSupport.mapper(dataSource, InvoiceRequestMapper.class);
        OrderMapper orderMapper = MybatisTestSupport.mapper(dataSource, OrderMapper.class);
        backend = new MybatisAfterSalesBackend(refundMapper, invoiceRequestMapper, orderMapper);
        jdbc = new JdbcTemplate(dataSource);
        TenantContext.set(TenantContext.DEFAULT);
        authenticate(USER_ID);
    }

    @AfterEach
    void tearDown() {
        try {
            if (dataSource != null) {
                try {
                    for (String orderId : createdOrders) {
                        jdbc.update("DELETE FROM cw_order WHERE tenant_id=? AND order_id=?", TenantContext.DEFAULT, orderId);
                    }
                } finally {
                    dataSource.close();
                }
            }
        } finally {
            TenantContext.clear();
            AgentInvocationIdentityContext.clear();
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
    void checkRefundEligibility_within7NonTerminalOrder_shouldBeEligible() {
        String result = backend.checkRefundEligibility("20260613003", "true").block();
        assertTrue(result.contains("满足七天无理由退款条件"), "非终态订单+七天内应可退");
    }

    @Test
    void checkRefundEligibility_notWithin7_shouldReject() {
        String result = backend.checkRefundEligibility("20260613003", "false").block();
        assertTrue(result.contains("已超出七天无理由期"), "超出七天应拒绝");
    }

    @Test
    void checkRefundEligibility_refundedOrder_shouldReject() {
        String result = backend.checkRefundEligibility("20260613004", "true").block();
        assertTrue(result.contains("不可重复退款"), "已退款订单不可再退");
    }

    @Test
    void checkRefundEligibility_unknownOrder_shouldReject() {
        assertThrows(NoSuchElementException.class, () -> backend.checkRefundEligibility("99999999999", "true").block());
    }

    @Test
    void submitRefund_shouldGeneratePendingTicket() {
        String orderId = createOwnedOrder();
        try {
            String result = backend.submitRefund(orderId, "199.00", "商品有瑕疵").block();
            assertTrue(result.contains("已生成退款工单"), "应生成退款工单");
            assertTrue(result.contains("金额=199.00 元"));
            assertTrue(result.contains("待人工复核"), "只创建待复核申请，不宣称已经执行资金动作");
            assertEquals("default", jdbc.queryForObject("SELECT tenant_id FROM cw_refund WHERE order_id=?", String.class, orderId));
        } finally {
            deleteRefundByOrder(orderId);
        }
    }

    @Test
    void queryRefundProgress_seededApprovedRefund_shouldOnlyReportApproval() {
        String result = backend.queryRefundProgress("20260613004").block();
        assertTrue(result.contains("审核通过"));
        assertFalse(result.contains("款项已原路退回"), "种子 APPROVED 只证明审核状态");
    }

    @Test
    void queryRefundProgress_noRefundRecord_shouldReturnNotFound() {
        String result = backend.queryRefundProgress("20260613003").block();
        assertTrue(result.contains("未查询到订单") && result.contains("退款记录"));
    }

    @Test
    void submitReturn_shouldGenerateReturnTicket() {
        String orderId = createOwnedOrder();
        try {
            String result = backend.submitReturn(orderId, "不喜欢了").block();
            assertTrue(result.contains("已生成退货工单"), "应生成退货工单");
            assertTrue(result.contains("待人工复核"));
            assertEquals("RETURN", jdbc.queryForObject("SELECT type FROM cw_refund WHERE order_id=?", String.class, orderId));
        } finally {
            deleteRefundByOrder(orderId);
        }
    }

    @Test
    void submitExchange_shouldGenerateExchangeTicket() {
        String orderId = createOwnedOrder();
        try {
            String result = backend.submitExchange(orderId, "尺寸不合适", "白色/大号").block();
            assertTrue(result.contains("已生成换货工单"), "应生成换货工单");
            assertTrue(result.contains("换为「白色/大号」"));
        } finally {
            deleteRefundByOrder(orderId);
        }
    }

    @Test
    void checkPriceProtection_seededOrder_shouldReadOrderAmount() {
        authenticate("U-demo-1");
        String result = backend.checkPriceProtection("20260613001").block();
        assertTrue(result.contains("价保条件尚未确认"));
        assertTrue(result.contains("下单金额 299.00 元"), "应联查 cw_order 金额");
    }

    @Test
    void checkPriceProtection_unknownOrder_shouldReject() {
        assertThrows(NoSuchElementException.class, () -> backend.checkPriceProtection("99999999999").block());
    }

    @Test
    void requestInvoice_shouldPersistRequest() {
        String orderId = createOwnedOrder();
        try {
            String result = backend.requestInvoice(orderId, "北京示例科技有限公司").block();
            assertTrue(result.contains("已受理"), "应受理发票申请");
            assertTrue(result.contains("抬头=「北京示例科技有限公司」"));
        } finally {
            deleteInvoiceByOrder(orderId);
        }
    }

    private void deleteRefundByOrder(String orderId) {
        refundMapper.delete(new LambdaQueryWrapper<RefundDO>().eq(RefundDO::getOrderId, orderId));
    }

    private void deleteInvoiceByOrder(String orderId) {
        invoiceRequestMapper.delete(new LambdaQueryWrapper<InvoiceRequestDO>().eq(InvoiceRequestDO::getOrderId, orderId));
    }

    private void authenticate(String userId) {
        AgentInvocationIdentityContext.set(new AgentInvocationIdentity(TenantContext.DEFAULT, QuotaSubjectType.USER, userId, true));
    }

    private String createOwnedOrder() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO cw_order(tenant_id,order_id,user_id,product_id,amount,status,created_at_ms) VALUES(?,?,?,?,?,?,?)",
            TenantContext.DEFAULT, orderId, USER_ID, "P002", "199.00", "已支付", System.currentTimeMillis());
        createdOrders.add(orderId);
        return orderId;
    }
}
