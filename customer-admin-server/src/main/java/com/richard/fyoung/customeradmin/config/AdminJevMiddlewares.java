package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.core.middleware.JevEscalationMiddleware;
import com.richard.fyoung.customerwork.core.middleware.JevRefundRiskMiddleware;
import com.richard.fyoung.customerwork.core.middleware.JevToolScopeMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SelfCorrectionMiddleware;
import io.agentscope.core.middleware.MiddlewareBase;

import java.util.List;

/**
 * 后台智能体挂载的 Jev 中间件组：三个决策点跑影子模式，答复闸门真执行。
 *
 * <p>打包成一个对象交给建 Agent 的入口，是为了让后台所有建 Agent 的路径拿到的是同一组、
 * 同一种运行模式的实例——各入口自己 new 的话，迟早有一条路径忘了挂或挂错模式。</p>
 *
 * @param escalation     情绪升级判定（影子）
 * @param toolScope      意图 · 工具收窄（影子）
 * @param refundRisk     退款风险判定（影子）
 * @param selfCorrection 答复安全闸门（真执行并展示）
 */
public record AdminJevMiddlewares(JevEscalationMiddleware escalation,
                                  JevToolScopeMiddleware toolScope,
                                  JevRefundRiskMiddleware refundRisk,
                                  SelfCorrectionMiddleware selfCorrection) {

    /** 按顺序值由框架排序，这里的先后无关紧要。 */
    public List<MiddlewareBase> all() {
        return List.of(escalation, toolScope, refundRisk, selfCorrection);
    }
}
