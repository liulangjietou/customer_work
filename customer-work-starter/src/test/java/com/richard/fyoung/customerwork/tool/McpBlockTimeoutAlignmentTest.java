package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.core.constant.McpTimeouts;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>MCP 阻塞超时门禁</b>：MCP / Higress 客户端的构建与注册调用必须显式传超时。
 *
 * <p><b>为什么需要这个测试</b>：{@code Mono.block()} 不带参数会无限等待。MCP 注册内部先
 * {@code initialize()} 再 {@code listTools()}，服务端不支持可选 SSE 长连接时 SDK 内部状态机会卡死，
 * 调用线程随之永久挂起——<b>不抛异常、不进 error 日志、不触发告警</b>，外层 try/catch 等不到任何东西。
 * 用户侧的表现只是"界面没有响应"。</p>
 *
 * <p>这个故障已经真实发生过一次：后台工作台链路当时踩到并修复，而它参考的来源——客服端
 * {@code McpToolkitConfigurer} 与 {@code HigressToolkitConfigurer}——四处 block 一直是裸的，
 * 而客服端才是 H5 终端用户真实走的那条路。同形状缺陷在一个模块修掉、另一个模块留着，
 * 是本项目反复出现的形状，因此这里对结构本身下断言。</p>
 *
 * <p>常规单测照不出它：注册逻辑本身是对的，缺的是"卡住时能不能退出来"，
 * 而单测里的 mock 永远会立刻返回。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class McpBlockTimeoutAlignmentTest {

    /** MCP 客户端调用链的标志方法：这些调用上的 block 必须带超时。 */
    private static final List<String> MCP_CALL_MARKERS =
        List.of("registerMcpClient", "buildMcpClient", "buildClient(");

    /** 无参 block：本门禁要禁掉的写法。 */
    private static final String BARE_BLOCK = ".block()";

    private static final List<String> MODULE_SOURCE_ROOTS = List.of(
        "customer-work-starter/src/main/java",
        "customer-admin-server/src/main/java",
        "customer-work-app-server/src/main/java",
        "customer-channel/src/main/java");

    @Test
    @DisplayName("MCP / Higress 客户端的 block 调用必须显式传超时")
    void mcpClientBlockCallsMustCarryTimeout() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scannedMcpCallSites = 0;

        for (String moduleRoot : MODULE_SOURCE_ROOTS) {
            Path root = sourceRoot(moduleRoot);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String trimmed = lines[i].trim();
                        // 注释与 javadoc 里提到的写法不算
                        if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                            continue;
                        }
                        boolean isMcpCall = MCP_CALL_MARKERS.stream().anyMatch(trimmed::contains);
                        if (!isMcpCall) {
                            continue;
                        }
                        scannedMcpCallSites++;
                        if (trimmed.contains(BARE_BLOCK)) {
                            offenders.add(file + ":" + (i + 1) + "  " + trimmed);
                        }
                    }
                }
            }
        }

        assertTrue(scannedMcpCallSites >= 6,
            "应至少扫描到 6 处 MCP 客户端调用点，实际 " + scannedMcpCallSites + " —— 扫描路径可能失效了");

        if (!offenders.isEmpty()) {
            fail("以下 MCP / Higress 调用用了无参 block()，服务端卡住时调用线程会永久挂起且不抛异常：\n"
                + String.join("\n", offenders)
                + "\n修法：改为 block(McpTimeouts.BUILD) 或 block(McpTimeouts.REGISTER)。");
        }
    }

    @Test
    @DisplayName("超时值只有一个定义处，不允许各模块各写一遍")
    void timeoutValueHasSingleSourceOfTruth() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (String moduleRoot : MODULE_SOURCE_ROOTS) {
            Path root = sourceRoot(moduleRoot);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("McpTimeouts.java")) {
                        continue;
                    }
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String trimmed = lines[i].trim();
                        if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                            continue;
                        }
                        // 重新声明一个 MCP 超时常量 = 又一个真相来源
                        if (trimmed.contains("MCP_REGISTER_TIMEOUT")
                            || trimmed.contains("MCP_BUILD_TIMEOUT")) {
                            offenders.add(file + ":" + (i + 1) + "  " + trimmed);
                        }
                    }
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("MCP 超时值在 McpTimeouts 之外被重新声明，同一个概念又有了多个真相来源：\n"
                + String.join("\n", offenders)
                + "\n修法：引用 McpTimeouts.BUILD / McpTimeouts.REGISTER。");
        }
    }

    @Test
    @DisplayName("超时取值必须落在合理区间")
    void timeoutValuesAreSane() {
        // 太短会让跨机房抖动误判为故障；太长则注册压在建 Agent 路径上，直接加到首字延迟里
        assertTrue(McpTimeouts.BUILD.getSeconds() >= 3 && McpTimeouts.BUILD.getSeconds() <= 30,
            "MCP 构建超时应在 3~30 秒之间，实际 " + McpTimeouts.BUILD.getSeconds());
        assertTrue(McpTimeouts.REGISTER.getSeconds() >= 3 && McpTimeouts.REGISTER.getSeconds() <= 30,
            "MCP 注册超时应在 3~30 秒之间，实际 " + McpTimeouts.REGISTER.getSeconds());
        assertEquals(10, McpTimeouts.BUILD.getSeconds(), "构建超时当前约定为 10 秒");
        assertEquals(10, McpTimeouts.REGISTER.getSeconds(), "注册超时当前约定为 10 秒");
    }

    /** 测试的工作目录是模块目录，仓库根在其上一层；两种布局都兼容。 */
    private Path sourceRoot(String moduleRelative) {
        Path fromRepoRoot = Paths.get(moduleRelative);
        if (Files.exists(fromRepoRoot)) {
            return fromRepoRoot;
        }
        return Paths.get("..").resolve(moduleRelative);
    }
}
