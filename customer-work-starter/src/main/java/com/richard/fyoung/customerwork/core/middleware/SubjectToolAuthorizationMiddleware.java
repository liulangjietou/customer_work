package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.tool.mcp.McpToolAuthorizationRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.function.Function;

/** MCP 工具执行前的主体级授权闸门。 */
@Component
@Order(Integer.MIN_VALUE + 100)
public class SubjectToolAuthorizationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SubjectToolAuthorizationMiddleware.class);
    private static final String ERROR_CODE = "MCP-SUBJECT-DENIED";

    private final McpToolAuthorizationRegistry registry;

    public SubjectToolAuthorizationMiddleware(McpToolAuthorizationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        String scope = ctx == null || ctx.getUserId() == null
            ? McpToolAuthorizationRegistry.CUSTOMER_RUNTIME_SCOPE : ctx.getUserId();
        AgentInvocationIdentity identity = ctx == null ? null : ctx.get(AgentInvocationIdentity.class);
        for (ToolUseBlock call : input.toolCalls()) {
            Set<QuotaSubjectType> allowed = registry.policyFor(scope, call.getName());
            if (allowed == null) {
                // 客服运行时使用固定作用域；后台使用 ctx.userId 精确作用域。
                allowed = registry.policyFor(McpToolAuthorizationRegistry.CUSTOMER_RUNTIME_SCOPE, call.getName());
            }
            if (allowed != null && (identity == null || !allowed.contains(identity.subjectType()))) {
                String subjectType = identity == null ? "MISSING" : identity.subjectType().name();
                log.error("MCP tool subject authorization denied, code={}, scope={}, tool={}, subjectType={}",
                    ERROR_CODE, scope, call.getName(), subjectType);
                return Flux.error(new McpToolAuthorizationException(call.getName(), subjectType));
            }
        }
        return next.apply(input);
    }

    /** 安全拒绝必须保留稳定错误码，供接入层映射统一终态。 */
    public static class McpToolAuthorizationException extends RuntimeException {

        public McpToolAuthorizationException(String toolName, String subjectType) {
            super(ERROR_CODE + ": tool=" + toolName + ", subjectType=" + subjectType);
        }
    }

    /** 顺序契约见 {@link MiddlewareOrders}：授权判定必须在工具执行之前。 */
    @Override
    public int order() {
        return MiddlewareOrders.SUBJECT_TOOL_AUTHORIZATION;
    }
}
