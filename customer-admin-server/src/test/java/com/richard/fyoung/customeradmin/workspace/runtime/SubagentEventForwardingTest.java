package com.richard.fyoung.customeradmin.workspace.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性验证探针：harness spawn 出的子 Agent 事件，是否原生透传到父 HarnessAgent 的
 * v1 {@code stream(msgs, StreamOptions, RuntimeContext)} 订阅方——即框架 open issue #1954
 * "父子上下文不传递" 是否命中本项目链路。
 *
 * <p>链路事实（已核实）：父 Agent 走 v1 {@code stream()} 时，框架在 {@code AgentBase.createEventStream}
 * 里把 {@code SubagentEventBus} 装进 Reactor Context；harness 的 {@code AgentSpawnTool} 在 spawn 点若能
 * 从 Context 拿到 bus，就把子 Agent 事件（携带 {@link EventSource}）直推父 sink。#1954 的风险 =
 * Reactor Context 传播断裂，导致 spawn 点拿不到 bus、子事件到不了父流。</p>
 *
 * <p>本测试用脚本化假 {@link Model} 驱动整条链路，纯内存、可重复、不依赖 MySQL/Redis/网络/真实 LLM：
 * <ul>
 *   <li>父 Model 第一轮产出一次 {@code agent_spawn} 工具调用（同步 spawn 子 Agent），第二轮产出纯文本收尾；</li>
 *   <li>子 Model 直接产出一段纯文本回答（一轮内收敛）。</li>
 * </ul>
 * 订阅父 {@code stream(...)} 收集全部事件后，用两个独立 {@code @Test} 明确区分三种结局：
 * <ol>
 *   <li><b>①透传正常</b>：存在 {@code getSource()!=null} 的事件，且子 Agent 的 path 含 doc-writer、有子 AGENT_RESULT；</li>
 *   <li><b>②spawn 发生但事件无 source/未到达</b>：子 Model 被调用过（spawn 执行了），但父流拿不到带 source 的事件
 *       → 命中 #1954，用 {@link Assumptions} 标记为"框架不支持"（跳过、非红），并打印全部事件诊断；</li>
 *   <li><b>③spawn 根本没发生</b>：子 Model 从未被调用（工具没被触发）→ 装配问题（工具名/参数 schema 不对），
 *       断言直接红，提示修测试而非下结论。</li>
 * </ol>
 *
 * @author owlzhangfq@gmail.com
 */
class SubagentEventForwardingTest {

    private static final Logger log = LoggerFactory.getLogger(SubagentEventForwardingTest.class);

    /** 子 Agent 注册名（= spawn 时的 agent_id，也应出现在子事件的调用链 path 里）。 */
    private static final String SUBAGENT_ID = "doc-writer";
    /** harness 注册的子 Agent spawn 工具名（javap 反汇编 SubagentsMiddleware 常量池核实）。 */
    private static final String SPAWN_TOOL_NAME = "agent_spawn";
    private static final String SPAWN_TOOL_KEYWORD = "spawn";
    /** 父 Model 首轮 spawn 工具调用的参数键（SubagentsMiddleware 系统提示模板核实的 schema）。 */
    private static final String ARG_AGENT_ID = "agent_id";
    private static final String ARG_TASK = "task";
    private static final String ARG_TIMEOUT_SECONDS = "timeout_seconds";
    private static final int SPAWN_TIMEOUT_SECONDS = 30;
    private static final String CHILD_ANSWER = "子Agent的回答内容：文档大纲已生成";
    private static final String PARENT_FINAL = "父Agent收尾：子任务已完成";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(60);

    @TempDir
    Path workspace;

    /** 子 Model 被调用次数——> 0 唯一充要地证明 spawn 真正执行了子 Agent（用于区分结局②与③）。 */
    private final AtomicInteger childModelCalls = new AtomicInteger();
    /** 父 Model 每轮拿到的工具名清单（诊断装配：spawn 工具是否被接线进父 toolkit）。 */
    private final List<String> parentToolNamesOffered = new CopyOnWriteArrayList<>();

