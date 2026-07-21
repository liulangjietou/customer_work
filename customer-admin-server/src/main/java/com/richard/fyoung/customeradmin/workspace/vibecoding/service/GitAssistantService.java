package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.audit.AiCodingOperation;
import com.richard.fyoung.customeradmin.workspace.audit.entity.AiCodingAuditLog;
import com.richard.fyoung.customeradmin.workspace.audit.service.AiCodingAuditService;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommitMessageResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.GitDiffSummary;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PrDescriptionResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ReviewIssue;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.ReviewResult;
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
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * VibeCoding Git 助手：基于会话 workspace 相对基线的 git diff，一次性调用模型（不经过 ReAct 工具
 * 循环，纯文本生成）生成 diff 摘要 / commit message / PR description。
 *
 * <p>与 {@link VibeCodingService#stream} 共享同一份能力校验与会话目录解析，但完全不复用
 * {@code ChatService}——那条链路是多轮 ReAct 对话，会触发文件读写等工具；这里只需要模型的
 * 纯文本摘要能力，直接拿 {@link AdminAgentInstanceFactory#buildModelForAgent} 现场构建的
 * {@link Model} 一次性调用，派发到独立线程池，不占用 Tomcat 请求线程（与 {@code McpService}
 * 连通性测试同一手法）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class GitAssistantService {

    private static final Logger log = LoggerFactory.getLogger(GitAssistantService.class);
    private static final String CAPABILITY_VIBECODING = "vibecoding";
    private static final String CAPABILITY_DELIMITER = ",";
    /** 送入模型的 diff 文本上限，超过截断并提示，避免撑爆上下文。 */
    private static final int MAX_DIFF_CHARS_FOR_MODEL = 20_000;
    /** Review 场景的 diff 上限（需求 §4.2.3：超大 diff 截断并在 summary 中声明）。 */
    private static final int MAX_DIFF_CHARS_FOR_REVIEW = 100_000;
    /** AI 调用超时：review 的 diff 上限 100k 字符，模型侧分钟级耗时属常态；取值需小于前端 LLM_TIMEOUT_MS(180s)，保证超时时前端收到的是本服务的结构化错误而非 axios 超时。 */
    private static final long AI_CALL_TIMEOUT_SECONDS = 170;
    /** Review 系统提示词内置的团队规范（需求 §4.2.2，后续可由 RAG 注入团队文档替换/增强）。 */
    private static final String REVIEW_SYSTEM_PROMPT =
        "你是一名资深 Java 代码审查专家。请对以下 git diff 做代码审查，严格按团队规范逐条给出问题：\n"
        + "1. 安全：SQL 注入、硬编码密钥/密码、路径穿越、SSRF、反序列化风险；\n"
        + "2. 健壮性：NPE 防护、空集合/空字符串处理、异常处理是否恰当（禁止吞异常）；\n"
        + "3. 日志规范：只用 info/error（不用 warn）、日志文案英文、error 带错误码占位符；\n"
        + "4. 命名与可读性：命名清晰、无魔法值、职责单一；\n"
        + "5. 性能：循环内远程调用/大对象、明显低效写法。\n"
        + "只输出一个 JSON 对象，不要输出任何解释文字或 Markdown 代码块标记，结构严格为：\n"
        + "{\"issues\":[{\"severity\":\"CRITICAL|WARNING|SUGGESTION\",\"file\":\"...\",\"line\":42,"
        + "\"category\":\"SECURITY|PERFORMANCE|READABILITY|BUG|STYLE\",\"message\":\"...\",\"suggestion\":\"...\"}],"
        + "\"summary\":\"一句话总述\"}\n"
        + "line 未知时填 null；没有任何问题时 issues 返回空数组。\n\n";
    /** Review 意见的合法严重级别/分类（与前端分组着色的匹配口径一致）。 */
    private static final Set<String> REVIEW_SEVERITIES = Set.of("CRITICAL", "WARNING", "SUGGESTION");
    private static final Set<String> REVIEW_CATEGORIES = Set.of("SECURITY", "PERFORMANCE", "READABILITY", "BUG", "STYLE");
    /** 模型输出的 severity 不在合法集合内时的兜底级别（保守起见归入最低级，不制造假告警）。 */
    private static final String DEFAULT_SEVERITY = "SUGGESTION";
    /** 模型输出的 category 不在合法集合内时的兜底分类（STYLE 语义最中性）。 */
    private static final String DEFAULT_CATEGORY = "STYLE";
    private static final ExecutorService GIT_ASSISTANT_EXECUTOR = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "git-assistant-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final AiAgentMapper agentMapper;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final GitWorkspaceService gitWorkspaceService;
    private final AiCodingAuditService auditService;
    /** Review 结果 JSON 解析用（宽松：忽略模型多吐的未知字段），窄用途、不依赖容器注入。 */
    private final ObjectMapper objectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public GitAssistantService(AiAgentMapper agentMapper, AdminAgentInstanceFactory agentInstanceFactory,
                                GitWorkspaceService gitWorkspaceService, AiCodingAuditService auditService) {
        this.agentMapper = agentMapper;
        this.agentInstanceFactory = agentInstanceFactory;
        this.gitWorkspaceService = gitWorkspaceService;
        this.auditService = auditService;
    }

    /** diff 摘要：无变更时直接返回空摘要，不额外调用模型。 */
    public CompletableFuture<GitDiffSummary> diffSummary(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        // 审计条目在请求线程同步段创建（操作人依赖 Sa-Token ThreadLocal），异步链路里只补结果
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.GIT_DIFF_SUMMARY, agentCode, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            String diff = gitWorkspaceService.diffAgainstBaseline(workspace);
            List<String> changedFiles = gitWorkspaceService.changedFilesAgainstBaseline(workspace);
            if (changedFiles.isEmpty()) {
                return new GitDiffSummary("本轮对话暂无文件变更", changedFiles);
            }
            Model model = agentInstanceFactory.buildModelForAgent(agentCode);
            ModelCallOutcome outcome = callModelOnce(model,
                "请用 1~3 句话总结以下 git diff 的变更内容，直接输出总结文字，不要输出多余的解释、前缀或代码块标记：\n\n"
                    + truncateDiff(diff));
            auditService.applyUsage(audit, outcome.usage());
            return new GitDiffSummary(outcome.text(), changedFiles);
        }, GIT_ASSISTANT_EXECUTOR).orTimeout(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> rethrow(ex, ResultCode.GIT_ASSISTANT_AI_FAILED, "GIT_DIFF_SUMMARY_FAIL", agentCode, sessionId))
            .whenComplete((result, error) -> auditService.finish(audit, error));
    }

    /** 生成 commit message：无变更时直接报错，不调用模型（没有内容可总结）。 */
    public CompletableFuture<CommitMessageResponse> commitMessage(String agentCode, String sessionId, String style) {
        requireVibeCodingCapable(agentCode);
        Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.COMMIT_MESSAGE, agentCode, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            String diff = requireNonEmptyDiff(workspace);
            Model model = agentInstanceFactory.buildModelForAgent(agentCode);
            String prompt = "conventional".equals(style)
                ? "请根据以下 git diff 生成一条符合 Conventional Commits 规范的 commit message"
                    + "（标题不超过 72 个字符，必要时换行补充详细描述），直接输出 commit message 文本，"
                    + "不要输出多余解释或代码块标记：\n\ndiff:\n" + truncateDiff(diff)
                : "请根据以下 git diff 生成一条简洁的中文 commit message（一句话，不超过 50 字），"
                    + "直接输出文本，不要输出多余解释：\n\ndiff:\n" + truncateDiff(diff);
            ModelCallOutcome outcome = callModelOnce(model, prompt);
            auditService.applyUsage(audit, outcome.usage());
            return new CommitMessageResponse(outcome.text());
        }, GIT_ASSISTANT_EXECUTOR).orTimeout(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> rethrow(ex, ResultCode.GIT_ASSISTANT_AI_FAILED, "GIT_COMMIT_MESSAGE_FAIL", agentCode, sessionId))
            .whenComplete((result, error) -> auditService.finish(audit, error));
    }

    /** 生成 PR description：无变更时直接报错，不调用模型。 */
    public CompletableFuture<PrDescriptionResponse> prDescription(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.PR_DESCRIPTION, agentCode, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            String diff = requireNonEmptyDiff(workspace);
            List<String> changedFiles = gitWorkspaceService.changedFilesAgainstBaseline(workspace);
            Model model = agentInstanceFactory.buildModelForAgent(agentCode);
            String prompt = "请根据以下 git diff 生成一份 Markdown 格式的 PR 描述，必须包含四个二级标题分区："
                + "## 变更摘要、## 改动文件清单、## 影响范围、## 自检清单。直接输出 Markdown 正文，"
                + "不要输出多余解释或额外代码块包裹：\n\n变更文件：\n" + String.join("\n", changedFiles)
                + "\n\ndiff:\n" + truncateDiff(diff);
            ModelCallOutcome outcome = callModelOnce(model, prompt);
            auditService.applyUsage(audit, outcome.usage());
            return new PrDescriptionResponse(outcome.text());
        }, GIT_ASSISTANT_EXECUTOR).orTimeout(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> rethrow(ex, ResultCode.GIT_ASSISTANT_AI_FAILED, "GIT_PR_DESCRIPTION_FAIL", agentCode, sessionId))
            .whenComplete((result, error) -> auditService.finish(audit, error));
    }

    /**
     * AI 代码审查（需求 P0-2 §4.2）：对本轮 diff 一次性调用模型，输出结构化审查意见。
     * 无变更时直接抛 {@link ResultCode#NO_FILE_CHANGES}，不调用模型。
     *
     * <p>模型输出 JSON 解析失败时降级（§4.2.3）：{@code issues} 为空、模型原文放进 {@code summary}，
     * 仍返回成功结果不抛裸异常；只有模型调用本身失败/超时才归一成 {@link ResultCode#AI_REVIEW_FAILED}
     * 快速失败（"明确错误码，不吞掉"）。</p>
     */
    public CompletableFuture<ReviewResult> review(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        AiCodingAuditLog audit = auditService.begin(AiCodingOperation.REVIEW, agentCode, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            String diff = requireNonEmptyDiff(workspace);
            boolean truncated = diff.length() > MAX_DIFF_CHARS_FOR_REVIEW;
            String diffForModel = truncated ? diff.substring(0, MAX_DIFF_CHARS_FOR_REVIEW) : diff;
            Model model = agentInstanceFactory.buildModelForAgent(agentCode);
            ModelCallOutcome outcome = callModelOnce(model, REVIEW_SYSTEM_PROMPT + "git diff:\n" + diffForModel);
            auditService.applyUsage(audit, outcome.usage());
            return parseReviewResult(outcome.text(), truncated);
        }, GIT_ASSISTANT_EXECUTOR).orTimeout(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> rethrow(ex, ResultCode.AI_REVIEW_FAILED, "AI_CODE_REVIEW_FAIL", agentCode, sessionId))
            .whenComplete((result, error) -> auditService.finish(audit, error));
    }

    // ---------------------- private helpers ----------------------

    /**
     * 解析模型返回的审查 JSON。剥离可能包裹的 Markdown 代码块，截取首个 {@code {} 到末个 }} 之间的
     * JSON 主体再反序列化；解析失败或结构不完整时降级（需求 §4.2.3）：原文以 {@code summary} 返回、
     * {@code issues} 为空，不抛异常。diff 被截断时在 summary 前追加声明。
     */
    private ReviewResult parseReviewResult(String modelText, boolean diffTruncated) {
        String truncateNote = diffTruncated ? "[注意] diff 过大已截断，仅审查前 " + MAX_DIFF_CHARS_FOR_REVIEW + " 字符。" : "";
        String json = extractJsonObject(modelText);
        if (json != null) {
            try {
                ReviewResult parsed = objectMapper.readValue(json, ReviewResult.class);
                if (parsed.issues() != null) {
                    String summary = StringUtils.hasText(truncateNote)
                        ? truncateNote + " " + (parsed.summary() == null ? "" : parsed.summary())
                        : parsed.summary();
                    return new ReviewResult(normalizeIssues(parsed.issues()), summary);
                }
            } catch (Exception e) {
                log.error("[workspace] review result json parse failed, code={}", "AI_REVIEW_JSON_DEGRADE", e);
            }
        }
        // 降级：模型没吐合法 JSON，原文放进 summary 让前端仍可展示
        return new ReviewResult(List.of(), (truncateNote + " " + modelText).trim());
    }

    /**
     * 归一化 severity/category（全链路唯一防御点，前端按大写精确匹配分组着色，不再兜底）：
     * 统一 toUpperCase 后校验合法集合，模型偶发输出 critical/Critical/blocker 等非规范值时
     * 不能让该 issue 在前端静默消失——未知 severity 兜底 {@link #DEFAULT_SEVERITY}、
     * 未知 category 兜底 {@link #DEFAULT_CATEGORY}，其余字段原样保留。
     */
    private List<ReviewIssue> normalizeIssues(List<ReviewIssue> issues) {
        return issues.stream()
            .map(issue -> new ReviewIssue(
                normalizeEnumValue(issue.severity(), REVIEW_SEVERITIES, DEFAULT_SEVERITY),
                issue.file(),
                issue.line(),
                normalizeEnumValue(issue.category(), REVIEW_CATEGORIES, DEFAULT_CATEGORY),
                issue.message(),
                issue.suggestion()))
            .collect(Collectors.toList());
    }

    /** 大写归一后在合法集合内则用归一值，否则用兜底值（null/空白同样走兜底）。 */
    private String normalizeEnumValue(String value, Set<String> allowed, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : fallback;
    }

    /** 截取文本中第一个 {@code '{'} 到最后一个 {@code '}'} 之间的子串（容忍模型在 JSON 前后夹带说明/代码块标记）。 */
    private String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : null;
    }

    private String requireNonEmptyDiff(Path workspace) {
        String diff = gitWorkspaceService.diffAgainstBaseline(workspace);
        if (!StringUtils.hasText(diff)) {
            throw new BizException(ResultCode.NO_FILE_CHANGES);
        }
        return diff;
    }

    private String truncateDiff(String diff) {
        if (diff.length() <= MAX_DIFF_CHARS_FOR_MODEL) {
            return diff;
        }
        return diff.substring(0, MAX_DIFF_CHARS_FOR_MODEL) + "\n\n[diff 过长，已截断，仅展示前 "
            + MAX_DIFF_CHARS_FOR_MODEL + " 个字符]";
    }

    /** 一次性模型调用的结果：拼接文本 + token 用量（流式响应中最后一个非空 usage，可能为空）。 */
    private record ModelCallOutcome(String text, ChatUsage usage) {
    }

    /** 一次性模型调用：单条 user 消息，不带工具，取全部返回内容块中的文本拼接结果与 token 用量。 */
    private ModelCallOutcome callModelOnce(Model model, String prompt) {
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .name("user")
            .content(TextBlock.builder().text(prompt).build())
            .build();
        List<ChatResponse> responses = model.stream(List.of(userMsg), List.of(), GenerateOptions.builder().build())
            .collectList()
            .block(Duration.ofSeconds(AI_CALL_TIMEOUT_SECONDS));
        if (responses == null || responses.isEmpty()) {
            throw new BizException(ResultCode.GIT_ASSISTANT_AI_FAILED, "模型未返回任何内容");
        }
        String text = responses.stream()
            .flatMap(response -> response.getContent().stream())
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::getText)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining());
        if (!StringUtils.hasText(text)) {
            throw new BizException(ResultCode.GIT_ASSISTANT_AI_FAILED, "模型返回内容为空");
        }
        // 流式分片中 usage 通常只在最后一个分片携带（或逐片累计），取最后一个非空即最终值
        ChatUsage usage = responses.stream()
            .map(ChatResponse::getUsage)
            .filter(Objects::nonNull)
            .reduce((first, second) -> second)
            .orElse(null);
        return new ModelCallOutcome(text.trim(), usage);
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

    /**
     * {@code exceptionally} 统一出口：如果根因本就是业务异常（比如 {@link #requireNonEmptyDiff}
     * 主动抛出的 {@code NO_FILE_CHANGES}），原样抛出、保留其 {@link ResultCode}，不能一律吞成
     * AI 失败码——那样前端会把"本轮无变更"误判成"AI 生成失败"。真正的 AI 硬失败
     * （模型空响应、调用异常、超时）统一归一成 {@code targetCode}：{@link #callModelOnce} 抛的通用
     * {@link ResultCode#GIT_ASSISTANT_AI_FAILED} 也会被改写成调用端点自己的错误码（如 review 链路的
     * {@link ResultCode#AI_REVIEW_FAILED}），保证同一接口的全部 AI 失败对外只有一个码。
     */
    private <T> T rethrow(Throwable ex, ResultCode targetCode, String logCode, String agentCode, String sessionId) {
        Throwable cause = ex;
        while (cause.getCause() != null && !(cause instanceof BizException)) {
            cause = cause.getCause();
        }
        if (cause instanceof BizException bizException) {
            if (bizException.getResultCode() == ResultCode.GIT_ASSISTANT_AI_FAILED
                    && targetCode != ResultCode.GIT_ASSISTANT_AI_FAILED) {
                // callModelOnce 的通用 AI 失败码 → 端点专属错误码（消息保留，便于排查具体失败原因）
                log.error("[workspace] vibecoding git assistant failed, code={}, agentCode={}, sessionId={}",
                    logCode, agentCode, sessionId, bizException);
                throw new BizException(targetCode, bizException.getMessage());
            }
            throw bizException;
        }
        log.error("[workspace] vibecoding git assistant failed, code={}, agentCode={}, sessionId={}",
            logCode, agentCode, sessionId, ex);
        throw new BizException(targetCode, describeError(ex));
    }

    /** 沿 cause 链找到根因，CompletableFuture/supplyAsync 的多层包装会掩盖真正的错误信息。 */
    private String describeError(Throwable ex) {
        if (ex instanceof BizException bizException) {
            return bizException.getMessage();
        }
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof BizException bizException) {
            return bizException.getMessage();
        }
        if (cause instanceof TimeoutException) {
            return "AI 生成超时";
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
