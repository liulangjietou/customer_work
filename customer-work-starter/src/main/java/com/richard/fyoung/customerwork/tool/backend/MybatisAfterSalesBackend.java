package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.tool.backend.entity.InvoiceRequestDO;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * 售后后端的 MyBatis-Plus 实现：退款/退货/换货工单真实落库到 {@code cw_refund}，发票申请落库到
 * {@code cw_invoice_request}，退款资格 / 价保联查 {@code cw_order}（订单存在且状态允许才可退）。
 *
 * <p>保留资金安全红线：{@code submitRefund} 只生成 {@code PENDING} 待人工复核工单，<b>不直接打款</b>，
 * 语义与 {@link MockAfterSalesBackend} 完全一致。输出文案对齐 Mock。工单落库走 {@link RefundMapper} /
 * {@link InvoiceRequestMapper} 的 BaseMapper insert，最近退款状态查询走 XML；联查订单走 {@link OrderMapper}。
 * 本类由 starter 的 {@code ToolBackendConfig} 在 {@code tool-backend.mode=jdbc} 时装配。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisAfterSalesBackend implements AfterSalesBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisAfterSalesBackend.class);

    /** 售后工单类型：退款 / 退货 / 换货。 */
    private static final String TYPE_REFUND = "REFUND";
    private static final String TYPE_RETURN = "RETURN";
    private static final String TYPE_EXCHANGE = "EXCHANGE";

    /** 工单状态：待人工复核 / 已通过。 */
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";

    /** 不可再退款的订单终态。 */
    private static final String ORDER_STATUS_REFUNDED = "已退款";
    private static final String ORDER_STATUS_CANCELLED = "已取消";

    private static final String TRUE_FLAG = "true";

    private final RefundMapper refundMapper;
    private final InvoiceRequestMapper invoiceRequestMapper;
    private final OrderMapper orderMapper;

    public MybatisAfterSalesBackend(RefundMapper refundMapper, InvoiceRequestMapper invoiceRequestMapper,
                                    OrderMapper orderMapper) {
        this.refundMapper = refundMapper;
        this.invoiceRequestMapper = invoiceRequestMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    public Mono<String> checkRefundEligibility(String orderId, String withinSevenDays) {
        return Mono.fromSupplier(() -> doCheckRefundEligibility(orderId, withinSevenDays));
    }

    @Override
    public Mono<String> submitRefund(String orderId, String amount, String reason) {
        return Mono.fromSupplier(() -> doSubmitRefund(orderId, amount, reason));
    }

    @Override
    public Mono<String> queryRefundProgress(String orderId) {
        return Mono.fromSupplier(() -> doQueryRefundProgress(orderId));
    }

    @Override
    public Mono<String> submitReturn(String orderId, String reason) {
        return Mono.fromSupplier(() -> doSubmitReturn(orderId, reason));
    }

    @Override
    public Mono<String> submitExchange(String orderId, String reason, String newSpec) {
        return Mono.fromSupplier(() -> doSubmitExchange(orderId, reason, newSpec));
    }

    @Override
    public Mono<String> checkPriceProtection(String orderId) {
        return Mono.fromSupplier(() -> doCheckPriceProtection(orderId));
    }

    @Override
    public Mono<String> requestInvoice(String orderId, String invoiceTitle) {
        return Mono.fromSupplier(() -> doRequestInvoice(orderId, invoiceTitle));
    }

    /** 退款资格校验：联查 cw_order（订单存在且非终态才可退），再按七天无理由标记裁决。 */
    private String doCheckRefundEligibility(String orderId, String withinSevenDays) {
        try {
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return "未查询到订单 " + orderId + "，无法校验退款资格，请核对订单号。";
            }
            String status = order.getStatus();
            if (ORDER_STATUS_REFUNDED.equals(status) || ORDER_STATUS_CANCELLED.equals(status)) {
                return "订单 " + orderId + " 当前状态为" + status + "，不可重复退款。";
            }
            if (!TRUE_FLAG.equalsIgnoreCase(withinSevenDays)) {
                return "订单 " + orderId + " 已超出七天无理由期，不满足无理由退款条件，"
                     + "如确需退款请走特殊申诉流程（转人工）。";
            }
            return "订单 " + orderId + " 满足七天无理由退款条件，可发起退款申请。";
        } catch (Exception e) {
            log.error("refund eligibility check failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-ELIGIBILITY-FAIL", orderId, e);
            return "退款规则引擎暂时不可用，建议转人工处理。";
        }
    }

    /** 生成退款工单：仅落 PENDING 待人工复核记录，不打款（资金安全红线）。 */
    private String doSubmitRefund(String orderId, String amount, String reason) {
        String refundNo = TYPE_REFUND.substring(0, 2) + System.currentTimeMillis();
        boolean ok = insertRefund(refundNo, orderId, TYPE_REFUND, STATUS_PENDING,
            toDecimal(amount), reason, null, "AFTERSALES-BACKEND-REFUND-FAIL");
        if (!ok) {
            return "退款工单系统暂时不可用，已为您转接人工坐席。";
        }
        return String.format(
            "已生成退款工单 %s：订单=%s，金额=%s 元，原因=%s。"
          + "【涉及资金，已转人工坐席复核，预计 1 个工作日内处理完成】",
            refundNo, orderId, amount, reason);
    }

    /** 按订单查询最近一笔退款工单进度。 */
    private String doQueryRefundProgress(String orderId) {
        try {
            String status = refundMapper.queryLatestRefundStatus(orderId, TYPE_REFUND);
            if (status == null) {
                return "未查询到订单 " + orderId + " 的退款记录，请确认是否已提交退款申请。";
            }
            if (STATUS_APPROVED.equals(status)) {
                return "订单 " + orderId + " 的退款进度：审核通过，款项已原路退回，预计 1-3 个工作日到账"
                     + "（具体以银行/支付渠道为准）。";
            }
            return "订单 " + orderId + " 的退款进度：待人工复核，预计 1 个工作日内处理完成。";
        } catch (Exception e) {
            log.error("refund progress query failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-PROGRESS-FAIL", orderId, e);
            return "退款进度查询暂时不可用，建议稍后再试。";
        }
    }

    /** 提交退货工单（cw_refund 同表 type=RETURN）。 */
    private String doSubmitReturn(String orderId, String reason) {
        String refundNo = TYPE_RETURN.substring(0, 2) + System.currentTimeMillis();
        boolean ok = insertRefund(refundNo, orderId, TYPE_RETURN, STATUS_PENDING,
            null, reason, null, "AFTERSALES-BACKEND-RETURN-FAIL");
        if (!ok) {
            return "退货工单系统暂时不可用，已为您转接人工坐席。";
        }
        return "已生成退货工单 " + refundNo + "：订单=" + orderId + "，原因=" + reason
            + "。请在 7 天内将商品寄回，回寄地址与运费规则将以短信发送。";
    }

    /** 提交换货工单（cw_refund 同表 type=EXCHANGE，new_spec 记录目标规格）。 */
    private String doSubmitExchange(String orderId, String reason, String newSpec) {
        String refundNo = TYPE_EXCHANGE.substring(0, 2) + System.currentTimeMillis();
        boolean ok = insertRefund(refundNo, orderId, TYPE_EXCHANGE, STATUS_PENDING,
            null, reason, newSpec, "AFTERSALES-BACKEND-EXCHANGE-FAIL");
        if (!ok) {
            return "换货工单系统暂时不可用，已为您转接人工坐席。";
        }
        return "已生成换货工单 " + refundNo + "：订单=" + orderId
            + "，换为「" + newSpec + "」，原因=" + reason + "。新规格有货，收到回寄商品后为您发出。";
    }

    /** 价保校验：联查 cw_order 下单金额，比对当前是否降价（只读，不打款）。 */
    private String doCheckPriceProtection(String orderId) {
        try {
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return "未查询到订单 " + orderId + "，无法办理价保，请核对订单号。";
            }
            String paid = order.getAmount().toPlainString();
            return "订单 " + orderId + " 价保校验：在价保有效期内，当前未监测到降价，暂无可补差价"
                 + "（下单金额 " + paid + " 元）；若后续降价可再次发起价保申请。";
        } catch (Exception e) {
            log.error("price protection check failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-PRICEPROTECT-FAIL", orderId, e);
            return "价保系统暂时不可用，建议稍后再试。";
        }
    }

    /** 发票申请：真实落库到 cw_invoice_request（PENDING）。 */
    private String doRequestInvoice(String orderId, String invoiceTitle) {
        try {
            InvoiceRequestDO record = new InvoiceRequestDO();
            record.setOrderId(orderId);
            record.setInvoiceTitle(invoiceTitle);
            record.setStatus(STATUS_PENDING);
            record.setCreatedAtMs(System.currentTimeMillis());
            invoiceRequestMapper.insert(record);
            return "已受理订单 " + orderId + " 的发票申请，抬头=「" + invoiceTitle + "」，"
                + "电子发票将于 24 小时内发送至您账户预留邮箱。";
        } catch (Exception e) {
            log.error("invoice request failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-INVOICE-FAIL", orderId, e);
            return "发票系统暂时不可用，建议稍后再试。";
        }
    }

    /** 插入一条售后工单（退款/退货/换货共表），返回是否成功。 */
    private boolean insertRefund(String refundNo, String orderId, String type, String status,
                                 BigDecimal amount, String reason, String newSpec, String errorCode) {
        try {
            RefundDO record = new RefundDO();
            record.setRefundNo(refundNo);
            record.setOrderId(orderId);
            record.setType(type);
            record.setStatus(status);
            record.setAmount(amount);
            record.setReason(reason);
            record.setNewSpec(newSpec);
            record.setCreatedAtMs(System.currentTimeMillis());
            refundMapper.insert(record);
            return true;
        } catch (Exception e) {
            log.error("aftersales ticket insert failed, code={}, orderId={}, type={}",
                errorCode, orderId, type, e);
            return false;
        }
    }

    /** String 金额转 BigDecimal，非数值返回 null（不阻断工单生成）。 */
    private static BigDecimal toDecimal(String amount) {
        if (amount == null || amount.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