    // ==================== 结局①/②/③ 探针（核心） ====================

    @Test
    void subagentEvents_shouldForwardToParentStream_orDiagnoseFrameworkGap() {
        List<Event> events = runParentStream();

        // 全量事件诊断日志（无论结局如何都打印，作为证据）
        logEventTrace(events);

        boolean spawnExecuted = childModelCalls.get() > 0;
        boolean anySourced = events.stream().anyMatch(e -> e.getSource() != null);

        if (anySourced) {
            // ===== 结局①：透传正常 =====
            // 核心断言 (a)：存在 source != null 的事件（子 Agent 事件被透传到父流）
            List<Event> sourced = events.stream().filter(e -> e.getSource() != null).collect(Collectors.toList());
            log.info("[probe] OUTCOME_1 forwarding works: sourcedEventCount={}", sourced.size());

            // 断言 (b)：source.getPath() 含子 Agent id，且存在子 Agent 的 AGENT_RESULT 事件
            boolean pathHasSubagent = sourced.stream()
                .map(Event::getSource).map(EventSource::getPath)
                .anyMatch(p -> p != null && p.contains(SUBAGENT_ID));
            assertTrue(pathHasSubagent,
                "透传发生但子事件 path 未含 '" + SUBAGENT_ID + "'，实际 paths="
                    + sourced.stream().map(e -> e.getSource().getPath()).collect(Collectors.toList()));

            boolean hasChildAgentResult = sourced.stream()
                .anyMatch(e -> e.getType() == EventType.AGENT_RESULT);
            assertTrue(hasChildAgentResult,
                "透传发生但未捕获子 Agent 的 AGENT_RESULT 事件，实际带 source 的事件类型="
                    + sourced.stream().map(e -> e.getType().name()).collect(Collectors.toList()));
            return;
        }

        // 没有任何带 source 的事件 —— 先排除结局③（装配错误），再判定结局②（框架断裂）
        // ===== 结局③：spawn 根本没发生（装配问题，红）=====
        assertTrue(spawnExecuted,
            "结局③ 装配问题：agent_spawn 工具调用未触发子 Agent（childModelCalls=0）。"
                + "说明父 Model 脚本的工具调用格式与真实 spawn 工具不匹配——请核对工具名/参数 schema，修测试而非下结论。"
                + " 父 Model 本轮拿到的工具清单=" + parentToolNamesOffered);

        // ===== 结局②：spawn 执行了但事件无 source/未到达父流（命中 #1954，跳过而非红）=====
        String diagnosis = "结局② 命中框架 open issue #1954：子 Agent 已执行（childModelCalls="
            + childModelCalls.get() + "），但父流未收到任何带 EventSource 的事件——"
            + "Reactor Context 中的 SubagentEventBus 未传播到 spawn 点，子事件到不了父 sink。"
            + " 父流收到的事件类型序列=" + events.stream().map(e -> e.getType().name()).collect(Collectors.toList());
        log.info("[probe] OUTCOME_2 framework gap (#1954): {}", diagnosis);
        // Assumptions 中止：标记为"框架不支持"（测试跳过、非失败），与"装配错误(红)"区分开
        Assumptions.abort(diagnosis);
    }

    // ==================== 装配守卫：spawn 工具确实接线进父 Model（区分结局③） ====================

