package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RollbackResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 会话 workspace 的轻量 Git 集成：把 {@code sessions/{sessionId}/} 目录现场初始化为 git 仓库，
 * 以"会话开始时的空快照"为基线提交，本轮对话产生的全部文件变更即为相对基线的 {@code git diff}。
 *
 * <p>只生成只读的 diff 信息，不做真正的业务提交——commit/push 仍由开发者在本地 IDE 完成，
 * 与需求文档 3.2 的定位一致（"辅助生成 Git 相关文本"，不代替开发者操作 Git）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class GitWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(GitWorkspaceService.class);
    private static final long GIT_TIMEOUT_SECONDS = 15;
    private static final String BASELINE_COMMIT_MESSAGE = "vibecoding-session-baseline";
    /** 会话现场建立的 git 仓库目录名，回滚前据此校验 workspace 拥有自己的仓库（防越界删父级仓库文件）。 */
    private static final String GIT_DIR_NAME = ".git";
    /** baseline 是会话内唯一提交，故 HEAD 恒等于 baseline，回滚以 HEAD 为恢复基准。 */
    private static final String HEAD_REF = "HEAD";

    /** 幂等：{@code .git} 已存在则直接返回；否则 init + 建立空基线提交，作为后续 diff 的对比基准。 */
    public void ensureRepo(Path workspace) {
        if (Files.isDirectory(workspace.resolve(".git"))) {
            return;
        }
        runGit(workspace, "init", "-q");
        runGit(workspace, "config", "user.email", "vibecoding@customer-work.local");
        runGit(workspace, "config", "user.name", "VibeCoding");
        runGit(workspace, "add", "-A");
        runGit(workspace, "commit", "-q", "--allow-empty", "-m", BASELINE_COMMIT_MESSAGE);
    }

    /** 相对基线提交的完整 unified diff 文本（含新增/修改/删除的所有文件）。 */
    public String diffAgainstBaseline(Path workspace) {
        ensureRepo(workspace);
        runGit(workspace, "add", "-A");
        return runGit(workspace, "diff", "--cached");
    }

    /** 相对基线提交发生变更的文件相对路径清单。 */
    public List<String> changedFilesAgainstBaseline(Path workspace) {
        ensureRepo(workspace);
        runGit(workspace, "add", "-A");
        return splitLines(runGit(workspace, "diff", "--cached", "--name-only"));
    }

    /**
     * 会话级一键回滚：把 workspace 恢复到 baseline（首个空提交）状态——已跟踪文件 checkout 回 baseline
     * 内容（本会话内的修改/删除被还原），新增未跟踪文件连同空目录清理删除，{@code .git} 目录本身保留。
     *
     * <p>幂等：无变更时两个清单均为空、不报错；重复调用恢复到同一 baseline。</p>
     *
     * <p><b>安全约束（全链路唯一防御点）</b>：破坏性的 {@code checkout}/{@code clean} 操作必须严格限定在
     * 本 workspace 自己的 git 仓库内。执行前强校验 workspace 根目录下存在 {@code .git}——若缺失（会话
     * 从未建立 baseline），git 会向上查找父级仓库，{@code clean -fd} 可能误删项目其它未跟踪文件；因此
     * 此处 fast fail，既是"baseline 缺失"的业务校验，也是"越界删文件"的安全兜底。</p>
     *
     * @return 恢复的已跟踪文件清单 + 删除的新增文件清单
     */
    public RollbackResult rollbackToBaseline(Path workspace) {
        if (!Files.isDirectory(workspace.resolve(GIT_DIR_NAME))) {
            log.error("[workspace] rollback baseline missing, code={}, workspace={}", "GIT_BASELINE_MISSING", workspace);
            throw new BizException(ResultCode.ROLLBACK_BASELINE_MISSING);
        }
        // 归一化 index：清除历史 diff 调用（changedFilesAgainstBaseline 会 git add -A）遗留的暂存，
        // 让新增文件回到 untracked、修改回到 unstaged，后续分类（restored=已跟踪变更 / deleted=未跟踪新增）才准确
        runGit(workspace, "reset", "-q");
        List<String> restoredFiles = splitLines(runGit(workspace, "diff", HEAD_REF, "--name-only"));
        List<String> deletedFiles = splitLines(runGit(workspace, "ls-files", "--others", "--exclude-standard"));
        // 仅在确有跟踪变更时 checkout，避免空 baseline 且无变更时 pathspec '.' 不匹配报错
        if (!restoredFiles.isEmpty()) {
            runGit(workspace, "checkout", HEAD_REF, "--", ".");
        }
        // 清理新增未跟踪文件与由此产生的空目录
        if (!deletedFiles.isEmpty()) {
            runGit(workspace, "clean", "-qfd");
        }
        return new RollbackResult(restoredFiles, deletedFiles);
    }

    /** git 输出按行拆分为非空 trim 后的相对路径清单。 */
    private List<String> splitLines(String output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (StringUtils.hasText(line)) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    private String runGit(Path workspace, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("[workspace] git command timeout, code={}, args={}", "GIT_COMMAND_TIMEOUT", command);
                throw new BizException(ResultCode.GIT_COMMAND_FAILED, "git 命令执行超时: " + String.join(" ", args));
            }
            if (process.exitValue() != 0) {
                log.error("[workspace] git command failed, code={}, args={}, output={}",
                    "GIT_COMMAND_FAIL", command, output);
                throw new BizException(ResultCode.GIT_COMMAND_FAILED, "git 命令执行失败: " + String.join(" ", args));
            }
            return output;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[workspace] git command exec error, code={}, args={}", "GIT_COMMAND_EXEC_ERROR", command, e);
            throw new BizException(ResultCode.GIT_COMMAND_FAILED, "git 命令执行异常: " + String.join(" ", args));
        }
    }
}
