package com.richard.fyoung.customeradmin.config;

import com.richard.fyoung.customerwork.core.middleware.IndirectInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.MaskingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.PromptInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SubjectToolAuthorizationMiddleware;
import com.richard.fyoung.customerwork.safety.security.SensitiveDataMasker;
import com.richard.fyoung.customerwork.tool.mcp.McpToolAuthorizationRegistry;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 智能体运行时公共 Bean：仿 {@code customer-channel} 的 {@code CustomerWebAgentConfig} 手法——
 * 本模块已用 {@code spring.autoconfigure.exclude} 关闭 starter 的自动装配，手动暴露
 * {@link AgentStateStore}/{@link PermissionContextState}，供 {@code AdminAgentInstanceFactory}
 * 动态装配任意智能体时复用（同进程共享，状态按 {@code (userId=agentCode, sessionId)} 天然隔离）。
 *
 * <p>状态存储用 {@link MysqlAgentStateStore}，复用本模块自身已配置的 {@link DataSource}（同一个
 * {@code customer_admin} 库，物理上不新开一个库）——需求"历史对话要能查到重启前的"要求持久化，
 * 不能再用进程内 {@code InMemoryAgentStateStore}。表结构由 Flyway {@code V4__chat_session_state.sql}
 * / DBA 预审的 {@code mysql/02-customer-admin/} 迁移副本管理，故这里 {@code createIfNotExist=false}，与本模块
 * "生产不自动建表"的既有约定一致。</p>
 *
 * <p><b>坑</b>：{@link MysqlAgentStateStore} 的 2 参数构造函数会把库名硬编码成默认值
 * {@code agentscope}，而不是取自 {@link DataSource} 连接串指向的当前库——必须用
 * 4 参数构造函数显式传库名。库名从 {@code admin.mysql.database-name} 配置项读取（而不是
 * 在代码里硬编码字面量），保证它始终跟 {@code spring.datasource.url} 指向同一个库——两者
 * 曾经分别硬编码导致库名不一致时启动直接报"表不存在"（见批次六本地联调排查记录）。</p>
 *
 * <p><b>坑（批次六真实联调才暴露，之前测试全用假 API key 从没跑到工具执行这一步）</b>：
 * {@link PermissionMode#DEFAULT} 官方语义是"所有操作都需要显式授权规则才能执行"——不是字面意义上
 * "默认放行"。真实模型决定调用一个真实 MCP 工具时，该工具调用会被置为 ASKING（等待人工确认）状态，
 * 而这套确认回传流程我们的 {@code ChatService} 根本没实现，于是框架直接抛
 * {@code IllegalStateException}（"Agent is paused for human-in-the-loop confirmation..."），
 * 前端表现为思考过程正常增量、决定调用工具后突然中断、只吐一句兜底话术。改用
 * {@link PermissionMode#BYPASS}（"所有操作直接放行，不做规则评估"）——本场景的授权已经在
 * Sa-Token 鉴权 + {@code agent:view} 权限点这一层做完了，不需要在 Agent 运行时内部再叠一层
 * 工具级人工确认。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class AdminAgentRuntimeConfig {

    private static final String TABLE_NAME = "ai_chat_session_state";

    @Value("${admin.mysql.database-name}")
    private String databaseName;

    @Bean
    public AgentStateStore agentStateStore(DataSource dataSource) {
        return new MysqlAgentStateStore(dataSource, databaseName, TABLE_NAME, false);
    }

    @Bean
    public PermissionContextState permissionContextState() {
        return PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
    }

    /**
     * 间接注入防护中间件（工具/MCP 结果隔离）。starter 的自动装配在本模块被排除，这里按
     * {@code admin.injection-guard.*} 显式构建。
     *
     * <p>做成共享单例而非每个智能体一份：中间件本身无状态（只有两个用于观测的累加计数器），
     * 与需要按 {@code agentCode} 构建的 {@code KnowledgeRetrievalMiddleware} 不同——那个要知道
     * "本智能体绑了哪些知识库"，这个对所有智能体的处理逻辑完全一致。</p>
     */
    @Bean
    public IndirectInjectionGuardMiddleware indirectInjectionGuardMiddleware(
            AdminInjectionGuardProperties properties, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new IndirectInjectionGuardMiddleware(properties.isEnabled(), properties.isDetectionEnabled(),
            properties.getInjectionPatterns(), meterRegistryProvider.getIfAvailable());
    }

    /**
     * 出站脱敏中间件（{@code admin.masking.*}）与下面的直接注入防护，都是 starter 侧的
     * {@code @Component}，本模块排除了自动装配后必须显式建。
     *
     * <p><b>补上之前，后台对话链路两者皆无、且连一个能打开它们的配置项都没有</b>，
     * 而客服端两者都有——运维在客服端验证过脱敏生效后，会理所当然地以为全局都保护上了。
     * 这正是"能力只接在一条路径上"的典型：能力本身造好了，另一条路径没接。</p>
     */
    /**
     * Bean 名刻意不叫 {@code sensitiveDataMasker}：admin 自己有一个同名的
     * {@code common.log.SensitiveDataMasker}（操作日志脱敏用，@Component 扫描注册），
     * 重名会直接抛 {@code BeanDefinitionOverrideException} 让上下文起不来。
     * 两者类型不同、职责不同——那个脱的是审计日志，这个脱的是发给用户的回复。
     */
    @Bean
    public SensitiveDataMasker agentOutboundMasker(AdminMaskingProperties properties) {
        return new SensitiveDataMasker(properties);
    }

    @Bean
    public MaskingMiddleware maskingMiddleware(AdminMaskingProperties properties, SensitiveDataMasker masker) {
        return new MaskingMiddleware(properties.isEnabled(), masker);
    }

    /** 直接提示词注入防护（{@code admin.prompt-guard.*}）：拦用户自己在输入框里打的越狱话术。 */
    @Bean
    public PromptInjectionGuardMiddleware promptInjectionGuardMiddleware(
            AdminPromptGuardProperties properties, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new PromptInjectionGuardMiddleware(properties.isEnabled(), properties.getRefusalReply(),
            properties.getInjectionPatterns(), meterRegistryProvider.getIfAvailable());
    }

    /** admin 排除了 starter 自动装配，MCP 主体授权登记表与执行闸门在此显式提供。 */
    @Bean
    public McpToolAuthorizationRegistry mcpToolAuthorizationRegistry() {
        return new McpToolAuthorizationRegistry();
    }

    @Bean
    public SubjectToolAuthorizationMiddleware subjectToolAuthorizationMiddleware(
            McpToolAuthorizationRegistry registry) {
        return new SubjectToolAuthorizationMiddleware(registry);
    }
}