    @Test
    void spawnTool_shouldBeOfferedToParentModel_assemblyGuard() {
        runParentStream();

        // 父 Model 至少被调用过一次，且拿到的工具清单里含 spawn 工具——证明 subagentFactory 把
        // agent_spawn 接线进了父 HarnessAgent 的 toolkit（若此断言失败，说明是装配问题，不是框架问题）
        assertFalse(parentToolNamesOffered.isEmpty(),
            "父 Model 从未被调用，链路根本没跑起来——检查 HarnessAgent 装配");
        boolean spawnOffered = parentToolNamesOffered.stream()
            .anyMatch(n -> n != null && n.contains(SPAWN_TOOL_KEYWORD));
        log.info("[probe] assembly guard: parentToolNamesOffered={}", parentToolNamesOffered);
        assertTrue(spawnOffered,
            "父 Model 未拿到任何名字含 '" + SPAWN_TOOL_KEYWORD + "' 的工具，spawn 能力未接线进父 toolkit，"
                + "实际工具清单=" + parentToolNamesOffered);
    }

    // ==================== 链路装配 ====================

    /** 构造父 HarnessAgent + 子 Agent，订阅 v1 stream() 并收集全部事件（镜像 ChatService 的生产订阅参数）。 */
    private List<Event> runParentStream() {
        childModelCalls.set(0);
        parentToolNamesOffered.clear();

        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        PermissionContextState permission = PermissionContextState.builder()
            .mode(PermissionMode.BYPASS)  // 旁路权限，避免 spawn 工具触发交互式审批阻塞确定性测试
            .build();

        // 内层父 ReActAgent（走 buildInnerReActAgent 同款最小装配）
        ReActAgent parentInner = ReActAgent.builder()
            .name("MainAgent")
            .sysPrompt("你是主 Agent，遇到文档任务就委派给子 Agent。")
            .model(parentModel())
            .toolkit(new Toolkit())
            .stateStore(stateStore)
            .permissionContext(permission)
            .maxIters(5)
            .build();

        // 父升级为 HarnessAgent，并注册静态子 Agent（镜像 AdminAgentInstanceFactory#registerSubagents）
        HarnessAgent parent = HarnessAgent.Builder.fromAgent(parentInner)
            .stateStore(stateStore)
            .permissionContext(permission)
            // 框架 #1644 缓解：未显式设置 generateOptions 时 streamEvents() 会 NPE
            .generateOptions(GenerateOptions.builder().build())
            .workspace(workspace)
            .subagentFactory(SUBAGENT_ID, id -> buildChildAgent(stateStore, permission))
            .disableDynamicSubagents()  // 只测静态注册的子 Agent 路径，关掉运行时动态造 Agent 的噪音
            .build();

        RuntimeContext ctx = RuntimeContext.builder()
            .userId("probe-user").sessionId("probe-session").build();

        // 镜像 ChatService.chatStream 的生产订阅参数（eventTypes/incremental/includeReasoningChunk/includeActingChunk）
        StreamOptions options = StreamOptions.builder()
            .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
            .incremental(true)
            .includeReasoningChunk(true)
            .includeActingChunk(true)
            .build();

        Msg userMsg = Msg.builder().role(MsgRole.USER)
            .textContent("帮我写一份产品文档").build();

        List<Event> events = parent.stream(List.of(userMsg), options, ctx)
            .collectList().block(BLOCK_TIMEOUT);
        return events != null ? events : Collections.emptyList();
    }

    /** 子 Agent：纯内层 ReActAgent，模型直接产出一段文本，一轮内收敛（镜像 buildSubagentInner）。 */
    private Agent buildChildAgent(InMemoryAgentStateStore stateStore, PermissionContextState permission) {
        return ReActAgent.builder()
            .name("DocWriter")
            .sysPrompt("你是文档撰写子 Agent。")
            .model(childModel())
            .toolkit(new Toolkit())
            .stateStore(stateStore)
            .permissionContext(permission)
            .maxIters(3)
            .build();
    }

    // ==================== 脚本化假 Model ====================

