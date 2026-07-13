package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
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
        String output = runGit(workspace, "diff", "--cached", "--name-only");
        List<String> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (StringUtils.hasText(line)) {
                files.add(line.trim());
            }
        }
        return files;
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
