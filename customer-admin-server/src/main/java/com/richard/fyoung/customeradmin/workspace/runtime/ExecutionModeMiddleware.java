package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionMode;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionModePolicy;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ExecutionModeRegistry;
import com.richard.fyoung.customeradmin.workspace.runtime.mode.ModeDecision;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanAction;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PlanEvent;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService;
import com.richard.fyoung.customeradmin.workspace.vibecoding.service.PlanConfirmationService.PlanTicket;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 执行模式闸门中间件（原 {@code PlanConfirmationMiddleware}，职责从"HITL 开关"升级为"五档模式闸门"）：
 * 挂在 {@link MiddlewareBase#onActing}，工具执行前从 {@link ExecutionModeRegistry} 取会话模式（取不到则
 * 回落全局 {@code permissionMode}），经 {@link ExecutionModePolicy} 决策后执行——PASS 透传 / CONFIRM 走
 * {@link PlanConfirmationService} 挂起闭环 / PLAN_BLOCK 拦改 mutating 工具入参后放行。
 *
 * <p>框架耦合（{@code onActing}/{@code ActingInput}/{@code next.apply}）只落在本层，模式决策纯函数、
 * 拦改改写复用 {@link SandboxRiskDetector}。Agent 实例被 {@code AgentInstanceCache} 缓存复用，本中间件
 * <b>运行时</b>读 registry，不在构建期固化模式。</p>
 *
 * <h3>与 {@code SandboxGuardMiddleware} 的关系（不变）</h3>
 * 本中间件注册在护栏<b>之前</b>（outermost），先看到原始命令：批准/放行后 {@code next.apply(原始)} 仍流经护栏，
 * catastrophic 命令即便被批准也被护栏改写为占位——护栏是最后防线不被绕过。BYPASS 档纯透传，行为等价于改造前。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ExecutionModeMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ExecutionModeMiddleware.class);
    private static final String REJECT_NOTICE = "[PLAN_REJECTED_BY_USER]";
    /** PLAN 档拦改 mutating 工具入参的占位提示（Agent 拿到该结果后转为输出实现方案，而非执行）。 */
    private static final String PLAN_BLOCK_NOTICE =
        "[PLAN_MODE_BLOCKED] Plan mode: mutating tools are disabled. Present an implementation plan instead of executing.";

    private final AdminSandboxProperties properties;
    private final SandboxRiskDetector riskDetector;
    private final ExecutionModeRegistry executionModeRegistry;
    private final ExecutionModePolicy executionModePolicy;
    private final PlanConfirmationService planConfirmationService;

    public ExecutionModeMiddleware(AdminSandboxProperties properties, SandboxRiskDetector riskDetector,
                                   ExecutionModeRegistry executionModeRegistry, ExecutionModePolicy executionModePolicy,
                                   PlanConfirmationService planConfirmationService) {
        this.properties = properties;
        this.riskDetector = riskDetector;
        this.executionModeRegistry = executionModeRegistry;
        this.executionModePolicy = executionModePolicy;
        this.planConfirmationService = planConfirmationService;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return next.apply(input);
        }
        String agentCode = ctx != null ? ctx.getUserId() : null;
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        ExecutionMode mode = resolveMode(agentCode, sessionId);
        ModeDecision decision = executionModePolicy.decide(mode, toolCalls);
        switch (decision.kind()) {
            case PASS:
                return next.apply(input);
            case PLAN_BLOCK:
                // 只读研究模式：mutating 工具入参改写为提示，Agent 转为输出方案；只读工具原样放行
                return next.apply(planBlock(input, decision));
            case CONFIRM:
            default:
                return confirm(agentCode, sessionId, input, next, decision);
        }
    }

    /**
     * 会话模式解析：registry 取到即用；未指定（旧调用方/存量测试）回落到全局 {@code permissionMode} 语义——
     * BYPASS→透传（{@link ExecutionMode#BYPASS}）、HITL→按 {@link ExecutionMode#AUTO} 处理。
     */
    private ExecutionMode resolveMode(String agentCode, String sessionId) {
        ExecutionMode mode = executionModeRegistry.get(agentCode, sessionId);
        if (mode != null) {
            return mode;
        }
        return properties.isHitlMode() ? ExecutionMode.AUTO : ExecutionMode.BYPASS;
    }

    /** CONFIRM：往会话通道推 plan 事件挂起等确认，复用 {@link PlanConfirmationService} 闭环（不改闭环语义）。 */
    private Flux<AgentEvent> confirm(String agentCode, String sessionId, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next, ModeDecision decision) {
        List<PlanAction> actions = decision.actions();
        String planId = UUID.randomUUID().toString();
        String reason = actions.stream().map(PlanAction::reason).distinct().collect(Collectors.joining("；"));
        PlanEvent event = new PlanEvent(planId, actions, reason, true, properties.getHitl().getConfirmTimeoutSeconds());

        // defer + boundedElastic：future.await 是阻塞语义，放到弹性线程池，不阻塞 reactor 事件循环
        return Flux.defer(() -> planConfirmationService.submit(agentCode, sessionId, event)
                .map(ticket -> awaitDecision(ticket, input, next, decision))
                // 无活跃确认通道（无 SSE 承接）：fast fail 放行给护栏兜底，不挂死
                .orElseGet(() -> {
                    log.info("[workspace] plan confirm channel absent, pass through to guard, agentCode={}, sessionId={}",
                        agentCode, sessionId);
                    return next.apply(input);
                }))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AgentEvent> awaitDecision(PlanTicket ticket, ActingInput input,
                                           Function<ActingInput, Flux<AgentEvent>> next, ModeDecision decision) {
        Duration timeout = Duration.ofSeconds(properties.getHitl().getConfirmTimeoutSeconds());
        return Mono.fromFuture(ticket.future())
            // 超时视为拒绝（false），并补 plan_result=TIMEOUT，流正常结束而非挂死
            .timeout(timeout, Mono.fromCallable(() -> {
                planConfirmationService.timeout(ticket);
                return Boolean.FALSE;
            }))
            .flatMapMany(approved -> Boolean.TRUE.equals(approved)
                ? next.apply(input)
                : next.apply(neutralizeConfirm(input, decision)));
    }

    /**
     * CONFIRM 拒绝/超时的取消改写（沿用护栏"改写占位"手法，非目标调用原样保留）：
     * <ul>
     *   <li>{@link ModeDecision.Neutralization#ALL_TOOLS}（MANUAL）：每个工具调用的全部字符串入参整体改写；</li>
     *   <li>{@link ModeDecision.Neutralization#RISK_BASED}（AUTO/ACCEPT_EDITS）：单工具高风险精确改写风险入参，
     *       批量修改被拒时按写类工具整体取消（批量风险是聚合判定，单调用不命中 {@code isHighRisk}）。</li>
     * </ul>
     */
    private ActingInput neutralizeConfirm(ActingInput input, ModeDecision decision) {
        boolean allTools = decision.neutralization() == ModeDecision.Neutralization.ALL_TOOLS;
        boolean batchModify = decision.batchModify();
        List<ToolUseBlock> rewritten = new ArrayList<>(input.toolCalls().size());
        for (ToolUseBlock use : input.toolCalls()) {
            if (allTools) {
                rewritten.add(riskDetector.neutralizeAllStringParams(use, REJECT_NOTICE));
            } else if (riskDetector.isHighRisk(use)) {
                rewritten.add(riskDetector.neutralize(use, REJECT_NOTICE));
            } else if (batchModify && riskDetector.isWriteToolName(use.getName())) {
                rewritten.add(riskDetector.neutralizeAllStringParams(use, REJECT_NOTICE));
            } else {
                rewritten.add(use);
            }
        }
        return new ActingInput(rewritten);
    }

    /**
     * PLAN 档拦改：按决策里与工具调用一一对应的 mutating 标记，把 mutating 工具的全部字符串入参改写为
     * {@link #PLAN_BLOCK_NOTICE}，只读工具原样保留。
     */
    private ActingInput planBlock(ActingInput input, ModeDecision decision) {
        List<ToolUseBlock> toolCalls = input.toolCalls();
        List<Boolean> flags = decision.mutatingFlags();
        List<ToolUseBlock> rewritten = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolUseBlock use = toolCalls.get(i);
            boolean mutating = i < flags.size() && Boolean.TRUE.equals(flags.get(i));
            rewritten.add(mutating ? riskDetector.neutralizeAllStringParams(use, PLAN_BLOCK_NOTICE) : use);
        }
        return new ActingInput(rewritten);
    }
}
