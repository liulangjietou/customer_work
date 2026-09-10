package com.richard.fyoung.customerwork.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>释放契约门禁</b>：测试里真建出来的 {@code HarnessAgent} 必须被释放。
 *
 * <h3>守的是什么</h3>
 * <p>AgentScope 2.0.3 起，{@code HarnessAgent#close()} 的头两件事是
 * {@code SessionTree.awaitMirrorQuiescence} 与 {@code MemoryBackgroundTasks.awaitQuiescence}
 * ——等会话镜像与记忆刷写的后台线程停下来（这两个 API 都是 2.0.3 新增的）。
 * 不调 close，那些线程就会在测试方法返回之后继续往 workspace 里写。</p>
 *
 * <p>后果在 {@code @TempDir} 上最直接：JUnit 收尾删临时目录时撞上正在写的文件，报
 * {@code IOException: Failed to delete temp directory}，suppressed 是
 * {@code DirectoryNotEmptyException}。这在 {@code SubagentEventForwardingTest} 上真实发生过——
 * 三轮 CI 炸了两轮，而且<b>两次挂的不是同一个用例</b>：谁最后跑完谁触发清理，谁就背锅，
 * 看起来像随机的测试不稳定，其实根因唯一。</p>
 *
 * <h3>为什么必须是结构断言</h3>
 * <p>这个竞态<b>本机复现不出来</b>：实测把释放去掉之后，本机连"关闭后主动删一次 workspace"
 * 这种直接断言都照样是绿的——机器快，后台在删之前就写完了。也就是说任何运行时断言在这里都可能
 * 恒真，只有 CI 的慢机器才照得出来。同一形状本仓库记过一次（PR #157 的 git 后台维护锁，
 * 本机与容器 1400 轮都没复现）。</p>
 *
 * <p>因此改用源码扫描对结构本身下断言：建了就必须释放，不依赖任何运行时时序，
 * 新写一个不释放的测试当场红。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class HarnessAgentReleaseContractTest {

    /** 真建一个 HarnessAgent 的标志性调用（mock 不算——mock 不会起后台线程）。 */
    private static final String BUILD_MARKER = "HarnessAgent.Builder.fromAgent";

    /** 认可的释放证据。 */
    private static final List<String> RELEASE_MARKERS = List.of(
        "try (HarnessAgent",          // try-with-resources
        "AgentResourceCloser.closeQuietly",
        ".close()");

    private static final List<String> TEST_SOURCE_ROOTS = List.of(
        "customer-work-starter/src/test/java",
        "customer-admin-server/src/test/java",
        "customer-work-app-server/src/test/java",
        "customer-channel/src/test/java");

    @Test
    @DisplayName("真建 HarnessAgent 的测试必须释放它")
    void everyBuiltHarnessAgentIsReleased() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (Path root : existingRoots()) {
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (!source.contains(BUILD_MARKER)) {
                        continue;
                    }
                    checked++;
                    boolean released = RELEASE_MARKERS.stream().anyMatch(source::contains);
                    if (!released) {
                        offenders.add(file.getFileName().toString());
                    }
                }
            }
        }

        assertTrue(checked >= 2,
            "应至少扫描到 2 个真建 HarnessAgent 的测试，实际 " + checked + " —— 扫描路径可能失效了");

        if (!offenders.isEmpty()) {
            fail("以下测试建了 HarnessAgent 却没有释放，后台任务会在测试结束后继续写 workspace：\n"
                + String.join("\n", offenders)
                + "\n修法：try-with-resources 包住，或收集起来在 @AfterEach 里走 "
                + "AgentResourceCloser.closeQuietly。\n"
                + "本机跑不出问题不代表没问题——这个竞态只在 CI 的慢机器上暴露。");
        }
    }

    private List<Path> existingRoots() {
        List<Path> roots = new ArrayList<>();
        for (String candidate : TEST_SOURCE_ROOTS) {
            Path fromRepoRoot = Paths.get(candidate);
            Path fromModule = Paths.get("..").resolve(candidate).normalize();
            if (Files.exists(fromRepoRoot)) {
                roots.add(fromRepoRoot);
            } else if (Files.exists(fromModule)) {
                roots.add(fromModule);
            }
        }
        return roots;
    }
}
