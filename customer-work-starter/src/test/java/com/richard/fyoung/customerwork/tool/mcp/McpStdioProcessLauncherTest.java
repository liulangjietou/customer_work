package com.richard.fyoung.customerwork.tool.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpStdioProcessLauncher} 的启动命令拼装单测。
 * @author owlzhangfq@gmail.com
 */
class McpStdioProcessLauncherTest {

    private static final String LOADER = "org.springframework.boot.loader.launch.PropertiesLauncher";

    private McpServerSpec spec() {
        return McpServerSpec.stdio("probe", "/bin/echo", List.of("hello"), "/tmp", Map.of("PATH", "/usr/bin"));
    }

    /**
     * 防回归：代理进程的主类必须在<b>当前 classpath</b> 上真实可加载。
     *
     * <p>此前这里硬编码了 {@code PropertiesLauncher}，那个类只在 {@code spring-boot-loader} 里，
     * 也就是只有以可执行 jar 运行时才在 classpath 上。以 exploded classpath 运行（IDE 直接跑、
     * {@code mvn spring-boot:run}、{@code java -cp}）时代理进程会当场
     * {@code ClassNotFoundException} 退出，表现为所有 stdio MCP 一律「Client failed to initialize」——
     * 而错误信息完全指不到这里，很容易一路误查白名单配置。</p>
     */
    @Test
    void commandFor_shouldTargetLoadableMainClass() {
        McpStdioProcessLauncher.LaunchCommand launch = McpStdioProcessLauncher.commandFor(spec());

        int classpathIndex = launch.arguments().indexOf("-cp");
        assertTrue(classpathIndex >= 0, "必须显式传 -cp");
        String mainClass = launch.arguments().get(classpathIndex + 2);

        assertDoesNotThrow(() -> Class.forName(mainClass, false, getClass().getClassLoader()),
            "代理进程主类必须在当前 classpath 上可加载，否则子进程起不来: " + mainClass);
    }

    /** 用 loader 启动时必须同时给出 {@code -Dloader.main}，否则它不知道该转发到哪个主类。 */
    @Test
    void commandFor_shouldPairLoaderWithLoaderMain() {
        McpStdioProcessLauncher.LaunchCommand launch = McpStdioProcessLauncher.commandFor(spec());

        boolean usesLoader = launch.arguments().contains(LOADER);
        boolean declaresLoaderMain = launch.arguments().stream()
            .anyMatch(argument -> argument.startsWith("-Dloader.main="));

        assertEquals(usesLoader, declaresLoaderMain, "-Dloader.main 必须与 PropertiesLauncher 成对出现");
    }

    /** 环境变量按「只传 key」约定下发，值由代理进程从自身环境取，避免密钥落进进程参数被 ps 看到。 */
    @Test
    void commandFor_shouldPassEnvironmentKeysOnlyNotValues() {
        McpStdioProcessLauncher.LaunchCommand launch = McpStdioProcessLauncher.commandFor(spec());

        assertTrue(launch.arguments().contains("PATH"), "允许透传的变量名应出现在参数里");
        assertTrue(launch.arguments().stream().noneMatch("/usr/bin"::equals),
            "变量值不得进入进程参数");
    }

    /**
     * 防回归：转发必须逐块 flush，不能等到流结束。
     *
     * <p>此前两个方向都用 {@code InputStream#transferTo}，它只在读到 EOF、由 close() 隐式 flush 一次。
     * MCP 是长连接上的行式 JSON-RPC，单条消息几百字节，既填不满缓冲区、连接也不会 EOF，
     * 于是 initialize 请求一直躺在缓冲里发不出去，握手必然超时——而进程树看上去完全正常，
     * 代理进程和目标进程都活着，排查时极难联想到 I/O 缓冲。</p>
     */
    @Test
    void pump_shouldFlushEachChunkWithoutWaitingForEof() throws Exception {
        java.io.PipedOutputStream producer = new java.io.PipedOutputStream();
        java.io.PipedInputStream source = new java.io.PipedInputStream(producer, 64 * 1024);
        java.io.ByteArrayOutputStream delivered = new java.io.ByteArrayOutputStream();
        java.io.BufferedOutputStream sink = new java.io.BufferedOutputStream(delivered, 8192);

        Thread pump = new Thread(() -> {
            try {
                McpStdioProcessLauncher.pump(source, sink);
            } catch (java.io.IOException ignored) {
                // 测试结束时管道关闭属正常收尾
            }
        }, "pump-under-test");
        pump.setDaemon(true);
        pump.start();

        byte[] message = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        producer.write(message);
        producer.flush();

        // 刻意不关闭 producer：模拟 MCP 长连接「还会继续发」的常态
        long deadline = System.currentTimeMillis() + 2000L;
        while (delivered.size() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }

        assertEquals(message.length, delivered.size(),
            "源流未结束时数据就必须已送达对端，否则 MCP 握手会一直卡住");
    }
}
