package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 投诉工单后端（扩展点）：对接你自己的工单 / 客诉系统。
 * 默认 {@link MockComplaintBackend}；提供自定义 Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public interface ComplaintBackend {

    /**
     * 投诉单号前缀：属于本 SPI 的输出契约而不是某个实现的内部细节——
     * 调用方按这个前缀识别单号类型，Mock 与落库两个实现必须一致。
     */
    String ID_PREFIX = "CP";

    /** 创建投诉工单，返回工单号与受理说明。 */
    Mono<String> fileComplaint(String orderId, String content);

    /** 查询投诉工单处理状态。 */
    Mono<String> queryComplaint(String ticketId);
}
