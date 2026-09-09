package com.richard.fyoung.customerwork.core.middleware;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>中间件顺序契约门禁</b>。
 *
 * <p><b>守的是什么</b>：框架的 {@code MiddlewareBase#order()} 默认返回 {@code 1}，相同数值时
 * 保持 builder 注册顺序。在声明 order 之前，全部 24 个中间件都用着这个默认值，也就是说实际顺序
 * 完全取决于 Spring 把 Bean 交给 {@code orderedStream()} 的先后——而它们一个 {@code @Order}
 * 都没标，那个先后是<b>不确定的</b>。</p>
 *
 * <p>于是"先裁剪还是先注入""审计记的是脱敏前还是脱敏后"这类问题，答案由 Bean 定义顺序偶然决定，
 * 改一行无关代码就可能翻转，而且不会报任何错。本项目已经在同构的问题上栽过一次：
 * {@code SubjectQuotaWebFilter} 的 Order 必须排在两个鉴权过滤器之后，
 * 抢在前面会把登录用户全按匿名 IP 限流。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class MiddlewareOrderContractTest {

    /** 走 admin 自有装配路径（AdminAgentInstanceFactory 显式逐个挂载），不参与 starter 的顺序契约。 */
    private static final List<String> ADMIN_OWNED = List.of(
        "ExecutionModeMiddleware",
        "SandboxGuardMiddleware",
        "CurrentTimeContextMiddleware",
        "AgentTaskReplayCaptureMiddleware");

    @Test
    @DisplayName("每个中间件都必须显式声明 order，不得留默认值")
    void everyMiddlewareDeclaresOrder() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        try (Stream<Path> stream = Files.walk(sourceRoot("customer-work-starter/src/main/java"))) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("implements MiddlewareBase")) {
                    continue;
                }
                String name = file.getFileName().toString().replace(".java", "");
                if (ADMIN_OWNED.contains(name)) {
                    continue;
                }
                checked++;
                if (!source.contains("public int order()")) {
                    offenders.add(name);
                }
            }
        }

        assertTrue(checked >= 18,
            "应至少扫描到 18 个中间件，实际 " + checked + " —— 扫描路径可能失效了");

        if (!offenders.isEmpty()) {
            fail("以下中间件没有声明 order()，会落到框架默认值 1，实际顺序将由 Bean 定义顺序偶然决定：\n"
                + String.join("\n", offenders)
                + "\n修法：在 MiddlewareOrders 里定一个值并说明它为什么在那一层，然后覆写 order()。");
        }
    }

    /**
     * 取值不得重复。
     *
     * <p>重复不会报错，只是让这两个中间件退回"按注册顺序"——也就是回到了这套契约要解决的问题本身。</p>
     */
    @Test
    @DisplayName("顺序取值不得重复")
    void orderValuesAreDistinct() throws IllegalAccessException {
        Map<Integer, String> seen = new HashMap<>();
        List<String> conflicts = new ArrayList<>();

        for (Field f : MiddlewareOrders.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            int value = f.getInt(null);
            String previous = seen.put(value, f.getName());
            if (previous != null) {
                conflicts.add(previous + " 与 " + f.getName() + " 都取 " + value);
            }
        }

        assertTrue(seen.size() >= 18, "顺序常量数量异常，实际 " + seen.size());
        if (!conflicts.isEmpty()) {
            fail("顺序取值重复，这两者会退回按注册顺序：\n" + String.join("\n", conflicts));
        }
    }

    /**
     * 关键相对次序。
     *
     * <p>数值越大越靠外；靠外意味着入站先执行、出站后执行。这几条是安全与正确性的硬约束，
     * 排错了不会报错，只会在某天以"未脱敏内容进了审计"或"预算裁剪不生效"的形式出现。</p>
     */
    @Test
    @DisplayName("关键相对次序成立")
    void criticalOrderingHolds() {
        assertTrue(MiddlewareOrders.AGENT_LIFECYCLE > MiddlewareOrders.OBSERVABILITY,
            "撤销态判定必须在一切之前，包括打点");

        assertTrue(MiddlewareOrders.TENANT_CONTEXT > MiddlewareOrders.AUDIT,
            "租户上下文必须比审计更外——审计要落库，落库依赖租户上下文");
        assertTrue(MiddlewareOrders.TENANT_CONTEXT > MiddlewareOrders.AGENT_CALL_TIMING,
            "租户上下文必须比 token 计量更外，计量同样要落库");

        assertTrue(MiddlewareOrders.AUDIT > MiddlewareOrders.MASKING,
            "审计必须比脱敏更外，否则留痕里记的是未脱敏的手机号与订单号");
        assertTrue(MiddlewareOrders.AUDIT > MiddlewareOrders.SENSITIVE_WORD,
            "审计必须比敏感词过滤更外，记录的应当是最终真正发出的内容");
        assertTrue(MiddlewareOrders.SENSITIVE_WORD > MiddlewareOrders.MASKING,
            "敏感词比脱敏更外，这样命中记录里存的原文片段已经是脱敏过的");

        assertTrue(MiddlewareOrders.SUBJECT_TOOL_AUTHORIZATION > MiddlewareOrders.TOOL_GUARD,
            "授权判定必须在入参护栏之前——没权限的工具根本不该走到改写参数那一步");
        assertTrue(MiddlewareOrders.HUMAN_APPROVAL > MiddlewareOrders.TOOL_GUARD,
            "人工确认必须在入参护栏之前");

        assertTrue(MiddlewareOrders.KNOWLEDGE_INJECTION > MiddlewareOrders.CONTEXT_BUDGET,
            "知识注入必须比预算裁剪更外，否则裁完之后内层还会继续往上下文里加东西");
        assertTrue(MiddlewareOrders.DIALOG_STAGE > MiddlewareOrders.CONTEXT_BUDGET,
            "阶段提示词注入必须比预算裁剪更外，理由同上");

        int min = Stream.of(MiddlewareOrders.AGENT_LIFECYCLE, MiddlewareOrders.OBSERVABILITY,
                MiddlewareOrders.TENANT_CONTEXT, MiddlewareOrders.AUDIT, MiddlewareOrders.MASKING,
                MiddlewareOrders.SENSITIVE_WORD, MiddlewareOrders.TOOL_GUARD,
                MiddlewareOrders.KNOWLEDGE_INJECTION, MiddlewareOrders.DIALOG_STAGE,
                MiddlewareOrders.DYNAMIC_OPTIONS, MiddlewareOrders.CONTEXT_BUDGET)
            .mapToInt(Integer::intValue).min().orElseThrow();
        assertTrue(MiddlewareOrders.CONTEXT_BUDGET == min,
            "上下文预算必须是最内层的一个，实际最小值是 " + min);
    }

    private Path sourceRoot(String moduleRelative) {
        Path fromRepoRoot = Paths.get(moduleRelative);
        if (Files.exists(fromRepoRoot)) {
            return fromRepoRoot;
        }
        return Paths.get("..").resolve(moduleRelative);
    }
}
