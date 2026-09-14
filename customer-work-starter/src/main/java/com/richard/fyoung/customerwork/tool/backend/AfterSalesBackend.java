package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 售后后端（扩展点）：对接你自己的退款规则引擎 / 工单系统。
 * 默认 {@link MockAfterSalesBackend}；提供自定义 Bean 即可覆盖。
 *
 * <p>真实业务实现必须在方法调用时保存可信调用身份，并在延迟执行时按该身份校验订单归属。
 * 创建方法只有完成真实持久化后才可返回成功文本；权限拒绝、写入失败必须通过
 * {@link Mono#error(Throwable)} 传递，调用方据此决定是否登记审批，不能解析文本判断成功。
 * 返回内容只描述已发生的动作；审批通过不等于退款到账。</p>
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

    /** 查询订单价保核验信息；没有实际价格及规则依据时，不得声称已完成核验或补差。 */
    Mono<String> checkPriceProtection(String orderId);

    /** 申请/重开发票。 */
    Mono<String> requestInvoice(String orderId, String invoiceTitle);
}
