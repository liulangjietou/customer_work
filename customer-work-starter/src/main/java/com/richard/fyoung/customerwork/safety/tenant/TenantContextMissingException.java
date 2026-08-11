package com.richard.fyoung.customerwork.safety.tenant;

/**
 * 多租户已开启但当前执行链路没有租户上下文：拒绝执行，而不是放行成跨租户全量查询。
 *
 * <p>触发点通常是三类遗漏：接入层入口没写上下文、跨线程边界没传播、系统级任务没显式
 * {@code TenantContext.runWith(...)}。让它炸出来是刻意的——静默返回别的租户的数据，
 * 后果远重于一次 500。</p>
 * @author owlzhangfq@gmail.com
 */
public class TenantContextMissingException extends RuntimeException {

    public TenantContextMissingException() {
        super("tenant context is required but absent in current execution chain");
    }
}
