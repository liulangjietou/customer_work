package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 自我纠错 Hook（对应「可靠性 · 业务约束兜底」）。
 *
 * <p>利用 {@link PostReasoningEvent#gotoReasoning(Msg)}：当模型在<b>没有调用任何退款类工具</b>的情况下，
 * 在回复里出现"已退款 / 已打款"等承诺打款措辞时，判定为越权承诺，注入一条纠正提示并强制 Agent
 * 重新推理，从而把"涉资金必须走工具 + 人工确认"的业务约束从提示词软约定升级为运行时硬兜底。</p>
 *
 * <p>用每会话纠错次数上限防止无限纠错。默认关闭（会引入额外一轮推理）。Hook 异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SelfCorrectionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(SelfCorrectionHook.class);

    private final boolean enabled;
    private final int maxCorrections;
    private final List<String> paymentKeywords;

    /** agent 名 -> 本次请求已纠错次数。 */
    private final ConcurrentMap<String, Integer> corrections = new ConcurrentHashMap<>();

    public SelfCorrectionHook(CustomerWorkProperties properties) {
        CustomerWorkProperties.Hooks.SelfCorrection cfg = properties.getHooks().getSelfCorrection();
        this.enabled = cfg.isEnabled();
        this.maxCorrections = cfg.getMaxCorrections();
        this.paymentKeywords = cfg.getPaymentKeywords();
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled) {
            return Mono.just(event);
        }
        try {
            if (event instanceof PostReasoningEvent e) {
                maybeCorrect(e);
            } else if (event instanceof PostCallEvent e) {
                // 一次请求结束，清理计数
                corrections.remove(agentName(event));
            }
        } catch (Exception ex) {
            log.warn("[FIX] 自我纠错异常（已忽略）: {}", ex.getMessage());
        }
        return Mono.just(event);
    }

    private void maybeCorrect(PostReasoningEvent event) {
        Msg reasoning = event.getReasoningMessage();
        if (reasoning == null) {
            return;
        }
        String text = reasoning.getTextContent();
        // 仅在"纯文本最终答复（无任何工具调用）却承诺打款"时纠错：
        // 若该轮含工具调用，gotoReasoning 注入的消息缺少对应 ToolResult 会触发框架校验异常，
        // 且此时模型尚在行动而非给出最终承诺，不应打断。
        if (text == null || text.isBlank() || !promisesPayment(text) || hasAnyToolUse(reasoning)) {
            return;
        }
        String agent = agentName(event);
        int used = corrections.getOrDefault(agent, 0);
        if (used >= maxCorrections) {
            log.warn("[FIX] 已达纠错次数上限({})，放行 agent={}", maxCorrections, agent);
            return;
        }
        corrections.put(agent, used + 1);
        log.warn("[FIX] 检测到未调用退款工具却承诺打款，强制重新推理 agent={} 第{}次", agent, used + 1);
        event.gotoReasoning(correctionMsg());
    }

    private boolean promisesPayment(String text) {
        for (String kw : paymentKeywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 该轮推理是否包含任何工具调用（含则视为模型在行动，不纠错）。 */
    private boolean hasAnyToolUse(Msg reasoning) {
        return reasoning.hasContentBlocks(ToolUseBlock.class);
    }

    private Msg correctionMsg() {
        return Msg.builder()
            .role(MsgRole.USER)
            .name("system-guard")
            .textContent("【系统纠错】你在没有调用退款工具的情况下承诺了已退款/已打款。"
                + "涉及资金的退款必须先调用退款工具走人工确认流程，不得直接声称已打款。"
                + "请重新作答：要么调用退款工具，要么明确告知用户退款将进入人工复核而非已完成。")
            .build();
    }

    private String agentName(HookEvent event) {
        try {
            return event.getAgent() == null ? "unknown" : event.getAgent().getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public int priority() {
        // 在观测(800)之前评估推理结果
        return 200;
    }
}
