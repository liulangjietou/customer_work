package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 售后后端的默认演示实现。保留资金安全红线：退款只生成待人工确认工单，不直接打款。
 * @author owlzhangfq@gmail.com
 */
public class MockAfterSalesBackend implements AfterSalesBackend {

    private static final Logger log = LoggerFactory.getLogger(MockAfterSalesBackend.class);

    @Override
    public Mono<String> checkRefundEligibility(String orderId, String withinSevenDays) {
        log.info("[MockAfterSalesBackend] 退款资格校验: order={}, within7={}", orderId, withinSevenDays);
        return Mono.fromSupplier(() -> {
                if (!"true".equalsIgnoreCase(withinSevenDays)) {
                    return "订单 " + orderId + " 已超出七天无理由期，不满足无理由退款条件，"
                         + "如确需退款请走特殊申诉流程（转人工）。";
                }
                return "订单 " + orderId + " 满足七天无理由退款条件，可发起退款申请。";
            })
            .delayElement(Duration.ofMillis(80))
            .onErrorResume(e -> Mono.just("退款规则引擎暂时不可用，建议转人工处理。"));
    }

    @Override
    public Mono<String> submitRefund(String orderId, String amount, String reason) {
        log.info("[MockAfterSalesBackend] 生成退款工单: order={}, amount={}, reason={}", orderId, amount, reason);
        return Mono.fromSupplier(() -> {
                String ticketId = "RF" + System.currentTimeMillis();
                return String.format(
                    "已生成退款工单 %s：订单=%s，金额=%s 元，原因=%s。"
                  + "【涉及资金，已转人工坐席复核，预计 1 个工作日内处理完成】",
                    ticketId, orderId, amount, reason);
            })
            .delayElement(Duration.ofMillis(90))
            .onErrorResume(e -> Mono.just("退款工单系统暂时不可用，已为您转接人工坐席。"));
    }

    @Override
    public Mono<String> queryRefundProgress(String orderId) {
        log.info("[MockAfterSalesBackend] query refund progress: {}", orderId);
        return Mono.just("订单 " + orderId + " 的退款进度：审核通过，款项已原路退回，预计 1-3 个工作日到账"
                + "（具体以银行/支付渠道为准）。")
            .delayElement(Duration.ofMillis(70));
    }

    @Override
    public Mono<String> submitReturn(String orderId, String reason) {
        log.info("[MockAfterSalesBackend] submit return: order={}, reason={}", orderId, reason);
        return Mono.just("已生成退货工单 RT" + System.currentTimeMillis() + "：订单=" + orderId + "，原因=" + reason
                + "。请在 7 天内将商品寄回，回寄地址与运费规则将以短信发送。")
            .delayElement(Duration.ofMillis(80));
    }

    @Override
    public Mono<String> submitExchange(String orderId, String reason, String newSpec) {
        log.info("[MockAfterSalesBackend] submit exchange: order={}, reason={}, newSpec={}", orderId, reason, newSpec);
        return Mono.just("已生成换货工单 EX" + System.currentTimeMillis() + "：订单=" + orderId
                + "，换为「" + newSpec + "」，原因=" + reason + "。新规格有货，收到回寄商品后为您发出。")
            .delayElement(Duration.ofMillis(80));
    }

    @Override
    public Mono<String> checkPriceProtection(String orderId) {
        log.info("[MockAfterSalesBackend] check price protection: {}", orderId);
        return Mono.just("订单 " + orderId + " 价保校验：在价保有效期内，当前价低于下单价 30 元，"
                + "已为您生成价保补差工单，差价将退回原支付方式。")
            .delayElement(Duration.ofMillis(70));
    }

    @Override
    public Mono<String> requestInvoice(String orderId, String invoiceTitle) {
        log.info("[MockAfterSalesBackend] request invoice: order={}, title={}", orderId, invoiceTitle);
        return Mono.just("已受理订单 " + orderId + " 的发票申请，抬头=「" + invoiceTitle + "」，"
                + "电子发票将于 24 小时内发送至您账户预留邮箱。")
            .delayElement(Duration.ofMillis(70));
    }
}
