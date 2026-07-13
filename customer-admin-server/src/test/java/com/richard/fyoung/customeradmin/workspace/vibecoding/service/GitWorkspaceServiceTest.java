package com.richard.fyoung.customeradmin.workspace.vibecoding.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitWorkspaceService} 单测：需要本机安装 {@code git}（与 sandbox 工具执行同等前提）。
 * @author owlzhangfq@gmail.com
 */
class GitWorkspaceServiceTest {

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
}
