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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>路径对齐门禁</b>：确保每一条构建 Agent 的链路都装配了治理中间件。
 *
 * <p><b>为什么需要这个测试</b>：本项目反复出现同一形状的缺陷——某个治理能力
 * （token 计量、敏感词过滤、工具审批、脱敏、注入防护、上下文预算）只接在了当时被测试的
 * 那一条路径上，另一条路径照常裸奔。已知复发六次：语义缓存只接非流式、CSAT 挂错生命周期钩子、
 * 缓存命中的出站过滤只在流式落实、知识盲区埋点只覆盖工具路径、附件 OCR 绕开隔离、
 * 多 Agent 编排整条链路无中间件。</p>
 *
 * <p>常规单测照不出这类问题：它们验证"这个方法的逻辑对不对"，而这里的缺陷是
 * "这段正确的逻辑有没有被接到用户走的那条路上"，两者正交。因此本测试<b>直接扫描源码</b>，
 * 对结构本身下断言——新增一条建 Agent 的路径而不装配治理中间件，这里就会红。</p>
 *
 * <p>扫描而非反射的理由：框架不暴露"某个 Agent 挂了哪些中间件"的查询接口，
 * 而装配发生在构建期。源码级断言虽然朴素，却精确对准了会出事的那一处。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class AgentAssemblyAlignmentTest {

    /** 构建 Agent 的标志性调用。 */
    private static final String AGENT_BUILDER = "ReActAgent.builder()";

    /**
     * 认可的治理装配证据。
     *
     * <ul>
     *   <li>{@code governanceAssembler.applyTo} —— starter 与 customer-channel 走统一装配器；</li>
     *   <li>{@code builder.middleware(} —— customer-admin-server 用
     *       {@code spring.autoconfigure.exclude} 关掉了 starter 自动装配，只能显式逐个挂载，
     *       它的完整性由 {@link #adminFactoryMountsAllGovernanceMiddlewares()} 单独断言。</li>
     * </ul>
     */
    private static final List<String> ASSEMBLY_MARKERS =
        List.of("governanceAssembler.applyTo", "builder.middleware(");

    /**
     * 豁免清单：确认<b>不</b>需要治理中间件的文件，每一条都要写明理由。
     *
     * <p>往这里加东西前先回答：这条链路会不会调模型？会不会把用户内容送进上下文？
     * 只要有一个"是"，它就该装配，而不是进这份清单。</p>
     */
    private static final Set<String> EXEMPT = Set.of(
        // 纯文档引用，类里并不真的构建 Agent
        "InMemoryKeywordKnowledge.java"
    );

    private static final List<String> MODULE_SOURCE_ROOTS = List.of(
        "customer-work-starter/src/main/java",
        "customer-admin-server/src/main/java",
        "customer-work-app-server/src/main/java",
        "customer-channel/src/main/java");

    @Test
    @DisplayName("每一条构建 Agent 的链路都必须装配治理中间件")
    void everyAgentBuildSiteAssemblesGovernanceMiddlewares() throws IOException {
        List<Path> offenders = new ArrayList<>();
        List<Path> checked = new ArrayList<>();

        for (Path file : agentBuildingFiles()) {
            checked.add(file);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            boolean assembled = ASSEMBLY_MARKERS.stream().anyMatch(source::contains);
            if (!assembled) {
                offenders.add(file);
            }
        }

        assertTrue(checked.size() >= 4,
            "应至少扫描到 4 个建 Agent 的文件，实际 " + checked.size() + " —— 扫描路径可能失效了");

        if (!offenders.isEmpty()) {
            fail("以下文件构建了 Agent 但没有装配治理中间件（token 计量/敏感词/工具审批/脱敏/注入防护会在这条路径上整体失效）：\n"
                + offenders.stream().map(Path::toString).reduce("", (a, b) -> a + "  - " + b + "\n")
                + "修法：注入 AgentGovernanceAssembler 并在 build() 前调用 applyTo(builder)。"
                + "确实不需要的，加进 EXEMPT 并写明理由。");
        }
    }

    /**
     * admin 侧不走统一装配器（它排除了 starter 的自动装配），因此单独断言它把关键治理中间件都挂上了。
     *
     * <p>脱敏与直接注入防护此前在这里整体缺席，且连一个能打开它们的配置项都没有——
     * 而客服端两者都有，运维很容易以为是全局生效的。</p>
     */
    @Test
    @DisplayName("admin 智能体工厂必须挂满关键治理中间件")
    void adminFactoryMountsAllGovernanceMiddlewares() throws IOException {
        Path factory = Paths.get(
            "../customer-admin-server/src/main/java/com/richard/fyoung/customeradmin/workspace/runtime/AdminAgentInstanceFactory.java");
        if (!Files.exists(factory)) {
            factory = Paths.get(
                "customer-admin-server/src/main/java/com/richard/fyoung/customeradmin/workspace/runtime/AdminAgentInstanceFactory.java");
        }
        assertTrue(Files.exists(factory), "找不到 AdminAgentInstanceFactory，路径约定变了？");

        String source = Files.readString(factory, StandardCharsets.UTF_8);
        List<String> required = List.of(
            "agentCallTimingMiddleware",          // token 计量唯一落点
            "sensitiveWordMiddleware",            // 敏感词进出站过滤
            "maskingMiddleware",                  // 出站脱敏
            "promptInjectionGuardMiddleware",     // 直接注入防护
            "indirectInjectionGuardMiddleware");  // 间接注入防护（工具/MCP 结果隔离）

        List<String> missing = required.stream().filter(m -> !source.contains(m)).toList();
        assertTrue(missing.isEmpty(),
            "admin 智能体工厂缺少治理中间件：" + missing
                + "。后台链路会在这些维度上裸奔，而客服端有——最容易被误以为是全局生效的那种缺失。");
    }

    /**
     * 治理装配器本身必须覆盖全部治理维度。
     *
     * <p>这是"改一处、所有路径生效"的前提：装配器漏了谁，所有走它的路径就一起漏。</p>
     */
    @Test
    @DisplayName("治理装配器必须覆盖可观测、人工确认与全部可插拔中间件")
    void assemblerCoversAllGovernanceDimensions() throws IOException {
        Path assembler = sourceRoot("customer-work-starter/src/main/java")
            .resolve("com/richard/fyoung/customerwork/core/agent/AgentGovernanceAssembler.java");
        assertTrue(Files.exists(assembler), "找不到 AgentGovernanceAssembler");

        String source = Files.readString(assembler, StandardCharsets.UTF_8);
        assertTrue(source.contains("ObservabilityMiddleware"), "装配器漏了可观测中间件");
        assertTrue(source.contains("HumanApprovalMiddleware"), "装配器漏了工具级人工确认");
        assertTrue(source.contains("pluggableMiddlewares"),
            "装配器漏了可插拔 MiddlewareBase 集合——敏感词、脱敏、租户、分段耗时与 token 计量都在这一批里");
    }

    /** 找出所有真正构建 Agent 的生产源文件（跳过豁免清单）。 */
    private List<Path> agentBuildingFiles() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String moduleRoot : MODULE_SOURCE_ROOTS) {
            Path root = sourceRoot(moduleRoot);
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (EXEMPT.contains(file.getFileName().toString())) {
                        continue;
                    }
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (containsRealBuildSite(source)) {
                        result.add(file);
                    }
                }
            }
        }
        return result;
    }

    /** 只认代码里的构建调用，注释与 javadoc 里提到的不算。 */
    private boolean containsRealBuildSite(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                continue;
            }
            if (trimmed.contains(AGENT_BUILDER)) {
                return true;
            }
        }
        return false;
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
