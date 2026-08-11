package com.richard.fyoung.customerwork.safety.sensitiveword;

/**
 * 敏感词命中投递出口：中间件只管"命中了，交给你"，不关心落在哪、是否异步。
 *
 * <p>把投递与存储分成两个接口，是为了让中间件对"记录动作本身"零耦合——关闭命中日志时容器里
 * 干脆没有本 Bean，中间件按 null 跳过，不需要一个空实现类来占位。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SensitiveWordHitSink {

    /** 投递一条命中记录；实现必须立即返回，绝不阻塞对话链路。 */
    void emit(SensitiveWordHitRecord record);
}
