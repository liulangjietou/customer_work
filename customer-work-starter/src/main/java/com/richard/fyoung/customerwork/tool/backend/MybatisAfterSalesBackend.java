package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.data.order.OrderStatuses;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.entity.InvoiceRequestDO;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * 真实订单的售后查询与申请：只允许已认证用户访问本租户自己的订单。
 *
 * <p>调用时冻结可信主体，延迟 SQL 执行时恢复租户；读写同时显式约束租户与订单用户，不依赖
 * 宿主是否安装租户插件。创建只登记待处理申请，数据库失败通过错误信号传播，不代表支付或通知已执行。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisAfterSalesBackend implements AfterSalesBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisAfterSalesBackend.class);
    private static final String TYPE_REFUND = "REFUND";
    private static final String TYPE_RETURN = "RETURN";
    private static final String TYPE_EXCHANGE = "EXCHANGE";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_DENIED = "DENIED";
    private static final String TRUE_FLAG = "true";
    private static final String CODE_ELIGIBILITY = "AFTERSALES-BACKEND-ELIGIBILITY-FAIL";
    private static final String CODE_REFUND = "AFTERSALES-BACKEND-REFUND-FAIL";
    private static final String CODE_PROGRESS = "AFTERSALES-BACKEND-PROGRESS-FAIL";
    private static final String CODE_RETURN = "AFTERSALES-BACKEND-RETURN-FAIL";
    private static final String CODE_EXCHANGE = "AFTERSALES-BACKEND-EXCHANGE-FAIL";
    private static final String CODE_PRICE_PROTECTION = "AFTERSALES-BACKEND-PRICEPROTECT-FAIL";
    private static final String CODE_INVOICE = "AFTERSALES-BACKEND-INVOICE-FAIL";

    private final RefundMapper refundMapper;
    private final InvoiceRequestMapper invoiceRequestMapper;
    private final OrderMapper orderMapper;

    public MybatisAfterSalesBackend(RefundMapper refundMapper, InvoiceRequestMapper invoiceRequestMapper,
                                    OrderMapper orderMapper) {
        this.refundMapper = refundMapper;
        this.invoiceRequestMapper = invoiceRequestMapper;
        this.orderMapper = orderMapper;
    }

    /** 在自有订单范围内沿用现有七天与订单终态规则。 */
    @Override
    public Mono<String> checkRefundEligibility(String orderId, String withinSevenDays) {
        return execute(orderId, CODE_ELIGIBILITY, identity -> {
            OrderDO order = requireOwnedOrder(identity, orderId);
            String status = order.getStatus();
            if (OrderStatuses.REFUNDED.equals(status) || OrderStatuses.CANCELLED.equals(status)) {
                return "订单 " + orderId + " 当前状态为" + status + "，不可重复退款。";
            }
            if (!TRUE_FLAG.equalsIgnoreCase(withinSevenDays)) {
                return "订单 " + orderId + " 已超出七天无理由期，不满足无理由退款条件。";
            }
            return "订单 " + orderId + " 满足七天无理由退款条件，可发起退款申请。";
        });
    }

    /** 为真实自有订单创建待人工复核的退款申请，不执行支付。 */
    @Override
    public Mono<String> submitRefund(String orderId, String amount, String reason) {
        return execute(orderId, CODE_REFUND, identity -> {
            RefundDO record = insertRefund(identity, orderId, TYPE_REFUND, toDecimal(amount), reason, null);
            String recordedAmount = record.getAmount() == null ? "未确认" : record.getAmount().toPlainString() + " 元";
            return "已生成退款工单 " + record.getRefundNo() + "：订单=" + orderId
                + "，金额=" + recordedAmount + "，原因=" + reason + "。当前状态：待人工复核。";
        });
    }

    /** 读取自有订单的真实审批状态；审核通过不等于资金到账。 */
    @Override
    public Mono<String> queryRefundProgress(String orderId) {
        return execute(orderId, CODE_PROGRESS, identity -> {
            requireOwnedOrder(identity, orderId);
            String status = refundMapper.queryLatestRefundStatus(
                TenantContext.require(), identity.subjectId(), orderId, TYPE_REFUND);
            if (status == null) {
                return "未查询到订单 " + orderId + " 的退款记录。";
            }
            return "订单 " + orderId + " 的退款进度：" + switch (status) {
                case STATUS_PENDING -> "待人工复核。";
                case STATUS_APPROVED -> "审核通过，到账情况尚未确认。";
                case STATUS_DENIED -> "审核未通过。";
                default -> "当前状态为 " + status + "。";
            };
        });
    }

    /** 只创建自有订单的退货申请，不承诺寄回期限或通知已经发送。 */
    @Override
    public Mono<String> submitReturn(String orderId, String reason) {
        return execute(orderId, CODE_RETURN, identity -> {
            RefundDO record = insertRefund(identity, orderId, TYPE_RETURN, null, reason, null);
            return "已生成退货工单 " + record.getRefundNo() + "：订单=" + orderId
                + "，原因=" + reason + "。当前状态：待人工复核。";
        });
    }

    /** 记录换货诉求与目标规格，不把申请当成库存确认或发货。 */
    @Override
    public Mono<String> submitExchange(String orderId, String reason, String newSpec) {
        return execute(orderId, CODE_EXCHANGE, identity -> {
            RefundDO record = insertRefund(identity, orderId, TYPE_EXCHANGE, null, reason, newSpec);
            return "已生成换货工单 " + record.getRefundNo() + "：订单=" + orderId
                + "，申请换为「" + newSpec + "」，原因=" + reason + "。当前状态：待人工复核。";
        });
    }

    /** 只返回已经读取的订单金额，价格变动与价保资格尚无可核实的数据。 */
    @Override
    public Mono<String> checkPriceProtection(String orderId) {
        return execute(orderId, CODE_PRICE_PROTECTION, identity -> {
            OrderDO order = requireOwnedOrder(identity, orderId);
            return "订单 " + orderId + " 下单金额 " + order.getAmount().toPlainString()
                + " 元。是否符合价保条件尚未确认，需核实价格与适用规则。";
        });
    }

    /** 为真实自有订单创建待处理的发票申请，不代表发票已开具或邮件已发送。 */
    @Override
    public Mono<String> requestInvoice(String orderId, String invoiceTitle) {
        return execute(orderId, CODE_INVOICE, identity -> {
            InvoiceRequestDO record = new InvoiceRequestDO();
            record.setTenantId(TenantContext.require());
            record.setOrderId(orderId);
            record.setInvoiceTitle(invoiceTitle);
            record.setStatus(STATUS_PENDING);
            record.setCreatedAtMs(System.currentTimeMillis());
            if (invoiceRequestMapper.insertForOwner(record, identity.subjectId()) != 1) {
                throw orderUnavailable();
            }
            return "已受理订单 " + orderId + " 的发票申请，抬头=「" + invoiceTitle + "」。当前状态：待处理。";
        });
    }

    /**
     * 七个公共入口共用的身份边界：调用时捕获，订阅时使用，不读取订阅线程可能残留的其他身份。
     * USER 以外的主体缺少订单用户授权事实；不能把 API Key 指纹或后台用户 ID 当作订单用户。
     */
    private Mono<String> execute(String orderId, String errorCode, Function<AgentInvocationIdentity, String> action) {
        AgentInvocationIdentity identity = AgentInvocationIdentity.capture();
        return Mono.fromSupplier(() -> {
            if (identity == null || !identity.authenticated() || identity.subjectType() != QuotaSubjectType.USER
                || !TenantContext.isValidTenantId(identity.tenantId()) || !StringUtils.hasText(identity.subjectId())) {
                throw new SecurityException("authenticated order user identity is required");
            }
            return TenantContext.callWith(identity.tenantId(), () -> action.apply(identity));
        }).doOnError(error -> log.error("aftersales operation failed, errorCode={}, orderId={}", errorCode, orderId, error))
            // 工具执行器会解包根异常；原始原因只保留在服务端日志，避免 SQL 与库内数据进入模型结果。
            .onErrorMap(DataAccessException.class,
                error -> new IllegalStateException("售后服务暂时不可用，请稍后重试。"));
    }

    private OrderDO requireOwnedOrder(AgentInvocationIdentity identity, String orderId) {
        OrderDO order = orderMapper.findOwned(TenantContext.require(), identity.subjectId(), orderId);
        if (order == null) {
            throw orderUnavailable();
        }
        return order;
    }

    /** 订单归属校验与写入由同一条 SQL 完成；影响行数为零不能报告创建成功。 */
    private RefundDO insertRefund(AgentInvocationIdentity identity, String orderId, String type,
                                  BigDecimal amount, String reason, String newSpec) {
        RefundDO record = new RefundDO();
        record.setTenantId(TenantContext.require());
        record.setRefundNo(type.substring(0, 2) + System.currentTimeMillis());
        record.setOrderId(orderId);
        record.setType(type);
        record.setStatus(STATUS_PENDING);
        record.setAmount(amount);
        record.setReason(reason);
        record.setNewSpec(newSpec);
        record.setCreatedAtMs(System.currentTimeMillis());
        if (refundMapper.insertForOwner(record, identity.subjectId()) != 1) {
            throw orderUnavailable();
        }
        return record;
    }

    private static NoSuchElementException orderUnavailable() {
        return new NoSuchElementException("order not found or not owned by the authenticated user");
    }

    /** 沿用现有金额输入契约：未确认或非数值金额保存为空，不在权限修复中增加退款政策。 */
    private static BigDecimal toDecimal(String amount) {
        if (!StringUtils.hasText(amount)) {
            return null;
        }
        try {
            return new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