    /**
     * 父 Model 脚本：第一次调用产出一次 {@code agent_spawn} 工具调用（同步 spawn 子 Agent），
     * 之后（工具返回后的收尾轮）产出纯文本 → ReAct 判定为最终回复收敛。
     * 每次调用把拿到的工具名记进 {@link #parentToolNamesOffered} 供装配诊断。
     */
    private Model parentModel() {
        return new Model() {
            private final AtomicInteger round = new AtomicInteger();

            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                List<String> names = tools == null ? List.of()
                    : tools.stream().map(ToolSchema::getName).collect(Collectors.toList());
                parentToolNamesOffered.addAll(names);
                int r = round.getAndIncrement();

                if (r == 0) {
                    // 首轮：发起 spawn 工具调用。工具名优先用真实 agent_spawn，找不到就从工具清单里挑含 spawn 的，
                    // 都没有则退化为纯文本（此时子 Model 不会被调用 → 结局③ 会被装配断言逮住）。
                    String toolName = resolveSpawnToolName(names);
                    if (toolName != null) {
                        Map<String, Object> args = Map.of(
                            ARG_AGENT_ID, SUBAGENT_ID,
                            ARG_TASK, "撰写产品文档大纲",
                            ARG_TIMEOUT_SECONDS, SPAWN_TIMEOUT_SECONDS);
                        // incremental(true) 流式路径下，框架从 content（原始参数 JSON 字符串）重建工具入参，
                        // 不读 input map——真实 LLM 的工具调用参数正是以 JSON 字符串分片到达。两者都填。
                        ToolUseBlock spawnCall = new ToolUseBlock(
                            UUID.randomUUID().toString(), toolName, args, toJson(args), null);
                        return Flux.just(response(List.of(spawnCall), "tool_calls"));
                    }
                    log.info("[probe] parent round0: no spawn tool offered, fallback to text. offered={}", names);
                }
                // 收尾轮：纯文本 → ReAct 收敛为最终回复
                ContentBlock text = TextBlock.builder().text(PARENT_FINAL).build();
                return Flux.just(response(List.of(text), "stop"));
            }

            @Override
            public String getModelName() {
                return "scripted-parent-model";
            }
        };
    }

    /** 子 Model 脚本：无条件产出一段纯文本回答，一轮内收敛。每次被调用即 +1，作为 spawn 真实执行的证据。 */
    private Model childModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                childModelCalls.incrementAndGet();
                ContentBlock text = TextBlock.builder().text(CHILD_ANSWER).build();
                return Flux.just(response(List.of(text), "stop"));
            }

            @Override
            public String getModelName() {
                return "scripted-child-model";
            }
        };
    }

    /** 从父 Model 拿到的工具清单里解析出 spawn 工具名：优先精确匹配 agent_spawn，退而求含 spawn 的任一名。 */
    private String resolveSpawnToolName(List<String> toolNames) {
        if (toolNames.contains(SPAWN_TOOL_NAME)) {
            return SPAWN_TOOL_NAME;
        }
        return toolNames.stream()
            .filter(n -> n != null && n.contains(SPAWN_TOOL_KEYWORD))
            .findFirst().orElse(null);
    }

    /** 把参数 Map 序列化为 JSON 字符串（喂给 ToolUseBlock 的 content，模拟真实 LLM 的工具参数分片）。 */
    private String toJson(Map<String, Object> args) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args);
        } catch (Exception e) {
            throw new IllegalStateException("serialize spawn args failed", e);
        }
    }

    private ChatResponse response(List<ContentBlock> content, String finishReason) {
        return ChatResponse.builder()
            .id(UUID.randomUUID().toString())
            .content(content)
            .usage(new ChatUsage(1, 1, 0.0))
            .finishReason(finishReason)
            .build();
    }

    /** 打印收到的全部事件（类型 + source 有无 + path），作为三种结局判定的证据。 */
    private void logEventTrace(List<Event> events) {
        List<String> trace = new ArrayList<>();
        for (Event e : events) {
            EventSource src = e.getSource();
            trace.add(e.getType().name() + "(source=" + (src == null ? "null" : src.getPath()) + ")");
        }
        log.info("[probe] childModelCalls={}, eventTrace={}, parentToolNamesOffered={}",
            childModelCalls.get(), trace, parentToolNamesOffered);
    }
}
