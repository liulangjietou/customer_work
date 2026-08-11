package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.tool.backend.entity.InvoiceRequestDO;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MyBatis 售后后端测试（对接本机 MySQL；不可达自动跳过）：退款资格联查 cw_order、工单落库往返、种子进度查询。
 *
 * <p>写操作用例统一使用随机订单号，finally 删除自建的 cw_refund / cw_invoice_request 行，绝不污染种子数据。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisAfterSalesBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private RefundMapper refundMapper;
    private InvoiceRequestMapper invoiceRequestMapper;
    private MybatisAfterSalesBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-aftersales-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        refundMapper = MybatisTestSupport.mapper(dataSource, RefundMapper.class);
        invoiceRequestMapper = MybatisTestSupport.mapper(dataSource, InvoiceRequestMapper.class);
        OrderMapper orderMapper = MybatisTestSupport.mapper(dataSource, OrderMapper.class);
        backend = new MybatisAfterSalesBackend(refundMapper, invoiceRequestMapper, orderMapper);
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
    void checkRefundEligibility_unknownOrder_shouldReturnNotFound() {
        String result = backend.checkRefundEligibility("99999999999", "true").block();
        assertTrue(result.contains("未查询到订单"));
    }

    @Test
    void submitRefund_shouldGeneratePendingTicket() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            String result = backend.submitRefund(orderId, "199.00", "商品有瑕疵").block();
            assertTrue(result.contains("已生成退款工单"), "应生成退款工单");
            assertTrue(result.contains("金额=199.00 元"));
            assertTrue(result.contains("已转人工坐席复核"), "退款为人工审批门控，不直接打款");
        } finally {
            deleteRefundByOrder(orderId);
        }
    }

    @Test
    void queryRefundProgress_seededApprovedRefund_shouldReportRefunded() {
        String result = backend.queryRefundProgress("20260613004").block();
        assertTrue(result.contains("审核通过，款项已原路退回"), "种子 APPROVED 退款应报已退回");
    }

    @Test
    void queryRefundProgress_noRefundRecord_shouldReturnNotFound() {
        String result = backend.queryRefundProgress("99999999999").block();
        assertTrue(result.contains("未查询到订单") && result.contains("退款记录"));
    }

    @Test
    void submitReturn_shouldGenerateReturnTicket() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            String result = backend.submitReturn(orderId, "不喜欢了").block();
            assertTrue(result.contains("已生成退货工单"), "应生成退货工单");
            assertTrue(result.contains("7 天内将商品寄回"));
        } finally {
            deleteRefundByOrder(orderId);
        }
    }

    @Test
    void submitExchange_shouldGenerateExchangeTicket() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
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
        String result = backend.checkPriceProtection("20260613001").block();
        assertTrue(result.contains("价保校验"));
        assertTrue(result.contains("下单金额 299.00 元"), "应联查 cw_order 金额");
    }

    @Test
    void checkPriceProtection_unknownOrder_shouldReturnNotFound() {
        String result = backend.checkPriceProtection("99999999999").block();
        assertTrue(result.contains("未查询到订单"));
    }

    @Test
    void requestInvoice_shouldPersistRequest() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
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
}
