package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.config.AdminCollaborationProperties;
import com.richard.fyoung.customeradmin.workspace.audit.AiCodingOperation;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatNodeKind;
import com.richard.fyoung.customeradmin.workspace.chat.dto.ChatStreamChunk;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RoleStageEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 多 Agent 协作编程（P3-1 降级版）：把一次需求输入串成"需求分析 → 方案设计 → 编码实现 → 自测审查"
 * 多角色顺序流水。降级说明见 {@link AdminCollaborationProperties}。
 *
 * <h3>编排思路（复用项目自研顺序编排）</h3>
 * 参照 {@code customer-work-starter} 的 {@code MultiAgentOrchestrator#sequential}——各角色输出
 * 作为下一角色输入逐步细化。本类不引入框架 SubAgent/Pipeline，只用一次性模型调用（PLAN/REVIEW 角色）
 * 与既有 VibeCoding 沙箱流（CODING 角色）在编排层串联：
 * <ul>
 *   <li><b>PLAN 角色</b>（需求分析/方案设计）：一次性模型调用输出文本，产出经 {@code role_stage} 事件推送，
 *       并累积进上下文供下游角色参考；</li>
 *   <li><b>CODING 角色</b>（编码实现）：委托 {@link VibeCodingService#stream} 走既有沙箱/file_change/
 *       test_report 链路，<b>不另起文件写入通道</b>；</li>
 *   <li><b>REVIEW 角色</b>（自测审查）：对本轮 git diff 一次性模型审查，产出经 {@code role_stage} 推送。</li>
 * </ul>
 *
 * <h3>失败语义（fast fail）</h3>
 * 任一角色失败 → 发一条 {@code STATUS_FAILED} 的 {@code role_stage} 事件明确报错并<b>中断整条流水</b>
 * （后续角色不再执行），审计记 {@link ResultCode#COLLAB_ROLE_FAILED}，绝不静默跳过。
 * @author owlzhangfq@gmail.com
 */
@Service
public class CollaborativeCodingService {

    private static final Logger log = LoggerFactory.getLogger(CollaborativeCodingService.class);
    private static final String CAPABILITY_VIBECODING = "vibecoding";
    private static final String CAPABILITY_DELIMITER = ",";
    private static final String ERR_ROLE_FAILED = "COLLAB-ROLE-FAILED";
    /** 送入审查角色的 diff 上限，超过截断，避免撑爆上下文。 */
    private static final int MAX_DIFF_CHARS_FOR_REVIEW = 60_000;

    private final AiAgentMapper agentMapper;
    private final AdminCollaborationProperties properties;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final VibeCodingService vibeCodingService;
    private final GitWorkspaceService gitWorkspaceService;
    private final AiCodingAuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CollaborativeCodingService(AiAgentMapper agentMapper, AdminCollaborationProperties properties,
                                      AdminAgentInstanceFactory agentInstanceFactory,
                                      VibeCodingService vibeCodingService,
                                      GitWorkspaceService gitWorkspaceService,
                                      AiCodingAuditService auditService) {
        this.agentMapper = agentMapper;
        this.properties = properties;
        this.agentInstanceFactory = agentInstanceFactory;
        this.vibeCodingService = vibeCodingService;
        this.gitWorkspaceService = gitWorkspaceService;
        this.auditService = auditService;
    }

    /**
     * 协作模式流式对话：按配置的角色顺序流水处理一次需求输入。
     *
     * <p>请求线程同步段完成：能力校验、角色校验、模型构建、审计条目创建（操作人取自 Sa-Token
     * ThreadLocal，与 {@link VibeCodingService#stream} 同一手法）。各角色的实际模型调用/沙箱执行
     * 在订阅后于 reactor 线程按序进行。</p>
     */
    /** 协作流水（未指定执行模式）：保留三参签名，回落全局模式语义。 */
    public Flux<ChatStreamChunk> stream(String agentCode, String sessionId, String userText) {
        return stream(agentCode, sessionId, userText, null);
    }

    public Flux<ChatStreamChunk> stream(String agentCode, String sessionId, String userText, String mode) {
        requireVibeCodingCapable(agentCode);
        List<AdminCollaborationProperties.Role> roles = properties.effectiveRoles();
        if (CollectionUtils.isEmpty(roles)) {
            throw new BizException(ResultCode.COLLAB_NO_ROLES);
        }
        String safeSession = StringUtils.hasText(sessionId) ? sessionId : "default";
        // 一次性构建模型供 PLAN/REVIEW 角色复用（CODING 角色内部由 VibeCodingService 自行构建带工具的 Agent）
        Model model = agentInstanceFactory.buildModelForAgent(agentCode);
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.COLLAB_STREAM, agentCode, safeSession);

        // 顺序流水共享的可变状态（Flux.concat 严格串行，无并发写）：
        AtomicReference<String> context = new AtomicReference<>("【用户原始需求】\n" + userText);
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicInteger inputTokens = new AtomicInteger(0);
        AtomicInteger outputTokens = new AtomicInteger(0);
        AtomicInteger totalTokens = new AtomicInteger(0);
        TokenSink tokens = new TokenSink(inputTokens, outputTokens, totalTokens);

        List<Flux<ChatStreamChunk>> stageFluxes = new ArrayList<>();
        int total = roles.size();
        for (int i = 0; i < total; i++) {
            AdminCollaborationProperties.Role role = roles.get(i);
            int index = i + 1;
            stageFluxes.add(roleFlux(agentCode, safeSession, userText, role, index, total, model, context, aborted, tokens, mode));
        }

        log.info("[collab] collaboration pipeline started, agentCode={}, sessionId={}, roles={}",
            agentCode, safeSession, roles.stream().map(AdminCollaborationProperties.Role::getName).collect(Collectors.toList()));

        return Flux.concat(stageFluxes).doFinally(signal -> {
            audit.setInputTokens(inputTokens.get() > 0 ? inputTokens.get() : null);
            audit.setOutputTokens(outputTokens.get() > 0 ? outputTokens.get() : null);
            audit.setTotalTokens(totalTokens.get() > 0 ? totalTokens.get() : null);
            auditService.applyChangedFiles(audit, safeChangedFiles(agentCode, safeSession));
            String errorCode = aborted.get() ? ResultCode.COLLAB_ROLE_FAILED.name()
                : (signal == SignalType.ON_COMPLETE ? null : "COLLAB_STREAM_" + signal.name());
            auditService.finish(audit, errorCode);
            log.info("[collab] collaboration pipeline finished, agentCode={}, sessionId={}, aborted={}",
                agentCode, safeSession, aborted.get());
        });
    }

    /** 按角色类型分派构建单个阶段的事件流。 */
    private Flux<ChatStreamChunk> roleFlux(String agentCode, String sessionId, String userText,
                                           AdminCollaborationProperties.Role role, int index, int total,
                                           Model model, AtomicReference<String> context,
                                           AtomicBoolean aborted, TokenSink tokens, String mode) {
        String type = normalizeType(role.getType());
        if (RoleStageEvent.TYPE_CODING.equals(type)) {
            return codingRoleFlux(agentCode, sessionId, role, index, total, context, aborted, mode);
        }
        // PLAN / REVIEW 均为一次性模型调用，仅提示词构造不同
        return oneShotRoleFlux(agentCode, sessionId, userText, role, type, index, total, model, context, aborted, tokens);
    }

    /**
     * PLAN/REVIEW 角色：START 事件 → 一次性模型调用（独立线程，超时受控） → DONE 事件（携带文本产出）。
     * 失败则 fast fail：发 FAILED 事件并置 aborted，后续角色的 defer 会短路为空。
     */
    private Flux<ChatStreamChunk> oneShotRoleFlux(String agentCode, String sessionId, String userText,
                                                  AdminCollaborationProperties.Role role, String type,
                                                  int index, int total, Model model,
                                                  AtomicReference<String> context, AtomicBoolean aborted,
                                                  TokenSink tokens) {
        return Flux.defer(() -> {
            if (aborted.get()) {
                return Flux.empty();
            }
            Mono<ChatStreamChunk> work = Mono.fromCallable(() -> {
                String prompt = RoleStageEvent.TYPE_REVIEW.equals(type)
                    ? buildReviewPrompt(agentCode, sessionId, role, context.get())
                    : buildPlanPrompt(role, context.get());
                OneShotOutcome outcome = callModelOnce(model, prompt);
                tokens.accumulate(outcome.usage());
                // 累积角色产出，供下游角色参考（顺序执行，无并发）
                context.set(context.get() + "\n\n【" + role.getName() + "】\n" + outcome.text());
                log.info("[collab] role finished, agentCode={}, role={}, type={}, index={}/{}",
                    agentCode, role.getName(), type, index, total);
                return stageChunk(RoleStageEvent.done(role.getName(), type, index, total, outcome.text()));
            }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(err -> {
                    aborted.set(true);
                    log.error("[collab] role failed, code={}, agentCode={}, role={}, index={}/{}",
                        ERR_ROLE_FAILED, agentCode, role.getName(), index, total, err);
                    return Mono.just(stageChunk(RoleStageEvent.failed(role.getName(), type, index, total,
                        "该角色执行失败：" + describeError(err))));
                });
            return Flux.concat(Flux.just(stageChunk(RoleStageEvent.start(role.getName(), type, index, total))), work);
        });
    }

    /**
     * CODING 角色：START 事件 → 委托 {@link VibeCodingService#stream} 走既有沙箱链路（file_change/
     * test_report/正文增量原样透传）→ DONE 事件。编码提示词内嵌上游需求与设计上下文。
     *
     * <p>注：委托的 VibeCoding 流在订阅时（reactor 线程）自建 CHAT_STREAM 审计，此时无 Sa-Token 上下文，
     * 操作人留空——这是 SSE 异步链路的既有降级（见 {@code AiCodingAuditService} 埋点约定），协作流水整体
     * 的操作人由本类 COLLAB_STREAM 审计在请求线程如实记录。</p>
     */
    private Flux<ChatStreamChunk> codingRoleFlux(String agentCode, String sessionId,
                                                 AdminCollaborationProperties.Role role, int index, int total,
                                                 AtomicReference<String> context, AtomicBoolean aborted, String mode) {
        return Flux.defer(() -> {
            if (aborted.get()) {
                return Flux.empty();
            }
            String codingPrompt = buildCodingPrompt(role, context.get());
            Flux<ChatStreamChunk> coding = vibeCodingService.stream(agentCode, sessionId, codingPrompt, mode)
                .onErrorResume(err -> {
                    aborted.set(true);
                    log.error("[collab] coding role failed, code={}, agentCode={}, role={}",
                        ERR_ROLE_FAILED, agentCode, role.getName(), err);
                    return Flux.just(stageChunk(RoleStageEvent.failed(role.getName(), RoleStageEvent.TYPE_CODING,
                        index, total, "编码阶段执行失败：" + describeError(err))));
                });
            Flux<ChatStreamChunk> done = Flux.defer(() -> aborted.get() ? Flux.empty()
                : Flux.just(stageChunk(RoleStageEvent.done(role.getName(), RoleStageEvent.TYPE_CODING, index, total, null))));
            return Flux.concat(
                Flux.just(stageChunk(RoleStageEvent.start(role.getName(), RoleStageEvent.TYPE_CODING, index, total))),
                coding, done);
        });
    }

    // ---------------------- prompt builders ----------------------

    private String buildPlanPrompt(AdminCollaborationProperties.Role role, String context) {
        return role.getPrompt() + "\n\n[上游上下文]\n" + context;
    }

    private String buildCodingPrompt(AdminCollaborationProperties.Role role, String context) {
        return role.getPrompt() + "\n\n请严格依据以下需求与设计上下文实现：\n" + context;
    }

    /** 审查角色提示词：角色指令 + 上游上下文 + 本轮真实 git diff（截断防溢出）。 */
    private String buildReviewPrompt(String agentCode, String sessionId,
                                     AdminCollaborationProperties.Role role, String context) {
        Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        String diff = gitWorkspaceService.diffAgainstBaseline(workspace);
        String diffSection = StringUtils.hasText(diff)
            ? truncate(diff, MAX_DIFF_CHARS_FOR_REVIEW)
            : "（本轮无代码变更，请据上游设计评估实现完整性）";
        return role.getPrompt() + "\n\n[上游上下文]\n" + context + "\n\n[本轮 git diff]\n" + diffSection;
    }

    // ---------------------- model call ----------------------

    /** 一次性模型调用结果：文本 + token 用量（可空）。 */
    private record OneShotOutcome(String text, ChatUsage usage) {
    }

    /** 一次性模型调用：单条 user 消息、不带工具，收集流式响应文本与末次 usage（与 GitAssistantService 同手法）。 */
    private OneShotOutcome callModelOnce(Model model, String prompt) {
        Msg userMsg = Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(prompt).build()).build();
        List<ChatResponse> responses = model.stream(List.of(userMsg), List.of(), GenerateOptions.builder().build())
            .collectList()
            .block(Duration.ofSeconds(properties.getRoleTimeoutSeconds()));
        if (CollectionUtils.isEmpty(responses)) {
            throw new BizException(ResultCode.COLLAB_ROLE_FAILED, "模型未返回任何内容");
        }
        String text = responses.stream()
            .flatMap(response -> response.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining());
        if (!StringUtils.hasText(text)) {
            throw new BizException(ResultCode.COLLAB_ROLE_FAILED, "模型返回内容为空");
        }
        ChatUsage usage = responses.stream()
            .map(ChatResponse::getUsage)
            .filter(Objects::nonNull)
            .reduce((first, second) -> second)
            .orElse(null);
        return new OneShotOutcome(text.trim(), usage);
    }

    // ---------------------- helpers ----------------------

    private ChatStreamChunk stageChunk(RoleStageEvent event) {
        try {
            return new ChatStreamChunk(ChatNodeKind.ROLE_STAGE, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.error("[collab] role_stage json serialize failed, code={}, role={}, status={}",
                "COLLAB-STAGE-JSON-FAIL", event.role(), event.status(), e);
            return new ChatStreamChunk(ChatNodeKind.ROLE_STAGE, "{}");
        }
    }

    /** 角色类型归一：未知/空一律视为 PLAN（最保守，纯文本一次性调用，不触发沙箱）。 */
    static String normalizeType(String type) {
        if (RoleStageEvent.TYPE_CODING.equalsIgnoreCase(type)) {
            return RoleStageEvent.TYPE_CODING;
        }
        if (RoleStageEvent.TYPE_REVIEW.equalsIgnoreCase(type)) {
            return RoleStageEvent.TYPE_REVIEW;
        }
        return RoleStageEvent.TYPE_PLAN;
    }

    private List<String> safeChangedFiles(String agentCode, String sessionId) {
        try {
            Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
            return gitWorkspaceService.changedFilesAgainstBaseline(workspace);
        } catch (Exception e) {
            log.error("[collab] resolve changed files for audit failed, code={}, agentCode={}",
                "COLLAB-AUDIT-FILES-FAIL", agentCode, e);
            return List.of();
        }
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n\n[diff 过长已截断]";
    }

    private String describeError(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    private void requireVibeCodingCapable(String agentCode) {
        AiAgent agent = agentMapper.selectOne(new LambdaQueryWrapper<AiAgent>().eq(AiAgent::getAgentCode, agentCode));
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + agentCode);
        }
        List<String> capabilities = StringUtils.hasText(agent.getCapabilities())
            ? Arrays.asList(agent.getCapabilities().split(CAPABILITY_DELIMITER)) : List.of();
        if (!capabilities.contains(CAPABILITY_VIBECODING)) {
            throw new BizException(ResultCode.AGENT_CAPABILITY_NOT_SUPPORTED, "智能体未开启 vibecoding 能力: " + agentCode);
        }
    }

    /** token 累加器：把每次一次性调用的 usage 累进流水总量（null 安全）。 */
    private record TokenSink(AtomicInteger input, AtomicInteger output, AtomicInteger total) {
        void accumulate(ChatUsage usage) {
            if (usage == null) {
                return;
            }
            input.addAndGet(usage.getInputTokens());
            output.addAndGet(usage.getOutputTokens());
            total.addAndGet(usage.getTotalTokens());
        }
    }
}
