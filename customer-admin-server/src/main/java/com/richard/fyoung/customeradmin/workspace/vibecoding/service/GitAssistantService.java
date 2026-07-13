package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.runtime.AdminAgentInstanceFactory;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.CommitMessageResponse;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.GitDiffSummary;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.PrDescriptionResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
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
    private static final long AI_CALL_TIMEOUT_SECONDS = 30;
    private static final ExecutorService GIT_ASSISTANT_EXECUTOR = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "git-assistant-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final AiAgentMapper agentMapper;
    private final AdminAgentInstanceFactory agentInstanceFactory;
    private final GitWorkspaceService gitWorkspaceService;

    public GitAssistantService(AiAgentMapper agentMapper, AdminAgentInstanceFactory agentInstanceFactory,
                                GitWorkspaceService gitWorkspaceService) {
        this.agentMapper = agentMapper;
        this.agentInstanceFactory = agentInstanceFactory;
        this.gitWorkspaceService = gitWorkspaceService;
    }

    /** diff 摘要：无变更时直接返回空摘要，不额外调用模型。 */
    public CompletableFuture<GitDiffSummary> diffSummary(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            String diff = gitWorkspaceService.diffAgainstBaseline(workspace);
            List<String> changedFiles = gitWorkspaceService.changedFilesAgainstBaseline(workspace);
            if (changedFiles.isEmpty()) {
                return new GitDiffSummary("本轮对话暂无文件变更", changedFiles);
            }
            Model model = agentInstanceFactory.buildModelForAgent(agentCode);
            String summary = callModelOnce(model,
                "请用 1~3 句话总结以下 git diff 的变更内容，直接输出总结文字，不要输出多余的解释、前缀或代码块标记：\n\n"
                    + truncateDiff(diff));
            return new GitDiffSummary(summary, changedFiles);
        }, GIT_ASSISTANT_EXECUTOR).orTimeout(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> rethrow(ex, "GIT_DIFF_SUMMARY_FAIL", agentCode, sessionId));
    }

    /** 生成 commit message：无变更时直接报错，不调用模型（没有内容可总结）。 */
    public CompletableFuture<CommitMessageResponse> commitMessage(String agentCode, String sessionId, String style) {
        requireVibeCodingCapable(agentCode);
        Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            String diff = requireNonEmptyDiff(workspace);
            Model model = agentInstanceFactory.buildModelForAgent(agentCode);
            String prompt = "conventional".equals(style)
                ? "请根据以下 git diff 生成一条符合 Conventional Commits 规范的 commit message"
                    + "（标题不超过 72 个字符，必要时换行补充详细描述），直接输出 commit message 文本，"
                    + "不要输出多余解释或代码块标记：\n\ndiff:\n" + truncateDiff(diff)
                : "请根据以下 git diff 生成一条简洁的中文 commit message（一句话，不超过 50 字），"
                    + "直接输出文本，不要输出多余解释：\n\ndiff:\n" + truncateDiff(diff);
            return new CommitMessageResponse(callModelOnce(model, prompt));
        }, GIT_ASSISTANT_EXECUTOR).orTimeout(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> rethrow(ex, "GIT_COMMIT_MESSAGE_FAIL", agentCode, sessionId));
    }

    /** 生成 PR description：无变更时直接报错，不调用模型。 */
    public CompletableFuture<PrDescriptionResponse> prDescription(String agentCode, String sessionId) {
        requireVibeCodingCapable(agentCode);
        Path workspace = agentInstanceFactory.resolveSessionWorkspace(agentCode, sessionId);
        return CompletableFuture.supplyAsync(() -> {
            String diff = requireNonEmptyDiff(workspace);
            List<String> changedFiles = gitWorkspaceService.changedFilesAgainstBaseline(workspace);
            Model model = agentInstanceFactory.buildModelForAgent(agentCode);
            String prompt = "请根据以下 git diff 生成一份 Markdown 格式的 PR 描述，必须包含四个二级标题分区："
                + "## 变更摘要、## 改动文件清单、## 影响范围、## 自检清单。直接输出 Markdown 正文，"
                + "不要输出多余解释或额外代码块包裹：\n\n变更文件：\n" + String.join("\n", changedFiles)
                + "\n\ndiff:\n" + truncateDiff(diff);
            return new PrDescriptionResponse(callModelOnce(model, prompt));
        }, GIT_ASSISTANT_EXECUTOR).orTimeout(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> rethrow(ex, "GIT_PR_DESCRIPTION_FAIL", agentCode, sessionId));
    }

    // ---------------------- private helpers ----------------------

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

    /** 一次性模型调用：单条 user 消息，不带工具，取全部返回内容块中的文本拼接结果。 */
    private String callModelOnce(Model model, String prompt) {
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
        return text.trim();
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
     * {@link ResultCode#GIT_ASSISTANT_AI_FAILED}——那样前端会把"本轮无变更"误判成"AI 生成失败"。
     * 只有真正意料之外的异常（模型调用失败、超时等）才归一成 {@code GIT_ASSISTANT_AI_FAILED}。
     */
    private <T> T rethrow(Throwable ex, String logCode, String agentCode, String sessionId) {
        Throwable cause = ex;
        while (cause.getCause() != null && !(cause instanceof BizException)) {
            cause = cause.getCause();
        }
        if (cause instanceof BizException bizException) {
            throw bizException;
        }
        log.error("[workspace] vibecoding git assistant failed, code={}, agentCode={}, sessionId={}",
            logCode, agentCode, sessionId, ex);
        throw new BizException(ResultCode.GIT_ASSISTANT_AI_FAILED, describeError(ex));
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
