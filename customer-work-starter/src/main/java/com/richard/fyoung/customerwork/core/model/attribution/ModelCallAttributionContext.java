package com.richard.fyoung.customerwork.core.model.attribution;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.function.Consumer;

/** 单次模型调用的 Reactor 侧信道；每个 onModelCall 独占一个 sink，不共享可变全局状态。 */
public final class ModelCallAttributionContext {

    private static final Object ATTRIBUTION_SINK_KEY = new Object();

    private ModelCallAttributionContext() {
    }

    public static Context withSink(Context context, Consumer<ModelCallAttribution> sink) {
        return context.put(ATTRIBUTION_SINK_KEY, sink);
    }

    @SuppressWarnings("unchecked")
    public static void publish(ContextView context, ModelCallAttribution attribution) {
        Object sink = context.getOrDefault(ATTRIBUTION_SINK_KEY, null);
        if (sink instanceof Consumer<?> consumer) {
            ((Consumer<ModelCallAttribution>) consumer).accept(attribution);
        }
    }
}
