package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
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
 * Plan Mode 人工确认中间件（需求 P1-1 HITL 核心）：挂在 {@link MiddlewareBase#onActing}，工具执行前判定高风险，
 * {@code hitl} 模式下 emit {@code plan} 事件挂起等确认，批准后原样执行、拒绝/超时则改写取消。
 *
 * <h3>与 {@code SandboxGuardMiddleware} 的关系（需求 §4.4.2.5「两者叠加」）</h3>
 * 本中间件注册在护栏<b>之前</b>（{@code builder.middleware(...)} first = outermost），先于护栏看到原始命令：
 * <ul>
 *   <li>{@code bypass} 模式：本中间件纯透传，行为完全等价于改造前——高风险由护栏静默改写兜底；</li>
 *   <li>{@code hitl} 模式：本中间件挂起询问；批准后 {@code next.apply(原始)} 仍会流经护栏，catastrophic
 *       命令（rm -rf、/etc 等）即便被批准也仍被护栏改写为占位——护栏是最后防线不被绕过。</li>
 * </ul>
 *
 * <h3>挂起实现</h3>
 * 命中高风险 → {@link PlanConfirmationService#submit}（推 plan 事件 + 登记 future）→
 * {@code Mono.fromFuture(future)} 挂起（boundedElastic，不占 reactor 事件循环线程）；超时（默认 5min）
 * 视为拒绝。批准 → {@code next.apply(原始 input)}；拒绝/超时 → {@code next.apply(改写后的 input)}，把风险入参
 * 替换为拒绝提示，让工具无害执行、Agent 拿到结果后调整方案，流正常继续（不挂死）。
 * @author owlzhangfq@gmail.com
 */
@Component
public class PlanConfirmationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PlanConfirmationMiddleware.class);
    private static final String REJECT_NOTICE = "[PLAN_REJECTED_BY_USER]";

    private final AdminSandboxProperties properties;
    private final SandboxRiskDetector riskDetector;
    private final PlanConfirmationService planConfirmationService;

    public PlanConfirmationMiddleware(AdminSandboxProperties properties, SandboxRiskDetector riskDetector,
                                      PlanConfirmationService planConfirmationService) {
        this.properties = properties;
        this.riskDetector = riskDetector;
        this.planConfirmationService = planConfirmationService;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        // bypass（默认）：纯透传，行为等价于改造前——非 vibecoding 链路与关闭 HITL 时零影响
        if (!properties.isHitlMode() || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        List<PlanAction> actions = riskDetector.assess(input.toolCalls());
        if (actions.isEmpty()) {
            return next.apply(input);
        }
        String agentCode = ctx != null ? ctx.getUserId() : null;
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        String planId = UUID.randomUUID().toString();
        String reason = actions.stream().map(PlanAction::reason).distinct().collect(Collectors.joining("；"));
        PlanEvent event = new PlanEvent(planId, actions, reason, true, properties.getHitl().getConfirmTimeoutSeconds());
        // BATCH_MODIFY 是整步聚合风险：纯批量普通写入时每个调用单独 assess 均为空（isHighRisk=false），
        // 拒绝时必须按"写类工具"整体取消，否则批量拒绝形同虚设（所有写入原样执行）
        boolean batchModify = actions.stream()
            .anyMatch(a -> SandboxRiskDetector.ACTION_BATCH_MODIFY.equals(a.type()));

        // defer + boundedElastic：future.await 是阻塞语义，放到弹性线程池，不阻塞 reactor 事件循环
        return Flux.defer(() -> planConfirmationService.submit(agentCode, sessionId, event)
                .map(ticket -> awaitDecision(ticket, input, next, batchModify))
                // 无活跃确认通道（无 SSE 承接）：fast fail 放行给护栏兜底，不挂死
                .orElseGet(() -> {
                    log.info("[workspace] plan confirm channel absent, pass through to guard, agentCode={}, sessionId={}",
                        agentCode, sessionId);
                    return next.apply(input);
                }))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AgentEvent> awaitDecision(PlanTicket ticket, ActingInput input,
                                           Function<ActingInput, Flux<AgentEvent>> next, boolean batchModify) {
        Duration timeout = Duration.ofSeconds(properties.getHitl().getConfirmTimeoutSeconds());
        return Mono.fromFuture(ticket.future())
            // 超时视为拒绝（false），并补 plan_result=TIMEOUT，流正常结束而非挂死（需求 §4.4.2）
            .timeout(timeout, Mono.fromCallable(() -> {
                planConfirmationService.timeout(ticket);
                return Boolean.FALSE;
            }))
            .flatMapMany(approved -> Boolean.TRUE.equals(approved)
                ? next.apply(input)
                : next.apply(neutralize(input, batchModify)));
    }

    /**
     * 拒绝/超时的取消改写（沿用护栏"改写占位"手法，非风险调用原样保留）：
     * <ul>
     *   <li>单工具维度高风险（删除/命令/依赖）：按风险正则精确改写风险入参；</li>
     *   <li>本步含 BATCH_MODIFY（批量修改被拒绝）：全部写类工具的字符串入参整体改写——批量风险是
     *       聚合判定，单个写调用不命中 {@code isHighRisk}，必须按写工具口径整体取消。</li>
     * </ul>
     */
    private ActingInput neutralize(ActingInput input, boolean batchModify) {
        List<ToolUseBlock> rewritten = new ArrayList<>(input.toolCalls().size());
        for (ToolUseBlock use : input.toolCalls()) {
            if (riskDetector.isHighRisk(use)) {
                rewritten.add(riskDetector.neutralize(use, REJECT_NOTICE));
            } else if (batchModify && riskDetector.isWriteToolName(use.getName())) {
                rewritten.add(riskDetector.neutralizeAllStringParams(use, REJECT_NOTICE));
            } else {
                rewritten.add(use);
            }
        }
        return new ActingInput(rewritten);
    }
}
