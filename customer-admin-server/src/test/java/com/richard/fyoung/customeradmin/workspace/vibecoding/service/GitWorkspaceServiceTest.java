package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.vibecoding.dto.RollbackResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitWorkspaceService} 单测：需要本机安装 {@code git}（与 sandbox 工具执行同等前提）。
 * @author owlzhangfq@gmail.com
 */
class GitWorkspaceServiceTest {

    /** 断言用的只读 git 调用超时，与被测类的执行超时无关，取值只需覆盖一次本地 config 读取。 */
    private static final long GIT_READ_TIMEOUT_SECONDS = 15;

    private final GitWorkspaceService service = new GitWorkspaceService();

    @TempDir
    Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "# hello");
    }

    @Test
    void ensureRepo_shouldBeIdempotent() {
        service.ensureRepo(workspace);
        assertTrue(Files.isDirectory(workspace.resolve(".git")));
        // 第二次调用不应报错（.git 已存在，直接跳过）
        service.ensureRepo(workspace);
        assertTrue(Files.isDirectory(workspace.resolve(".git")));
    }

    /**
     * 本类曾在 CI 上间歇性失败：{@code @TempDir} 清理报
     * {@code NoSuchFileException: .git/objects/maintenance.lock}——不是"删不掉"，是"列到了它、删的时候
     * 已经没了"。病根在 {@code commit} 收尾时 fork 的后台 {@code git maintenance run --auto}：
     * 它建这个 lock 再自行删除，而这个进程不在 {@code runGit} 的 waitFor 范围内，与调用方遍历 workspace
     * 天然竞态。会话仓库不需要任何维护，故建仓时就把它关掉，让这个文件根本不会出现。
     *
     * <p>删掉那行 config 这里就会红——竞态本身在本机复现不出来（窗口只有几毫秒，只有 CI 的负载
     * 才拉得开），所以能守住的只有"后台维护是关的"这个事实。</p>
     */
    @Test
    void ensureRepo_shouldDisableBackgroundMaintenance() throws Exception {
        service.ensureRepo(workspace);

        assertEquals(GitWorkspaceService.MAINTENANCE_AUTO_OFF,
            readRepoConfig(GitWorkspaceService.MAINTENANCE_AUTO_KEY),
            "会话仓库必须关闭 git 后台自动维护，否则 commit 之后仍有后台进程在动 .git/objects/");
    }

    /** 读取 workspace 自身 git 仓库的一项配置（读不到时返回空串）。 */
    private String readRepoConfig(String key) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "config", "--get", key)
            .directory(workspace.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(GIT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return output.trim();
    }

    @Test
    void diffAgainstBaseline_shouldBeEmpty_beforeAnyChange() {
        // ensureRepo 建立基线提交时，工作目录里已有的文件（如 README.md）会被一并纳入基线提交
        String diff = service.diffAgainstBaseline(workspace);
        assertEquals("", diff.trim());
        assertEquals(List.of(), service.changedFilesAgainstBaseline(workspace));
    }

    @Test
    void diffAgainstBaseline_shouldDetectNewFile_afterBaseline() throws IOException {
        service.ensureRepo(workspace);

        Files.writeString(workspace.resolve("Foo.java"), "class Foo {}");

        String diff = service.diffAgainstBaseline(workspace);
        assertTrue(diff.contains("Foo.java"), "diff 应包含新增文件名");
        assertTrue(diff.contains("class Foo {}"), "diff 应包含新增文件内容");
        assertEquals(List.of("Foo.java"), service.changedFilesAgainstBaseline(workspace));
    }

    @Test
    void diffAgainstBaseline_shouldDetectModifiedFile() throws IOException {
        Files.writeString(workspace.resolve("existing.txt"), "original");
        service.ensureRepo(workspace); // 基线提交把 existing.txt 也纳入

        Files.writeString(workspace.resolve("existing.txt"), "changed");

        String diff = service.diffAgainstBaseline(workspace);
        assertTrue(diff.contains("existing.txt"));
        assertTrue(diff.contains("-original"));
        assertTrue(diff.contains("+changed"));
    }

    @Test
    void changedFilesAgainstBaseline_shouldAccumulateAcrossMultipleCalls() throws IOException {
        service.ensureRepo(workspace);

        Files.writeString(workspace.resolve("a.txt"), "a");
        assertEquals(List.of("a.txt"), service.changedFilesAgainstBaseline(workspace));

        Files.writeString(workspace.resolve("b.txt"), "b");
        // 基线不变，diff 是"相对基线的全部累计变更"，第二次调用应同时看到 a.txt 和 b.txt
        List<String> changed = service.changedFilesAgainstBaseline(workspace);
        assertEquals(2, changed.size());
        assertTrue(changed.contains("a.txt"));
        assertTrue(changed.contains("b.txt"));
    }

    @Test
    void rollback_shouldDeleteNewUntrackedFiles() throws IOException {
        service.ensureRepo(workspace); // README.md 纳入 baseline

        Files.writeString(workspace.resolve("New.java"), "class New {}");
        Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(workspace.resolve("sub/Nested.java"), "class Nested {}");

        RollbackResult result = service.rollbackToBaseline(workspace);

        assertTrue(result.restoredFiles().isEmpty(), "无已跟踪文件变更，restored 应为空");
        assertTrue(result.deletedFiles().contains("New.java"));
        assertTrue(result.deletedFiles().contains("sub/Nested.java"));
        assertFalse(Files.exists(workspace.resolve("New.java")), "新增文件应被删除");
        assertFalse(Files.exists(workspace.resolve("sub")), "新增文件产生的空目录应被清理");
    }

    @Test
    void rollback_shouldRestoreModifiedTrackedFiles() throws IOException {
        Files.writeString(workspace.resolve("existing.txt"), "original");
        service.ensureRepo(workspace); // existing.txt 纳入 baseline

        Files.writeString(workspace.resolve("existing.txt"), "changed");

        RollbackResult result = service.rollbackToBaseline(workspace);

        assertTrue(result.restoredFiles().contains("existing.txt"));
        assertTrue(result.deletedFiles().isEmpty());
        assertEquals("original", Files.readString(workspace.resolve("existing.txt")), "修改应还原到 baseline 内容");
    }

    @Test
    void rollback_shouldRestoreDeletedTrackedFiles() throws IOException {
        Files.writeString(workspace.resolve("keep.txt"), "keep me");
        service.ensureRepo(workspace); // keep.txt 纳入 baseline

        Files.delete(workspace.resolve("keep.txt")); // 会话内误删已跟踪文件

        RollbackResult result = service.rollbackToBaseline(workspace);

        assertTrue(result.restoredFiles().contains("keep.txt"));
        assertTrue(Files.exists(workspace.resolve("keep.txt")), "被删除的已跟踪文件应恢复");
        assertEquals("keep me", Files.readString(workspace.resolve("keep.txt")));
    }

    @Test
    void rollback_shouldHandleMixedChangesAndCoexistWithDiffCalls() throws IOException {
        Files.writeString(workspace.resolve("mod.txt"), "v1");
        service.ensureRepo(workspace);

        Files.writeString(workspace.resolve("mod.txt"), "v2");      // 修改
        Files.writeString(workspace.resolve("added.txt"), "new");   // 新增
        // 先调一次 diff（内部会 git add -A 把新增文件暂存），验证回滚仍能正确分类
        service.changedFilesAgainstBaseline(workspace);

        RollbackResult result = service.rollbackToBaseline(workspace);

        assertTrue(result.restoredFiles().contains("mod.txt"));
        assertTrue(result.deletedFiles().contains("added.txt"));
        assertEquals("v1", Files.readString(workspace.resolve("mod.txt")));
        assertFalse(Files.exists(workspace.resolve("added.txt")));
    }

    @Test
    void rollback_shouldBeIdempotent() throws IOException {
        service.ensureRepo(workspace);
        Files.writeString(workspace.resolve("Foo.java"), "class Foo {}");

        service.rollbackToBaseline(workspace);
        // 第二次回滚：已是 baseline 状态，两个清单均为空且不报错
        RollbackResult second = service.rollbackToBaseline(workspace);
        assertTrue(second.restoredFiles().isEmpty());
        assertTrue(second.deletedFiles().isEmpty());
    }

    @Test
    void rollback_shouldNotBreakBaselineForSubsequentDiff() throws IOException {
        service.ensureRepo(workspace);
        Files.writeString(workspace.resolve("Foo.java"), "class Foo {}");
        service.rollbackToBaseline(workspace);

        // 回滚后 baseline 未被破坏，可继续建立新变更并被 diff 感知
        Files.writeString(workspace.resolve("Bar.java"), "class Bar {}");
        assertEquals(List.of("Bar.java"), service.changedFilesAgainstBaseline(workspace));
    }

    @Test
    void rollback_shouldFastFail_whenBaselineMissing() {
        // 未 ensureRepo，workspace 下无 .git → baseline 缺失，必须 fast fail 而非向上查找父级仓库
        BizException ex = assertThrows(BizException.class, () -> service.rollbackToBaseline(workspace));
        assertEquals(ResultCode.ROLLBACK_BASELINE_MISSING, ex.getResultCode());
    }
}
