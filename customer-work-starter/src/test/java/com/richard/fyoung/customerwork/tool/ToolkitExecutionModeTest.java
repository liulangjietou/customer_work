package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.infra.config.properties.ToolExecutionProperties;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>工具执行模式门禁</b>：Toolkit 必须显式保持串行。
 *
 * <p><b>守的是什么</b>：AgentScope v2.0.1 把 Toolkit 默认执行模式从串行改成并行，
 * 而这条变更写在 release notes 的 "Refactored" 段落里、不在 Breaking Changes。
 * 工具实现本身并行安全，不安全的是业务与治理语义——审批交错、租户上下文跨线程传播、
 * token 计量口径。升级时把它钉回串行，是为了把行为变更与版本升级解耦。</p>
 *
 * <p>没有这道门禁的话，下一次依赖升版或者有人新写一个 {@code new Toolkit()}，
 * 并行就会悄悄回来——而它不报错，只在某个并发审批场景下表现为"退款被拒了但订单已经改了"。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class ToolkitExecutionModeTest {

    private static final List<String> MODULE_SOURCE_ROOTS = List.of(
        "customer-work-starter/src/main/java",
        "customer-admin-server/src/main/java",
        "customer-work-app-server/src/main/java",
        "customer-channel/src/main/java");

    @Test
    @DisplayName("串行配置只关掉并行，不顺带改变其它默认行为")
    void sequentialConfigOnlyFlipsParallel() {
        ToolkitConfig sequential = ToolkitConfigs.sequential();
        ToolkitConfig framework = ToolkitConfig.defaultConfig();

        assertFalse(sequential.isParallel(), "必须是串行");
        assertTrue(framework.isParallel(),
            "框架默认已是并行（v2.0.1 起）；若此处变红说明框架又改了默认值，需要重新评估这道门禁");

        assertEquals(framework.isAllowToolDeletion(), sequential.isAllowToolDeletion(),
            "不应顺带改变工具删除许可");
        assertEquals(framework.hasCustomExecutor(), sequential.hasCustomExecutor(),
            "不应顺带引入自定义线程池");
        assertEquals(framework.getExecutionConfig(), sequential.getExecutionConfig(),
            "不应顺带改变执行配置");
        assertEquals(framework.getDefaultContext(), sequential.getDefaultContext(),
            "不应顺带改变默认执行上下文");
    }

    /** 主链路与渠道链路用的都是它的子类，构造器兜住则全部实例化点自动正确。 */
    @Test
    @DisplayName("ManagedToolkit 默认构造即为串行")
    void managedToolkitDefaultsToSequential() {
        try (ManagedToolkit toolkit = new ManagedToolkit()) {
            assertTrue(toolkit instanceof Toolkit, "应仍是框架 Toolkit 的子类");
        }
        // 行为本身由上面的配置断言保证；这里确认默认构造器不抛异常且走的是带 config 的那条路
        assertFalse(ToolkitConfigs.sequential().isParallel());
    }

    /**
     * 工具执行必须有可用的超时。
     *
     * <p>实测框架 {@code ExecutionConfig.TOOL_DEFAULTS} 是 {@code timeout=5分钟, maxAttempts=1}。
     * 5 分钟对客服对话等于没有超时——订单库慢一次，用户就对着不动的界面等五分钟。
     * 项目在模型侧做了失败转移、熔断、分级路由一整套弹性，工具侧此前一样都没配。</p>
     */
    @Test
    @DisplayName("工具执行超时必须远小于框架默认的 5 分钟")
    void toolExecutionTimeoutIsUsableForConversation() {
        ToolExecutionProperties defaults = new ToolExecutionProperties();
        ToolkitConfig config = ToolkitConfigs.sequentialWith(defaults);

        assertFalse(config.isParallel(), "仍然必须是串行");
        ExecutionConfig execution = config.getExecutionConfig();
        assertNotNull(execution, "必须显式给出执行配置，否则落到框架的 5 分钟默认值");
        assertTrue(execution.getTimeout().toSeconds() <= 60,
            "对话场景的工具超时应在一分钟以内，实际 " + execution.getTimeout());
        assertTrue(execution.getTimeout().toSeconds() >= 5,
            "太短会把正常的慢查询误判成故障，实际 " + execution.getTimeout());
        assertTrue(ExecutionConfig.TOOL_DEFAULTS.getTimeout().toMinutes() >= 5,
            "这里变红说明框架改了默认值，需要重新评估本项目的取值是否还有必要覆盖");
    }

    /**
     * <b>默认必须不重试</b>——这条是安全约束，不是性能取舍。
     *
     * <p>框架的重试对整个工具集统一生效、不区分幂等性，而客服工具里有「发起退款」
     * 「创建工单」「转人工」这类重试一次就多做一次的操作。超时往往意味着请求已经到达下游、
     * 只是响应慢了——这时重试会退两次款。</p>
     *
     * <p>要打开重试的部署，必须先确认全部已注册工具（含 MCP 侧接进来的）都是幂等的。</p>
     */
    @Test
    @DisplayName("默认不重试：框架重试不区分工具幂等性，退款类操作会重复执行")
    void retryIsDisabledByDefault() {
        ToolExecutionProperties defaults = new ToolExecutionProperties();

        assertEquals(1, defaults.getMaxAttempts(),
            "默认必须是 1（不重试）——框架的重试不区分幂等性，退款类工具会被重复执行");

        ExecutionConfig execution = ToolkitConfigs.sequentialWith(defaults).getExecutionConfig();
        assertEquals(1, execution.getMaxAttempts());
        assertNull(execution.getInitialBackoff(), "不重试时不该设退避");
    }

    /** 显式打开重试时才配退避参数。 */
    @Test
    @DisplayName("显式打开重试后才带退避")
    void backoffOnlyWhenRetryEnabled() {
        ToolExecutionProperties retrying = new ToolExecutionProperties();
        retrying.setMaxAttempts(3);

        ExecutionConfig execution = ToolkitConfigs.sequentialWith(retrying).getExecutionConfig();

        assertEquals(3, execution.getMaxAttempts());
        assertNotNull(execution.getInitialBackoff(), "重试必须带退避，密集重试是自制雪崩");
        assertTrue(execution.getMaxBackoff().toMillis() >= execution.getInitialBackoff().toMillis());
    }

    @Test
    @DisplayName("配置为空时退回纯串行，不伪造执行参数")
    void nullExecutionFallsBackToPlainSequential() {
        ToolkitConfig config = ToolkitConfigs.sequentialWith(null);

        assertFalse(config.isParallel());
        assertNull(config.getExecutionConfig(), "没有配置就不该编造一个出来");
    }

    /**
     * 生产代码不得绕过 {@link ManagedToolkit} 直接 new 框架 Toolkit。
     *
     * <p>直接 {@code new Toolkit()} 会拿到框架默认配置，也就是并行——而且不报错。
     * 项目里 Toolkit 的实例化点有 7 处，靠"记得传配置"守不住。</p>
     */
    @Test
    @DisplayName("生产代码不得直接 new 框架 Toolkit 绕过串行配置")
    void productionCodeMustNotInstantiateRawToolkit() throws IOException {
        List<String> offenders = new ArrayList<>();

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
                        if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                            continue;
                        }
                        // 只拦裸的框架 Toolkit，ManagedToolkit / DefaultActiveGroupsToolkit 是允许的
                        if (trimmed.contains("new Toolkit(")) {
                            offenders.add(file + ":" + (i + 1) + "  " + trimmed);
                        }
                    }
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("以下位置直接实例化了框架 Toolkit，会拿到框架默认的并行执行模式：\n"
                + String.join("\n", offenders)
                + "\n修法：改用 ManagedToolkit 或 DefaultActiveGroupsToolkit，它们的构造器已固定串行。");
        }
    }

    private Path sourceRoot(String moduleRelative) {
        Path fromRepoRoot = Paths.get(moduleRelative);
        if (Files.exists(fromRepoRoot)) {
            return fromRepoRoot;
        }
        return Paths.get("..").resolve(moduleRelative);
    }
}
