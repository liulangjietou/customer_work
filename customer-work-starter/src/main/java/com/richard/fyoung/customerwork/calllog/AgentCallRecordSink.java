package com.richard.fyoung.customerwork.calllog;

/**
 * 智能体调用记录下沉 SPI（采集与落地解耦的扩展点）。
 *
 * <p>{@code AgentCallTimingMiddleware} 在每次调用结束时组装 {@link AgentCallRecord} 交给本 Sink。
 * 默认实现 {@link StoreAgentCallRecordSink} 异步落 starter 自己的 {@link AgentCallLogStore}；下游可声明
 * 自己的 {@code AgentCallRecordSink} Bean 覆盖（如投递 Kafka / 数仓）。</p>
 *
 * <p><b>约定</b>：实现必须非阻塞或自行异步——本方法在响应事件流的终止回调里被调用，阻塞会拖慢响应。
 * 且实现自身不得抛异常打断主链路（中间件侧已兜底 catch，实现侧仍应自我消化异常）。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AgentCallRecordSink {

    /** 提交一条调用记录（非阻塞）。 */
    void emit(AgentCallRecord record);
}
