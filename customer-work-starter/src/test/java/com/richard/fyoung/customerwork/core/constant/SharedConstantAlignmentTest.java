package com.richard.fyoung.customerwork.core.constant;

import com.richard.fyoung.customerwork.safety.tenant.TenantAccessConstants;
import com.richard.fyoung.customerwork.tool.mcp.McpServerSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>公共常量门禁</b>：同一个字面量不得在多处各定义一遍。
 *
 * <p><b>为什么需要这个测试</b>：{@code private static final String STORE_MODE_JDBC = "jdbc"} 这一行
 * 曾出现在 21 个装配类里，同一个 "jdbc" 语义在 28 处、三种命名下各写一遍。这类重复不会编译失败、
 * 不会有任何测试变红，只会在某天有人"改了一处忘了另一处"时，以最难查的方式发作——
 * 装配走了另一条分支、统计过滤不到数据、协议两端对不上，全程不报错。</p>
 *
 * <p>常规单测照不出这类问题：每一份重复定义单独看都是对的。因此本测试<b>直接扫描源码</b>，
 * 对"同一个值有几个真相来源"下断言。新增一处重复定义，这里就会红。</p>
 *
 * <p><b>刻意不管 {@code @ConfigurationProperties} 的字段默认值</b>（{@code private String storeMode = "jdbc";}）：
 * {@code spring-boot-configuration-processor} 是源码级注解处理器，只从<b>字面量</b>初始化表达式里提取
 * {@code defaultValue}，换成常量引用后那 336 项默认值元数据会静默消失，IDE 自动补全里就再也看不到
 * "默认多少"。判定侧统一走 {@link StoreModes#isJdbc(String)} 已经够了：默认值写歪会被绑定测试与
 * 装配测试直接照出来，不是那种"静默漂移"的形状。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class SharedConstantAlignmentTest {

    private static final List<String> MODULE_SOURCE_ROOTS = List.of(
        "customer-work-starter/src/main/java",
        "customer-admin-server/src/main/java",
        "customer-work-app-server/src/main/java",
        "customer-channel/src/main/java");

    /** {@code static final String NAME = "字面量";}（含 private / public / 接口字段等各种修饰）。 */
    private static final Pattern DECLARATION =
        Pattern.compile("static final String\\s+([A-Z_0-9]+)\\s*=\\s*\"([^\"]*)\"\\s*;");

    /** 太短的值（单字符、空串）不参与判定：巧合率高、收敛收益低。 */
    private static final int MIN_VALUE_LENGTH = 2;

    /**
     * 已经有公共定义处的值：<b>值 -&gt; 应当引用的常量</b>。
     *
     * <p>与下面的 {@link #DISTINCT_CONCEPTS} 是两种判定：那份管"同一个值出现在多个文件"，
     * 这份更严——只要在别处<b>再声明一次</b>就算违规，哪怕只有一处。复制粘贴一个新的装配类
     * 正是这么开始的：先多出一份看起来无害的私有常量，等第二个人照着抄时已经晚了。</p>
     */
    private static final Map<String, String> ALREADY_CONSOLIDATED = alreadyConsolidated();

    /** 各值允许的唯一定义处（文件名）。 */
    private static final Map<String, String> DEFINITION_SITES = definitionSites();

    /**
     * 同值不同概念的豁免清单：值一样纯属巧合，合并反而会把不相干的东西绑死。
     *
     * <p>往这里加东西前先回答一个问题：这两处如果<b>其中一处改了值而另一处没改</b>，
     * 系统会不会出错？会 —— 那它们是同一个概念，该收敛；不会 —— 才是真的巧合。</p>
     */
    private static final Set<String> DISTINCT_CONCEPTS = Set.of(
        // 五个互不相干的状态机各有自己的成功/失败态（配置版本、代码评审、知识索引、角色阶段、定时任务）
        "FAILED", "SUCCESS", "APPROVED",
        // 文件操作类型 / 沙箱危险动作 / 权限动作，三套动作词汇
        "DELETE", "CREATE",
        // Nacos 注册的 URL scheme 与 MCP 的传输类型，值撞了而已
        "http",
        // WS 帧字段 / MDC 日志键 / 开放 API 响应字段
        "sessionId",
        // WS 出站协议字段 / 进程内 MDC 日志键；改动任一侧不要求另一侧同步
        "traceId",
        // store-mode 取值 / 智能体能力码 / 记忆目录名
        "memory",
        // 默认租户码 / 默认会话名
        "default",
        // 未知主体 / 未知调用类型 / 缺省会话 ID
        "unknown",
        // WS 帧类型 / 指标标签名
        "type",
        // WS 系统帧类型 / 注入消息名
        "system",
        // WS 错误帧类型与 SSE 错误事件名，两套协议
        "error", "message",
        // 两个 WebFilter 各自决定跳过哪些路径，是独立策略而非共享契约
        "/actuator",
        // JWT claim 名 / 会话属性键
        "username",
        // 诊断模式 / 填充模式
        "auto",
        // 定时任务触发方式 / 评测触发来源
        "MANUAL",
        // 智能体调用来源 / admin 调度器执行模式，两套独立的配置词汇
        "internal",
        // 微信消息类型 / 钉钉消息类型，两个平台各自的协议值
        "text",
        // WS 对话帧类型 / 智能体的基础对话能力码
        "chat");

    @Test
    @DisplayName("已收敛的字面量不得在别处重新声明")
    void consolidatedLiteralsAreNotRedeclared() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String fileName = file.getFileName().toString();
            Matcher m = DECLARATION.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                String value = m.group(2);
                String canonical = ALREADY_CONSOLIDATED.get(value);
                if (canonical == null || fileName.equals(DEFINITION_SITES.get(value))) {
                    continue;
                }
                offenders.add(String.format("%s 里的 %s = \"%s\"，应改用 %s", fileName, m.group(1), value, canonical));
            }
        }
        if (!offenders.isEmpty()) {
            fail("这些字面量已有公共定义，不要再各写一份：\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    @DisplayName("同一个字面量不得在多个文件各定义一遍")
    void noLiteralIsDeclaredInMoreThanOneFile() throws IOException {
        Map<String, Map<String, String>> byValue = new TreeMap<>();
        for (Path file : mainSources()) {
            String fileName = file.getFileName().toString();
            Matcher m = DECLARATION.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                if (m.group(2).length() < MIN_VALUE_LENGTH || DISTINCT_CONCEPTS.contains(m.group(2))) {
                    continue;
                }
                byValue.computeIfAbsent(m.group(2), k -> new LinkedHashMap<>()).put(fileName, m.group(1));
            }
        }

        List<String> offenders = new ArrayList<>();
        byValue.forEach((value, sites) -> {
            if (sites.size() < 2) {
                return;
            }
            List<String> where = new ArrayList<>();
            sites.forEach((fileName, constantName) -> where.add(fileName + ":" + constantName));
            offenders.add(String.format("\"%s\" 被 %d 处各定义一遍 -> %s", value, sites.size(), String.join(", ", where)));
        });

        if (!offenders.isEmpty()) {
            fail("同一个字面量有多个真相来源，请收敛到公共常量类（若确属同值不同概念，"
                + "加进 DISTINCT_CONCEPTS 并写明理由）：\n  " + String.join("\n  ", offenders));
        }
    }

    // ==================== 清单 ====================

    private static Map<String, String> alreadyConsolidated() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(StoreModes.JDBC, "StoreModes.JDBC");
        map.put(StoreModes.REDIS, "StoreModes.REDIS");
        map.put(AgentFileNames.SKILL_MD, "AgentFileNames.SKILL_MD");
        map.put(AgentFileNames.MEMORY_MD, "AgentFileNames.MEMORY_MD");
        map.put(HttpAuthConstants.BEARER_PREFIX, "HttpAuthConstants.BEARER_PREFIX");
        map.put(HttpAuthConstants.AGENT_TOKEN_HEADER, "HttpAuthConstants.AGENT_TOKEN_HEADER");
        map.put(OpenApiProtocol.TOKEN_HEADER, "OpenApiProtocol.TOKEN_HEADER");
        map.put(OpenApiProtocol.RUNTIME_CONFIG_ACK_TOKEN_HEADER,
            "OpenApiProtocol.RUNTIME_CONFIG_ACK_TOKEN_HEADER");
        map.put(TenantAccessConstants.ACCESS_EPOCH_KEY, "TenantAccessConstants.ACCESS_EPOCH_KEY");
        map.put(OpenApiProtocol.SSE_DONE_MARKER, "OpenApiProtocol.SSE_DONE_MARKER");
        map.put(ModelProviders.DASHSCOPE, "ModelProviders.DASHSCOPE");
        map.put(ModelProviders.OPENAI, "ModelProviders.OPENAI");
        map.put(ModelProviders.ANTHROPIC, "ModelProviders.ANTHROPIC");
        map.put(ModelProviders.GEMINI, "ModelProviders.GEMINI");
        map.put(ModelProviders.OLLAMA, "ModelProviders.OLLAMA");
        map.put(FactTypes.QUALITY_FAILURE, "FactTypes.QUALITY_FAILURE");
        map.put(FactTypes.NEGATIVE_FEEDBACK, "FactTypes.NEGATIVE_FEEDBACK");
        map.put(DevDefaultCredentials.USER_JWT_SECRET, "DevDefaultCredentials.USER_JWT_SECRET");
        map.put(DevDefaultCredentials.AGENT_ACCESS_SECRET, "DevDefaultCredentials.AGENT_ACCESS_SECRET");
        map.put(DevDefaultCredentials.MINIO_CREDENTIAL, "DevDefaultCredentials.MINIO_CREDENTIAL");
        map.put(McpServerSpec.TYPE_STDIO, "McpServerSpec.TYPE_STDIO");
        map.put(McpServerSpec.TYPE_SSE, "McpServerSpec.TYPE_SSE");
        map.put(McpServerSpec.MCP_SERVERS_WRAPPER_KEY, "McpServerSpec.MCP_SERVERS_WRAPPER_KEY");
        // 下面几处的公共定义在别的包/模块，这里按值登记即可
        map.put(McpServerSpec.TRANSPORT_STREAMABLE_HTTP, "McpServerSpec.TRANSPORT_STREAMABLE_HTTP");
        map.put("agent-tool", "ToolConstants.AGENT_TOOL_SESSION");
        map.put("EVAL-LOAD-FAIL", "EvalErrorCodes.LOAD_FAIL");
        map.put("Asia/Shanghai", "DevToolConstants.DEFAULT_ZONE");
        map.put("UTF8", "DevToolConstants.ENCODING_UTF8");
        map.put("HEX", "DevToolConstants.ENCODING_HEX");
        map.put("BASE64", "DevToolConstants.ENCODING_BASE64");
        map.put("已取消", "OrderStatuses.CANCELLED");
        map.put("已发货", "OrderStatuses.SHIPPED");
        map.put("待发货", "OrderStatuses.PENDING_SHIPMENT");
        map.put("已退款", "OrderStatuses.REFUNDED");
        map.put("CP", "ComplaintBackend.ID_PREFIX");
        map.put(".git", "GitWorkspaceService.GIT_DIR_NAME");
        map.put("vibecoding", "AgentCapabilities.VIBECODING");
        map.put("subagent", "AgentCapabilities.SUBAGENT");
        map.put("skill-learning", "AgentCapabilities.SKILL_LEARNING");
        map.put("dynamic-subagent", "AgentCapabilities.DYNAMIC_SUBAGENT");
        map.put("tasklist", "AgentCapabilities.TASKLIST");
        map.put("super_admin", "SystemRoles.SUPER_ADMIN");
        map.put(ChannelTypes.DINGTALK, "ChannelTypes.DINGTALK");
        map.put(ChannelTypes.WECHAT, "ChannelTypes.WECHAT");
        map.put("Authorization", "org.springframework.http.HttpHeaders.AUTHORIZATION");
        map.put("Content-Type", "org.springframework.http.HttpHeaders.CONTENT_TYPE");
        map.put("application/json", "org.springframework.http.MediaType.APPLICATION_JSON_VALUE");
        map.put("application/octet-stream", "org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE");
        return map;
    }

    /** 每个值唯一被允许的声明处（文件名）；不在此表的值意味着"任何地方都不许再声明"。 */
    private static Map<String, String> definitionSites() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(StoreModes.JDBC, "StoreModes.java");
        map.put(StoreModes.REDIS, "StoreModes.java");
        map.put(AgentFileNames.SKILL_MD, "AgentFileNames.java");
        map.put(AgentFileNames.MEMORY_MD, "AgentFileNames.java");
        map.put(HttpAuthConstants.BEARER_PREFIX, "HttpAuthConstants.java");
        map.put(HttpAuthConstants.AGENT_TOKEN_HEADER, "HttpAuthConstants.java");
        map.put(OpenApiProtocol.TOKEN_HEADER, "OpenApiProtocol.java");
        map.put(OpenApiProtocol.RUNTIME_CONFIG_ACK_TOKEN_HEADER, "OpenApiProtocol.java");
        map.put(TenantAccessConstants.ACCESS_EPOCH_KEY, "TenantAccessConstants.java");
        map.put(OpenApiProtocol.SSE_DONE_MARKER, "OpenApiProtocol.java");
        map.put(ModelProviders.DASHSCOPE, "ModelProviders.java");
        map.put(ModelProviders.OPENAI, "ModelProviders.java");
        map.put(ModelProviders.ANTHROPIC, "ModelProviders.java");
        map.put(ModelProviders.GEMINI, "ModelProviders.java");
        map.put(ModelProviders.OLLAMA, "ModelProviders.java");
        map.put(FactTypes.QUALITY_FAILURE, "FactTypes.java");
        map.put(FactTypes.NEGATIVE_FEEDBACK, "FactTypes.java");
        map.put(DevDefaultCredentials.USER_JWT_SECRET, "DevDefaultCredentials.java");
        map.put(DevDefaultCredentials.AGENT_ACCESS_SECRET, "DevDefaultCredentials.java");
        map.put(DevDefaultCredentials.MINIO_CREDENTIAL, "DevDefaultCredentials.java");
        map.put(McpServerSpec.TYPE_STDIO, "McpServerSpec.java");
        map.put(McpServerSpec.TYPE_SSE, "McpServerSpec.java");
        map.put(McpServerSpec.MCP_SERVERS_WRAPPER_KEY, "McpServerSpec.java");
        map.put(McpServerSpec.TRANSPORT_STREAMABLE_HTTP, "McpServerSpec.java");
        map.put("agent-tool", "ToolConstants.java");
        map.put("EVAL-LOAD-FAIL", "EvalErrorCodes.java");
        map.put("Asia/Shanghai", "DevToolConstants.java");
        map.put("UTF8", "DevToolConstants.java");
        map.put("HEX", "DevToolConstants.java");
        map.put("BASE64", "DevToolConstants.java");
        map.put("已取消", "OrderStatuses.java");
        map.put("已发货", "OrderStatuses.java");
        map.put("待发货", "OrderStatuses.java");
        map.put("已退款", "OrderStatuses.java");
        map.put("CP", "ComplaintBackend.java");
        map.put(".git", "GitWorkspaceService.java");
        map.put("vibecoding", "AgentCapabilities.java");
        map.put("subagent", "AgentCapabilities.java");
        map.put("skill-learning", "AgentCapabilities.java");
        map.put("dynamic-subagent", "AgentCapabilities.java");
        map.put("tasklist", "AgentCapabilities.java");
        map.put("super_admin", "SystemRoles.java");
        map.put(ChannelTypes.DINGTALK, "ChannelTypes.java");
        map.put(ChannelTypes.WECHAT, "ChannelTypes.java");
        return map;
    }

    // ==================== 扫描 ====================

    private List<Path> mainSources() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String moduleRelative : MODULE_SOURCE_ROOTS) {
            Path root = sourceRoot(moduleRelative);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(result::add);
            }
        }
        if (result.isEmpty()) {
            fail("没有扫描到任何源码文件，检查 MODULE_SOURCE_ROOTS 与工作目录");
        }
        return result;
    }

    /** 测试的工作目录是模块目录，仓库根在其上一层；两种布局都兼容（与装配门禁测试一致）。 */
    private Path sourceRoot(String moduleRelative) {
        Path fromRepoRoot = Paths.get(moduleRelative);
        return Files.exists(fromRepoRoot) ? fromRepoRoot : Paths.get("..").resolve(moduleRelative);
    }
}
