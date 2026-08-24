package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.observability.MdcContextLifter;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.UUID;

/** 对话协议共用的 traceId 解析与本地补全规则。 */
public final class ChatTraceContext {

    private ChatTraceContext() {
    }

    /** 优先沿用真实链路 traceId；无上游链路时生成本轮唯一的 32 位本地 traceId。 */
    public static String resolveOrCreate(ContextView contextView) {
        Object traceId = contextView.getOrDefault(MdcContextLifter.TRACE_ID_KEY, null);
        if (traceId != null && !traceId.toString().isBlank()) {
            return traceId.toString();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static Context withTraceId(Context context, String traceId) {
        return context.hasKey(MdcContextLifter.TRACE_ID_KEY)
            ? context : context.put(MdcContextLifter.TRACE_ID_KEY, traceId);
    }
}
