package com.richard.fyoung.customeradmin.workspace.chat.dto;

import com.richard.fyoung.customerwork.core.middleware.ModelCompletionMiddleware;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.GenerateReason;

/** 历史展示阶段，只采用框架记录的结束原因；缺少依据的旧消息保持 UNKNOWN。 */
public enum ChatMessagePhase {
    USER_INPUT, PROCESS, FINAL, STOPPED, WAITING, FAILED, UNKNOWN;

    /** 将框架原因投影为展示阶段，不推测工具调用是否已产生业务结果。 */
    public static ChatMessagePhase of(Msg message) {
        if (message.getRole() == MsgRole.USER) {
            return USER_INPUT;
        }
        GenerateReason reason = recordedReason(message);
        if (reason == null) {
            // 新消息可使用模型明确记录的协议原因，旧消息没有该字段时仍为 UNKNOWN。
            // 非法框架原因不能被厂商字段掩盖。
            if (message.getMetadata().containsKey(Msg.METADATA_GENERATE_REASON)) {
                return UNKNOWN;
            }
            String modelReason = modelReason(message);
            if (modelReason == null) {
                return UNKNOWN;
            }
            return switch (modelReason) {
                case "stop" -> FINAL;
                case "tool_calls" -> PROCESS;
                case "length" -> STOPPED;
                case "content_filter" -> FAILED;
                default -> UNKNOWN;
            };
        }
        return switch (reason) {
            case MODEL_STOP, STRUCTURED_OUTPUT -> FINAL;
            case TOOL_CALLS -> PROCESS;
            case TOOL_SUSPENDED, PERMISSION_ASKING -> WAITING;
            case ALL_TOOLS_DENIED -> FAILED;
            case INTERRUPTED, MAX_ITERATIONS, REASONING_STOP_REQUESTED,
                 ACTING_STOP_REQUESTED, MIDDLEWARE_STOP_REQUESTED -> STOPPED;
        };
    }

    /** 优先采用框架的中断/审批等结束原因，否则返回实际记录的厂商协议值。 */
    public static String finishReason(Msg message) {
        GenerateReason reason = recordedReason(message);
        return reason == null ? modelReason(message) : reason.name();
    }

    private static String modelReason(Msg message) {
        Object value = message.getMetadata().get(
            ModelCompletionMiddleware.FINISH_REASON_KEY);
        return value instanceof String reason ? reason : null;
    }

    /** 框架 getter 对缺失或非法值默认返回 MODEL_STOP，历史展示必须读取实际保存的字段。 */
    public static GenerateReason recordedReason(Msg message) {
        Object value = message.getMetadata().get(Msg.METADATA_GENERATE_REASON);
        if (value instanceof GenerateReason reason) {
            return reason;
        }
        if (value instanceof String name) {
            try {
                return GenerateReason.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
