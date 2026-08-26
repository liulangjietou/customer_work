package com.richard.fyoung.customeradmin.aiconfig.model.runtime.failover;

import com.richard.fyoung.customerwork.core.model.failover.FailoverModel;
import java.util.List;

/**
 * 主备容错模型（admin 薄壳）：降级/熔断语义已下沉到
 * {@link FailoverModel}，本类只保留 admin 侧的构造入口
 * （候选类型 {@code Candidate} 直接继承自父类，调用方 {@code AdminAgentInstanceFactory} 无需改动）。
 *
 * <p>动态智能体运行时沿用父类默认的"流中途失败也切下一候选"语义：智能体调用多为工具编排与整段结果，
 * 宁可重发也要拿到完整回答。</p>
 * @author owlzhangfq@gmail.com
 */
public class AdminFailoverModel extends FailoverModel {

    /**
     * @param candidates 有序候选（主在前备在后），至少一个
     * @param registry   熔断状态登记表
     */
    public AdminFailoverModel(List<Candidate> candidates, AdminModelCircuitBreakerRegistry registry) {
        super(candidates, registry);
    }
}
