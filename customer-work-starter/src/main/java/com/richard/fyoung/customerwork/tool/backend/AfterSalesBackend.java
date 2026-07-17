package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 售后后端（扩展点）：对接你自己的退款规则引擎 / 工单系统。
 * 默认 {@link MockAfterSalesBackend}；提供自定义 Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public interface AfterSalesBackend {

    /** 校验订单是否满足退款条件。 */
    Mono<String> checkRefundEligibility(String orderId, String withinSevenDays);

    /** 发起退款（生产应只生成待人工确认工单，不直接打款）。 */
    Mono<String> submitRefund(String orderId, String amount, String reason);

    /** 查询退款/退货进度。 */
    Mono<String> queryRefundProgress(String orderId);

    /** 提交退货申请（生成退货工单）。 */
    Mono<String> submitReturn(String orderId, String reason);

    /** 提交换货申请（生成换货工单）。 */
    Mono<String> submitExchange(String orderId, String reason, String newSpec);

    /** 校验并申请价保（买后降价补差）。 */
    Mono<String> checkPriceProtection(String orderId);

    /** 申请/重开发票。 */
    Mono<String> requestInvoice(String orderId, String invoiceTitle);
}
