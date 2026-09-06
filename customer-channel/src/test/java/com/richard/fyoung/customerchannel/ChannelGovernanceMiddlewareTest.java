package com.richard.fyoung.customerchannel;

import com.richard.fyoung.customerwork.core.middleware.AuditMiddleware;
import com.richard.fyoung.customerwork.core.middleware.ContextBudgetMiddleware;
import com.richard.fyoung.customerwork.core.middleware.IndirectInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.MaskingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.PromptInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.TenantContextMiddleware;
import com.richard.fyoung.customerwork.core.middleware.ToolGuardMiddleware;
import io.agentscope.core.middleware.MiddlewareBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>渠道治理装配门禁（运行时断言）</b>。
 *
 * <p><b>守的是什么 bug</b>：{@code AgentGovernanceAssembler#applyTo} 里只有生命周期与可观测两个
 * 中间件是直接 new 的，其余全部走 {@code pluggableMiddlewares.orderedStream()} 从容器取。
 * 本模块用 {@code @SpringBootApplication} 只扫自己的包，starter 里带 {@code @Component} 的中间件
 * 一个都不会被注册——于是钉钉/企微/微信/飞书四条对外渠道上，出站敏感词过滤、PII 脱敏、
 * 提示词注入防护、租户上下文、token 计量与审计全部缺失。</p>
 *
 * <p><b>为什么已有的门禁挡不住</b>：{@code AgentAssemblyAlignmentTest} 是<b>文本扫描</b>——
 * 它断言源码里出现过 {@code governanceAssembler.applyTo} 这个字符串。本模块确实调了，
 * 门禁照常通过，而装进去的是空集。"调用了"与"装进去了"是两件事，只有起真实容器才能分辨。</p>
 *
 * <p>因此这个测试刻意用 {@code @SpringBootTest} 起完整上下文，并直接检查装配器所依赖的
 * 那个 {@code ObjectProvider}——它解析出什么，装配器就装什么，两者是同一个东西。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@SpringBootTest
class ChannelGovernanceMiddlewareTest {

    /**
     * 期望的治理中间件数量下限。
     *
     * <p>取当前显式声明的条数。新增 starter 中间件而忘了在本模块声明时，这里不会自动变红——
     * 数量下限只能挡住"整体退化"。真正逐项的保证是下面那个按类型断言的用例，
     * 新增关键治理能力时应当同步往那份清单里加一条。</p>
     *
     * <p><b>仍未在渠道链路声明的三个（已知缺口，不是遗漏）</b>：</p>
     * <ul>
     *   <li>{@code SensitiveWordMiddleware} —— 依赖 {@code SensitiveWordFilter}，
     *       而词库加载与刷新是 starter 的一整套基础设施，本模块容器里没有；</li>
     *   <li>{@code SubjectToolAuthorizationMiddleware} —— 依赖 {@code McpToolAuthorizationRegistry}；</li>
     *   <li>{@code AgentCallTimingMiddleware} —— 依赖 {@code ToolKindRegistry} 与
     *       {@code AgentCallRecordSink}（要落库）。它是 token 的唯一落点，
     *       在位之前渠道链路的用量既不进配额也不进账单。</li>
     * </ul>
     * <p>这三项需要把对应基础设施一并接进本模块，属于独立的一件事；在此之前如实记录，
     * 不用"声明了 12 个"制造覆盖完整的错觉。</p>
     */
    private static final int MIN_GOVERNANCE_MIDDLEWARES = 12;

    @Autowired
    private ObjectProvider<MiddlewareBase> pluggableMiddlewares;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("渠道容器必须解析出治理中间件，而不是空集")
    void assemblerProviderMustNotBeEmpty() {
        List<MiddlewareBase> resolved = pluggableMiddlewares.orderedStream().toList();

        assertFalse(resolved.isEmpty(),
            "AgentGovernanceAssembler 拿到的是空集——IM 渠道上脱敏、敏感词、注入防护、"
                + "租户上下文、审计与 token 计量会全部失效，而调用 applyTo 的那行代码看起来一切正常");
        assertTrue(resolved.size() >= MIN_GOVERNANCE_MIDDLEWARES,
            "治理中间件数量低于下限：期望至少 " + MIN_GOVERNANCE_MIDDLEWARES
                + "，实际 " + resolved.size() + "。是不是有 @Bean 声明被删了？");
    }

    /**
     * 逐项断言关键治理能力在渠道链路上真实存在。
     *
     * <p>这几类的缺失后果最直接：违规内容与个人信息会原样发给站外 IM 平台的真实客户，
     * 注入防护缺席则对外入口门户大开，租户上下文缺席会让持久层隔离失效。</p>
     */
    @Test
    @DisplayName("关键治理能力逐项在位")
    void criticalGovernanceCapabilitiesArePresent() {
        List<Class<? extends MiddlewareBase>> required = List.of(
            MaskingMiddleware.class,
            PromptInjectionGuardMiddleware.class,
            IndirectInjectionGuardMiddleware.class,
            TenantContextMiddleware.class,
            ToolGuardMiddleware.class,
            ContextBudgetMiddleware.class,
            AuditMiddleware.class);

        Map<String, MiddlewareBase> beans = context.getBeansOfType(MiddlewareBase.class);
        for (Class<? extends MiddlewareBase> type : required) {
            boolean present = beans.values().stream().anyMatch(type::isInstance);
            assertTrue(present, "渠道链路缺少治理中间件：" + type.getSimpleName()
                + "。本模块不扫描 starter 的 @Component，必须在 CustomerWebAgentConfig 里显式声明。");
        }
    }
}
