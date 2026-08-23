package com.richard.fyoung.customerwork.core.model.routing;

import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * 单次模型调用的路由指令。
 *
 * <p>指令放在 Reactor Context，而不是 ThreadLocal：模型调用会跨异步线程，ThreadLocal 无法可靠传播；
 * 同时指令只对当前订阅生效，不会污染其它租户或并发请求。配额 {@code DEGRADE} 使用
 * {@link #preferFallback(Context)} 强制走备用候选，模型链中的分级路由和故障转移装饰器共同识别。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class ModelRoutingContext {

    private static final String ROUTE_KEY = ModelRoutingContext.class.getName() + ".route";
    private static final String ROUTE_HINT_KEY = ModelRoutingContext.class.getName() + ".hint";
    private static final String FALLBACK_ROUTE = "FALLBACK";

    private ModelRoutingContext() {
    }

    /** 在当前订阅上下文中声明“只走备用模型”。 */
    public static Context preferFallback(Context context) {
        return context.put(ROUTE_KEY, FALLBACK_ROUTE);
    }

    /** 当前订阅是否要求强制备用模型。 */
    public static boolean isFallbackPreferred(ContextView context) {
        return FALLBACK_ROUTE.equals(context.getOrDefault(ROUTE_KEY, null));
    }

    /** 写入本次调用的权威路由事实；与 fallback 指令使用不同 key，可同时存在。 */
    public static Context withHint(Context context, ModelRouteHint hint) {
        return hint == null ? context : context.put(ROUTE_HINT_KEY, hint);
    }

    /** 未提供显式事实时返回空 Hint，交由路由模型从调用参数补全。 */
    public static ModelRouteHint routeHint(ContextView context) {
        return context.getOrDefault(ROUTE_HINT_KEY, ModelRouteHint.empty());
    }
}
