package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customerwork.infra.config.RuntimeWorkDir;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Docker 沙箱补齐（需求 P1-3 §4.6）的门控式集成测试：脱离 Agent/模型/MySQL，只依赖本机 Docker，
 * 且**直接消费生产装配**——沙箱规格取自
 * {@link AdminAgentInstanceFactory#buildDockerFilesystemSpec}（bind mount 挂 agent 工作区根、
 * {@code --user} 注入、projection 关闭、镜像/网络参数），任何人改坏生产接线（挂载路径、run 参数、
 * projection 开关）本测试都会失败，而不是只验证一份手工重建的等价规格。
 *
 * <p><b>门控约定</b>（对齐仓库既有 MySQL/Redis/Nacos 门控测试）：本机 Docker 守护进程不可达时整体
 * {@code assumeTrue} 跳过、不失败；镜像未拉取时跳过并在消息里给出 {@code docker pull} 命令（测试内不自动
 * 拉取，太慢）。模式无关的保证（sessionId 转义、破坏性命令识别）不依赖 Docker，恒定执行。</p>
 *
 * <p>覆盖需求 §4.6.2：①容器内写文件→编译运行→读回 + bind mount 产物实时落宿主机（含跨会话
 * {@code MEMORY.md} 的宿主机持久化、{@code --user} 下的产物属主对齐）；②沙箱资源获取失败时快速抛错
 * 不挂起；③sessionId 转义（{@link SandboxSafeAgentStateStore}）在 docker 模式同样生效；④破坏性命令识别
 * （{@link SandboxRiskDetector}）与模式无关，docker 模式一样拦得住。</p>
 * @author owlzhangfq@gmail.com
 */
class DockerSandboxIntegrationTest {

    // ==================== 主链路：生产装配 + bind mount 产物同步（docker 门控）====================

    @Test
    void productionSpec_writeCompileRun_artifactsAndMemoryVisibleOnHost() throws Exception {
        assumeTrue(dockerAvailable(), "Docker 守护进程不可达，跳过该集成测试");
        AdminSandboxProperties props = dockerModeProperties();
        String image = props.getDocker().getImage();
        assumeTrue(imagePresent(image), "镜像未拉取，跳过。请先执行: docker pull " + image);

        String agentCode = "it-docker-" + System.currentTimeMillis();
        // 与工厂 prepareHostWorkspaceRoot 同源的宿主机路径（CWD=模块目录，落在 /Users 之下，
        // macOS Docker Desktop 默认共享白名单内）
        Path hostAgentRoot = RuntimeWorkDir.resolve("admin-workspace").resolve(agentCode).toAbsolutePath().normalize();

        // ① 生产工厂装配沙箱规格（内部完成宿主机目录预建 + run 参数组装）
        SandboxFilesystemSpec spec = AdminAgentInstanceFactory.buildDockerFilesystemSpec(props, agentCode);
        SandboxContext ctx = spec.toSandboxContext();

        // ② 静态断言生产接线：bind mount 挂 agent 根、projection 关闭（无注入 entry）、HOME 环境
        DockerSandboxClientOptions options = (DockerSandboxClientOptions) ctx.getClientOptions();
        assertEquals(image, options.getImage(), "镜像应取自 admin.sandbox.docker.image");
        assertEquals("none", options.getNetwork(), "默认网络应为 none");
        List<String> runArgs = options.getAdditionalRunArgs();
        String expectedMount = hostAgentRoot + ":" + AdminAgentInstanceFactory.CONTAINER_WORKSPACE_ROOT + ":rw";
        assertTrue(runArgs.contains("-v") && runArgs.contains(expectedMount),
            "run 参数应含 agent 工作区根 bind mount，实际: " + runArgs);
        boolean userInjected = runArgs.contains("--user");
        assertTrue(Files.isDirectory(hostAgentRoot.resolve("sessions")), "工厂应预建宿主机 sessions/ 目录");
        assertTrue(ctx.getWorkspaceSpec().getEntries() == null || ctx.getWorkspaceSpec().getEntries().isEmpty(),
            "projection 已关闭且挂载走 run 参数，WorkspaceSpec 不应残留注入 entry");
        assertEquals(AdminAgentInstanceFactory.CONTAINER_WORKSPACE_ROOT,
            ctx.getWorkspaceSpec().getEnvironment().get("HOME"), "--user 下需显式 HOME");

        // ③ 真起容器：写文件→编译→运行→读回，并验证宿主机实时可见
        DockerSandboxClient client = (DockerSandboxClient) ctx.getClient();
        Sandbox sandbox = client.create(ctx.getWorkspaceSpec(), ctx.getSnapshotSpec(), options);
        try {
            sandbox.start();
            RuntimeContext runtime = RuntimeContext.builder().userId(agentCode).sessionId("s-1").build();

            String workspace = AdminAgentInstanceFactory.CONTAINER_WORKSPACE_ROOT;
            String heredoc = "mkdir -p " + workspace + "/sessions/app && "
                + "cat > " + workspace + "/sessions/app/Hello.java <<'EOF'\n"
                + "public class Hello { public static void main(String[] a) { System.out.println(\"hi-from-sandbox\"); } }\n"
                + "EOF";
            ExecResult write = sandbox.exec(runtime, heredoc, 60);
            assertTrue(write.ok(), "容器内写文件应成功: " + write.combinedOutput());

            ExecResult run = sandbox.exec(runtime,
                "cd " + workspace + "/sessions/app && javac Hello.java && java Hello", 180);
            assertEquals(0, run.exitCode(), "编译运行应成功: " + run.combinedOutput());
            assertTrue(run.combinedOutput().contains("hi-from-sandbox"), "应读回程序输出: " + run.combinedOutput());

            // bind mount 实时同步：容器写的源码 + 编译产物立即出现在宿主机
            Path hostJava = hostAgentRoot.resolve("sessions/app/Hello.java");
            assertTrue(Files.exists(hostJava), "宿主机应看到容器写入的源码");
            assertTrue(Files.exists(hostAgentRoot.resolve("sessions/app/Hello.class")), "宿主机应看到容器编译的 .class 产物");
            assertTrue(Files.readString(hostJava, StandardCharsets.UTF_8).contains("hi-from-sandbox"),
                "宿主机读到的源码内容应与容器内一致");

            // 跨会话 MEMORY.md（框架经 Filesystem 抽象写 workspace 根）也应落宿主机持久化（P1-3 挂根的关键收益）
            ExecResult mem = sandbox.exec(runtime, "echo agent-memory > " + workspace + "/MEMORY.md", 30);
            assertTrue(mem.ok(), "容器内写 MEMORY.md 应成功: " + mem.combinedOutput());
            assertTrue(Files.exists(hostAgentRoot.resolve("MEMORY.md")),
                "宿主机应看到容器写入的 MEMORY.md（跨会话记忆持久化）");

            // --user 注入生效时，产物属主应与宿主机 JVM 用户一致（Linux root 属主问题的回归断言；
            // macOS Docker Desktop 属主映射下同样成立）
            if (userInjected) {
                assertEquals(System.getProperty("user.name"), Files.getOwner(hostJava).getName(),
                    "bind mount 产物属主应为宿主机 JVM 用户（--user 对齐）");
                assertTrue(Files.isWritable(hostJava), "宿主机 JVM 用户应可写产物（回滚/保存的前提）");
            }
        } finally {
            safeClose(sandbox);
            deleteRecursively(hostAgentRoot);
        }
    }

    // ==================== 资源获取失败：快速抛错不挂起（docker 门控）====================

    @Test
    void sandboxAcquireFailure_throwsPromptly_doesNotHang() {
        assumeTrue(dockerAvailable(), "Docker 守护进程不可达，跳过该集成测试");
        String agentCode = "it-docker-fail-" + System.currentTimeMillis();
        Path hostAgentRoot = RuntimeWorkDir.resolve("admin-workspace").resolve(agentCode).toAbsolutePath().normalize();

        // 同样走生产工厂，仅镜像换成不存在的引用：docker run 找不到镜像会有界地失败（拉取失败/not found），
        // 而不是无限挂起——回归第 2 章约束 2「沙箱资源获取失败要能正常报错」的坑。
        AdminSandboxProperties props = dockerModeProperties();
        props.getDocker().setImage("agentscope-nonexistent-image-p1-3:doesnotexist");
        SandboxContext ctx = AdminAgentInstanceFactory.buildDockerFilesystemSpec(props, agentCode).toSandboxContext();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(120), () ->
                assertThrows(Exception.class, () -> {
                    DockerSandboxClient client = (DockerSandboxClient) ctx.getClient();
                    Sandbox sandbox = client.create(ctx.getWorkspaceSpec(), ctx.getSnapshotSpec(),
                        (DockerSandboxClientOptions) ctx.getClientOptions());
                    try {
                        sandbox.start();
                    } finally {
                        safeClose(sandbox);
                    }
                }));
        } finally {
            deleteRecursively(hostAgentRoot);
        }
    }

    // ==================== 模式无关保证（无需 Docker，恒定执行）====================

    @Test
    void sessionIdWithSlash_isEscaped_beforeReachingStore() {
        // docker 模式下框架给沙箱状态槽位拼出带 "/" 的 sessionId，MysqlAgentStateStore 会拒绝——
        // SandboxSafeAgentStateStore 装饰器在转发前把分隔符转义掉（见其 Javadoc）。此保证与是否真起容器无关。
        AgentStateStore delegate = mock(AgentStateStore.class);
        SandboxSafeAgentStateStore safe = new SandboxSafeAgentStateStore(delegate);

        safe.exists("agent", "sandbox/agent/slot");
        safe.delete("agent", "a\\b");

        verify(delegate).exists("agent", "sandbox_agent_slot");
        verify(delegate).delete("agent", "a_b");
    }

    @Test
    void destructiveCommand_detected_modeIndependent() {
        // 危险命令拦截由 SandboxGuardMiddleware 在工具调用中间件层完成，命令下发到 local/docker 沙箱之前
        // 就已改写，与沙箱后端无关——这里断言其依赖的破坏性识别在默认配置下命中 rm -rf。
        SandboxRiskDetector detector = new SandboxRiskDetector(new AdminSandboxProperties());
        assertTrue(detector.matchesDestructive("rm -rf /workspace"), "rm -rf 应被识别为破坏性命令");
        assertTrue(detector.matchesDestructive("echo x && rm -rf ./data"), "内嵌 rm -rf 也应命中");
    }

    // ==================== helpers ====================

    /** 生产等价的 docker 模式配置（镜像/网络/资源全用 AdminSandboxProperties 默认值）。 */
    private AdminSandboxProperties dockerModeProperties() {
        AdminSandboxProperties props = new AdminSandboxProperties();
        props.setMode("docker");
        return props;
    }

    /**
     * 停止并删除测试容器。注意框架 {@code DockerSandboxClient#delete} 在当前实现里是 no-op，真正的
     * stop+rm 走 {@link Sandbox#close()}（内部 stop → shutdown，owned 容器执行 {@code docker rm --force}），
     * 用它清理避免遗留容器堆积。
     */
    private void safeClose(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        try {
            sandbox.close();
        } catch (Exception ignored) {
            // best-effort 清理，失败不影响断言结论
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理是 best-effort（--user 对齐后一般不会有删不掉的 root 属主文件）
                }
            });
        } catch (Exception ignored) {
            // 清理是 best-effort
        }
    }

    /** 探测本机 Docker 守护进程是否可达（{@code docker version} 退出码 0）。 */
    private boolean dockerAvailable() {
        return dockerCli(60, "version", "--format", "{{.Server.Version}}");
    }

    /** 镜像是否已在本地（{@code docker image inspect <image>} 退出码 0）。 */
    private boolean imagePresent(String image) {
        return dockerCli(30, "image", "inspect", image);
    }

    private boolean dockerCli(int timeoutSeconds, String... args) {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(dockerExecutable());
        cmd.addAll(java.util.Arrays.asList(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * docker 可执行文件路径：IDE/GUI 启动的 JVM 不继承 shell PATH（macOS 常见坑），
     * 探测常见安装位置，找不到再退回裸命令名交给 PATH 解析。
     */
    private String dockerExecutable() {
        for (String candidate : new String[] {
            "/opt/homebrew/bin/docker",
            "/usr/local/bin/docker",
            "/Applications/Docker.app/Contents/Resources/bin/docker",
            "/usr/bin/docker"
        }) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "docker";
    }
}
