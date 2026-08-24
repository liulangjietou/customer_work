package com.richard.fyoung.customerwork.core.service;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** 单轮对话终止采集器的 Reactor Context 访问入口。 */
public final class ChatTerminalCaptureContext {

    private static final Class<ChatTerminalCapture> KEY = ChatTerminalCapture.class;

    private ChatTerminalCaptureContext() {
    }

    public static Context withCapture(Context context, ChatTerminalCapture capture) {
        return context.put(KEY, capture);
    }

    public static ChatTerminalCapture get(ContextView contextView) {
        return contextView.getOrDefault(KEY, null);
    }
}
