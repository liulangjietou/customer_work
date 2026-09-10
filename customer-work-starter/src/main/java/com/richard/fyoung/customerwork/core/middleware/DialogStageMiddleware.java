package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.dialog.DialogStage;
import com.richard.fyoung.customerwork.capability.dialog.DialogStageService;
import com.richard.fyoung.customerwork.tool.RefundIntakeTools;
import com.richard.fyoung.customerwork.tool.ToolConstants;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 对话阶段中间件（借鉴 AliGo 动态 Prompt 状态机）：按会话阶段动态组装系统提示词，并驱动阶段流转。
 *
 * <p>读侧在 {@link #onSystemPrompt} 段把当前 {@link DialogStage} 的聚焦指令追加到系统提示词，
 * 替代把全量规则塞进一个静态大 Prompt——降低 token、提升当前阶段的判定准确率。</p>
 *
 * <p><b>写侧此前整个缺失</b>：{@code DialogStageService} 的 {@code set} / {@code advance} 在全仓生产代码里
 * 没有任何调用方，每个会话都永远停在 {@code GREETING}。而 GREETING 当时的文案是"暂不调用业务工具"，
 * 于是每一轮对话的系统提示词末尾——模型注意力最重的位置——都写着让它别调工具。这不报任何错，
 * 只表现为工具调用率被无声压低、智能体倾向于反问澄清而不是直接办事。</p>
 *
 * <p><b>流转只认可观测的事实，不做猜测</b>：</p>
 * <ul>
 *   <li>发起转人工工具 → {@code ESCALATED}（终态，不再自行办理）；</li>
 *   <li>发起任何业务工具 → {@code PROCESSING}（模型能调工具，说明意图已明确、信息已够）；</li>
 *   <li>本轮回复正常结束且当前在 {@code PROCESSING} → {@code CONFIRMING}（复述结果、确认收尾）。</li>
 * </ul>
 *
 * <p><b>{@code COLLECTING} 由信息收集工具驱动</b>：模型调用
 * {@code collectRefundInfo} 说明它判断"要办这件事但信息还不全"，这是个确切信号。
 * 此前这一跳空着，因为槽位填充虽然建好却不在主链路上——现在它作为工具交给了模型，
 * 这个接口才真正接上（P1-4）。仍然刻意不用"连续两轮没调工具"这类脆弱启发式去猜阶段。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class DialogStageMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(DialogStageMiddleware.class);

    private final DialogStageService stageService;

    public DialogStageMiddleware(DialogStageService stageService) {
        this.stageService = stageService;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        DialogStage stage = stageService.current(sessionId(ctx));
        String note = String.format("%n%n[对话阶段] 当前处于%s", stage.promptFragment());
        return Mono.just((currentPrompt == null ? "" : currentPrompt) + note);
    }

    /**
     * 工具调用是阶段推进的信号源。
     *
     * <p>推进发生在工具真正执行<b>之前</b>：同一轮里模型可能多次 reasoning，
     * 先落阶段才能让紧接着的那次 {@link #onSystemPrompt} 拿到新阶段的指令。</p>
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        applyToolCallSignal(sessionId(ctx), input);
        return next.apply(input);
    }

    /**
     * 一轮回复正常结束时收尾到 {@code CONFIRMING}。
     *
     * <p>用 {@code doOnComplete} 而不是 {@code doFinally}：后者在错误与取消路径上同样会跑，
     * 会把"这一轮炸了"也记成"业务已办妥、进入确认收尾"，下一轮的提示词就会让模型
     * 向用户复述一个根本没发生的处理结果。</p>
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String sessionId = sessionId(ctx);
        return next.apply(input).doOnComplete(() -> {
            if (stageService.current(sessionId) == DialogStage.PROCESSING) {
                stageService.set(sessionId, DialogStage.CONFIRMING);
            }
        });
    }

    /** 按本次工具调用推进阶段。 */
    private void applyToolCallSignal(String sessionId, ActingInput input) {
        if (sessionId == null || input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return;
        }
        DialogStage current = stageService.current(sessionId);
        // 已转人工是终态：坐席接手后智能体不该再把自己切回业务处理
        if (current == DialogStage.ESCALATED) {
            return;
        }
        if (containsHandoff(input)) {
            stageService.set(sessionId, DialogStage.ESCALATED);
            return;
        }
        // 信息收集工具是 COLLECTING 的可靠信号：模型调它，说明它判断"要办这件事但信息还不全"。
        // 这正是批次一留下的接口——那时槽位填充不在主链路上，只好让 COLLECTING 空着
        if (containsIntake(input)) {
            if (current != DialogStage.COLLECTING) {
                stageService.set(sessionId, DialogStage.COLLECTING);
            }
            return;
        }
        if (current != DialogStage.PROCESSING) {
            stageService.set(sessionId, DialogStage.PROCESSING);
        }
    }

    /** 本批工具调用里是否含信息收集类工具。 */
    private boolean containsIntake(ActingInput input) {
        for (ToolUseBlock use : input.toolCalls()) {
            if (use != null && RefundIntakeTools.COLLECT_REFUND_INFO.equals(use.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsHandoff(ActingInput input) {
        for (ToolUseBlock use : input.toolCalls()) {
            if (use != null && ToolConstants.TRANSFER_TO_HUMAN.equals(use.getName())) {
                return true;
            }
        }
        return false;
    }

    private String sessionId(RuntimeContext ctx) {
        return ctx == null ? null : ctx.getSessionId();
    }

    /** 顺序契约见 {@link MiddlewareOrders}：按阶段动态组装系统提示词。 */
    @Override
    public int order() {
        return MiddlewareOrders.DIALOG_STAGE;
    }
}
