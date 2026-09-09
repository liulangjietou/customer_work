package com.richard.fyoung.customerwork.core.middleware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.core.dto.TaskPlanItem;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCaptureContext;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 把智能体列出的任务清单带给用户看。
 *
 * <h3>它补的是什么</h3>
 * <p>多步任务（"既要退货又要改地址"）在智能体这边是几轮工具调用，在用户那边是一段沉默的等待。
 * 他不知道办到哪一步，也不知道自己提的第二件事有没有被记住——客服场景里"是不是被落下了"
 * 恰恰是最容易引发追问和投诉的疑虑。</p>
 *
 * <p><b>更要紧的是前一半</b>：系统提示词第 8 条一直写着"可借助计划工具拆解为子任务"，
 * 而框架的 {@code TodoTools} 此前根本没挂上——每一轮对话都在指示模型使用一个不存在的能力。
 * 本类连同 {@code enableTaskList()} 一起把这条链路补完：工具挂上、清单回传、
 * <b>并且度量模型到底用不用</b>。</p>
 *
 * <h3>为什么要度量</h3>
 * <p>挂上工具不等于模型会用。若 {@code todo_write} 的调用计数长期为零，说明提示词的引导没生效，
 * 或者这个场景压根不需要拆解——那时该调整的是提示词或这个能力本身，而不是继续假设它在工作。
 * 本仓库反复出现的"造了但没接线"，下一层就是"接了但没人用"。</p>
 *
 * <h3>实现取自事件流</h3>
 * <p>工具调用的参数以 {@link ToolCallDeltaEvent} 增量到达、{@link ToolCallEndEvent} 收尾，
 * 与 {@code LoopGuardMiddleware} 同一模式：不在 {@code onActing} 与 {@code onAgent} 之间
 * 共享状态，也就不依赖"框架对 onActing 的调用是否落在本方法 next 链上"这个假设。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class TaskPlanCaptureMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanCaptureMiddleware.class);

    /** 框架内置任务清单工具名（{@code TodoTools#todoWrite} 的 @Tool name）。 */
    static final String TOOL_TODO_WRITE = "todo_write";

    private static final String CODE_PARSE_FAIL = "TASK-PLAN-PARSE-FAIL";
    private static final String M_WRITE = "customerwork.taskplan.write";

    private static final String FIELD_TODOS = "todos";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_PRIORITY = "priority";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxItems;
    private final MeterRegistry meterRegistry;

    public TaskPlanCaptureMiddleware(CustomerWorkProperties properties,
                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.maxItems = Math.max(1, properties.getPlan().getMaxSubtasks());
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(contextView -> {
            ChatTerminalCapture capture = ChatTerminalCaptureContext.get(contextView);
            if (capture == null) {
                // 工作台、调度任务等没有终止采集上下文的调用方完全透传
                return next.apply(input);
            }
            // 每次订阅一份累积器：中间件是单例，放字段会让并发会话串味
            Map<String, StringBuilder> pending = new HashMap<>();
            return next.apply(input).doOnNext(event -> collect(event, pending, capture, agent));
        });
    }

    private void collect(AgentEvent event, Map<String, StringBuilder> pending,
                         ChatTerminalCapture capture, Agent agent) {
        if (event instanceof ToolCallDeltaEvent delta) {
            if (TOOL_TODO_WRITE.equals(delta.getToolCallName()) && delta.getDelta() != null) {
                pending.computeIfAbsent(keyOf(delta.getToolCallId()), k -> new StringBuilder())
                    .append(delta.getDelta());
            }
            return;
        }
        if (event instanceof ToolCallEndEvent end && TOOL_TODO_WRITE.equals(end.getToolCallName())) {
            StringBuilder args = pending.remove(keyOf(end.getToolCallId()));
            if (args == null) {
                return;
            }
            List<TaskPlanItem> items = parse(args.toString(), agent);
            if (!items.isEmpty()) {
                capture.acceptTaskPlan(items);
                metric();
            }
        }
    }

    /**
     * 解析 {@code todo_write} 的入参。
     *
     * <p>解析失败只记日志不抛：这条链路是给用户看进度的旁路，
     * 它的故障不该让整轮对话失败——用户宁可看不到进度，也不愿收到一个错误。</p>
     */
    private List<TaskPlanItem> parse(String arguments, Agent agent) {
        try {
            JsonNode root = objectMapper.readTree(arguments);
            JsonNode todos = root.path(FIELD_TODOS);
            if (!todos.isArray()) {
                return List.of();
            }
            List<TaskPlanItem> items = new ArrayList<>();
            for (JsonNode node : todos) {
                String content = node.path(FIELD_CONTENT).asText("");
                if (content.isBlank()) {
                    continue;
                }
                items.add(new TaskPlanItem(content,
                    node.path(FIELD_STATUS).asText(""), node.path(FIELD_PRIORITY).asText("")));
                // 清单长到一定程度就不再是"给人看的进度"了，截断避免把终止帧撑大
                if (items.size() >= maxItems) {
                    break;
                }
            }
            return List.copyOf(items);
        } catch (Exception e) {
            log.error("parse task plan arguments failed, code={}, agent={}",
                CODE_PARSE_FAIL, agent == null ? "?" : agent.getName(), e);
            return List.of();
        }
    }

    private void metric() {
        if (meterRegistry != null) {
            Counter.builder(M_WRITE).register(meterRegistry).increment();
        }
    }

    private String keyOf(String toolCallId) {
        return toolCallId == null || toolCallId.isBlank() ? "" : toolCallId;
    }

    /** 顺序契约见 {@link MiddlewareOrders}：只读事件流，与循环守卫同层。 */
    @Override
    public int order() {
        return MiddlewareOrders.TASK_PLAN_CAPTURE;
    }
}
