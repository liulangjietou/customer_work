package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

/**
 * 工具执行的超时与重试。
 *
 * <p><b>为什么要覆盖框架默认值</b>：实测 {@code ExecutionConfig.TOOL_DEFAULTS} 是
 * {@code timeout=5分钟, maxAttempts=1}。5 分钟对客服对话场景等于没有超时——
 * 订单库慢一次，用户就对着一个不动的界面等五分钟；而工具背后是订单、售后、会员这些
 * 真实业务后端，它们会慢会挂。项目在模型侧做了失败转移、熔断、分级路由一整套弹性，
 * 工具侧此前一样都没配。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Data
public class ToolExecutionProperties {

    /**
     * 单次工具执行的硬超时（毫秒）。
     *
     * <p>客服工具是查订单、查物流、查会员这类操作，正常在秒级返回。15 秒足以覆盖一次慢查询
     * 或一次跨机房抖动，又不至于让用户对着不动的界面干等。超时后模型会收到一个错误结果并自行应对，
     * 比继续等下去要好。</p>
     */
    private long timeoutMs = 15_000L;

    /**
     * 最大尝试次数，<b>默认 1（即不重试）</b>。
     *
     * <p><b>这个默认值是刻意的，改它之前请想清楚</b>：框架的重试是对整个工具集统一生效的，
     * 不区分工具是否幂等。而客服工具里有「发起退款」「创建工单」「转人工」这类
     * <b>重试一次就多做一次</b>的操作——超时往往意味着请求已经到达下游、只是响应慢了，
     * 这时重试会退两次款。</p>
     *
     * <p>所以这里不提供"给所有工具都加上重试"这个选项的默认开启。确实需要重试的部署，
     * 必须先确认全部已注册工具都是幂等的（含 MCP 侧接进来的），再显式调大。</p>
     */
    private int maxAttempts = 1;

    /** 重试初始退避（毫秒）；{@link #maxAttempts} 为 1 时不生效。 */
    private long initialBackoffMs = 200L;

    /** 重试最大退避（毫秒）。 */
    private long maxBackoffMs = 2_000L;

    /** 退避倍率。 */
    private double backoffMultiplier = 2.0d;
}
