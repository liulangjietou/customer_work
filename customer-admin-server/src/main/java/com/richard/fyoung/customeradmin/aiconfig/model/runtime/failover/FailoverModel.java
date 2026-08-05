package com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover;

import java.util.List;

/**
 * 主备容错模型（admin 薄壳）：降级/熔断语义已下沉到
 * {@link com.richard.fyoung.customerwork.model.failover.FailoverModel}，本类只保留 admin 侧的构造入口
 * （候选类型 {@code Candidate} 直接继承自父类，调用方 {@code AdminAgentInstanceFactory} 无需改动）。
 *
 * <p>动态智能体运行时沿用父类默认的"流中途失败也切下一候选"语义：智能体调用多为工具编排与整段结果，
 * 宁可重发也要拿到完整回答。</p>
 * @author owlzhangfq@gmail.com
 */
public class FailoverModel extends com.richard.fyoung.customerwork.model.failover.FailoverModel {

    /**
     * @param candidates 有序候选（主在前备在后），至少一个
     * @param registry   熔断状态登记表
     */
    public FailoverModel(List<Candidate> candidates, ModelCircuitBreakerRegistry registry) {
        super(candidates, registry);
    }
}
