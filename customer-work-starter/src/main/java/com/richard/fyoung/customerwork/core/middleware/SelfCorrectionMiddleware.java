package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;
import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;

/**
 * 自我纠错中间件（2.0 Middleware，承接 1.x SelfCorrectionHook，「可靠性 · 业务约束兜底」）。
 *
 * <p>落在 {@link #onAgent} 段：检测最终回复是否在<b>未走退款工具</b>的情况下出现"已退款 / 已打款"等
 * 越权承诺，命中则告警标记（供审计 / 人工介入）。</p>
 *
 * <p><b>2.0 说明</b>：1.x 的 {@code gotoReasoning(强制重新推理)} 在中间件模型下无等价语义；本中间件
 * 退化为"检测 + 告警"，而把"涉资金必须走工具/人工确认"的<b>硬约束</b>交由 Permission 的 ask/deny 规则
 * （{@code PermissionConfig}）与系统提示词承担。默认关闭。异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SelfCorrectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SelfCorrectionMiddleware.class);

    private final boolean enabled;
    private final List<String> paymentKeywords;

    public SelfCorrectionMiddleware(CustomerWorkProperties properties) {
        HooksProperties.SelfCorrection cfg = properties.getHooks().getSelfCorrection();
        this.enabled = cfg.isEnabled();
        this.paymentKeywords = cfg.getPaymentKeywords();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        return next.apply(input).doOnNext(event -> {
            try {
                if (event instanceof AgentResultEvent result) {
                    Msg msg = result.getResult();
                    String text = msg == null ? null : msg.getTextContent();
                    if (promisesPayment(text)) {
                        log.info("[FIX] payment promise detected in final answer, flagged for review, "
                            + "agent={} (hard constraint enforced by Permission ask/deny rules)",
                            agent == null ? "?" : agent.getName());
                    }
                }
            } catch (Exception e) {
                log.error("[FIX] self-correction check failed, code={}", "SELF_CORRECTION_ERROR", e);
            }
        });
    }

    boolean promisesPayment(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String kw : paymentKeywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
