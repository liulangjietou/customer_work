package com.richard.fyoung.customerwork.tool.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * stdio MCP 子进程隔离启动器。
 *
 * <p>AgentScope 的 stdio transport 没有工作目录 API，也会继承宿主环境。本启动器作为透明代理：
 * 在独立进程中固定工作目录、清空目标子进程环境，只传安全策略允许的变量，然后原样转发 stdin/stdout。
 * 进程启动全程使用 {@link ProcessBuilder} 参数数组，不经过 shell。</p>
 */
public final class McpStdioProcessLauncher {

    private static final String MAIN_CLASS = McpStdioProcessLauncher.class.getName();

    /** fat jar 启动器；只有以可执行 jar 运行时才在 classpath 上。 */
    private static final String PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";

    private McpStdioProcessLauncher() {
    }

    /** 把目标规格转换成启动本代理进程的命令；secret 只走环境变量，不进入进程参数。 */
    static LaunchCommand commandFor(McpServerSpec spec) {
        List<String> arguments = new ArrayList<>();
        // 打成可执行 jar 时 java.class.path 只有那一个 jar，内部依赖要靠 PropertiesLauncher 展开才找得到；
        // 而以 exploded classpath 运行时（IDE 直接跑、mvn spring-boot:run、java -cp）loader 根本不在
        // classpath 上——此时仍拼 PropertiesLauncher 会让代理进程直接 ClassNotFoundException 退出，
        // 表现为所有 stdio MCP 一律「Client failed to initialize」，且与白名单怎么配都无关。
        // 故按 loader 是否真的可加载来选启动方式，两种运行模式都能起来。
        boolean fatJar = propertiesLauncherPresent();
        if (fatJar) {
            arguments.add("-Dloader.main=" + MAIN_CLASS);
        }
        arguments.add("-cp");
        arguments.add(System.getProperty("java.class.path"));
        arguments.add(fatJar ? PROPERTIES_LAUNCHER : MAIN_CLASS);
        arguments.add(spec.workingDirectory());
        arguments.add(spec.command());
        Map<String, String> environment = spec.environment() == null ? Map.of() : spec.environment();
        arguments.add(Integer.toString(environment.size()));
        arguments.addAll(environment.keySet());
        List<String> targetArgs = spec.args() == null ? List.of() : spec.args();
        arguments.add(Integer.toString(targetArgs.size()));
        arguments.addAll(targetArgs);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new LaunchCommand(java, List.copyOf(arguments), Map.copyOf(environment));
    }

    /** loader 是否在当前 classpath 上；决定代理进程用哪种方式启动。 */
    private static boolean propertiesLauncherPresent() {
        try {
            Class.forName(PROPERTIES_LAUNCHER, false, McpStdioProcessLauncher.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        ParsedLaunch launch = parse(args);
        ProcessBuilder builder = new ProcessBuilder(command(launch));
        builder.directory(Path.of(launch.workingDirectory()).toFile());
        Map<String, String> childEnvironment = builder.environment();
        childEnvironment.clear();
        launch.environmentKeys().forEach(key -> childEnvironment.put(key, System.getenv(key)));
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process child = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(child::destroy, "mcp-stdio-shutdown"));
        Thread inputPump = new Thread(() -> copyInput(child), "mcp-stdio-input");
        inputPump.setDaemon(true);
        inputPump.start();
        pump(child.getInputStream(), System.out);
        int exitCode = child.waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static List<String> command(ParsedLaunch launch) {
        List<String> command = new ArrayList<>();
        command.add(launch.command());
        command.addAll(launch.arguments());
        return command;
    }

    private static void copyInput(Process child) {
        try (var output = child.getOutputStream()) {
            pump(System.in, output);
        } catch (IOException e) {
            child.destroy();
        }
    }

    /**
     * 边读边写并<b>逐块 flush</b> 地转发字节流。
     *
     * <p>不能用 {@code InputStream#transferTo}：两端都是带缓冲的流，而 transferTo 只在读到 EOF、
     * 由 close() 隐式 flush 一次。MCP 走的是长连接上的行式 JSON-RPC——单条消息只有几百字节，
     * 既填不满 8KB 缓冲区，连接也不会 EOF，于是 initialize 请求会一直躺在缓冲区里发不到子进程，
     * 子进程的响应同样卡在回程缓冲里。两个方向一起堵死，表现为握手 8 秒超时，
     * 而进程树看上去一切正常（代理进程和目标进程都活着），极难往 I/O 缓冲上想。</p>
     */
    static void pump(InputStream source, OutputStream sink) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = source.read(buffer)) >= 0) {
            sink.write(buffer, 0, read);
            sink.flush();
        }
    }

    private static ParsedLaunch parse(String[] args) {
        if (args == null || args.length < 4) {
            throw new IllegalArgumentException("invalid MCP stdio launch arguments");
        }
        int index = 0;
        String workingDirectory = args[index++];
        String command = args[index++];
        int environmentCount = parseCount(args[index++]);
        if (args.length < index + environmentCount + 1) {
            throw new IllegalArgumentException("invalid MCP stdio environment arguments");
        }
        List<String> environmentKeys = new ArrayList<>();
        for (int i = 0; i < environmentCount; i++) {
            String key = args[index++];
            if (System.getenv(key) == null) {
                throw new IllegalArgumentException("missing MCP stdio environment value: " + key);
            }
            environmentKeys.add(key);
        }
        int argumentCount = parseCount(args[index++]);
        if (args.length != index + argumentCount) {
            throw new IllegalArgumentException("invalid MCP stdio target arguments");
        }
        List<String> targetArguments = new ArrayList<>();
        for (int i = 0; i < argumentCount; i++) {
            targetArguments.add(args[index++]);
        }
        return new ParsedLaunch(workingDirectory, command, List.copyOf(environmentKeys),
            List.copyOf(targetArguments));
    }

    private static int parseCount(String value) {
        int count = Integer.parseInt(value);
        if (count < 0) {
            throw new IllegalArgumentException("negative MCP stdio argument count");
        }
        return count;
    }

    record LaunchCommand(String command, List<String> arguments, Map<String, String> environment) {
    }

    private record ParsedLaunch(String workingDirectory, String command,
                                List<String> environmentKeys, List<String> arguments) {
    }
}
