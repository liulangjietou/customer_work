package com.richard.fyoung.customerwork.capability.typesafe;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * System One 传输层 SPI。调用方只依赖此接口，单测注入桩即可完全离线。
 *
 * <p><b>契约：永不抛错、永不阻塞调用线程</b>。任何失败（网络、超时、4xx/5xx、响应非法、熔断打开）
 * 一律表现为 {@link Mono#empty()}，并由实现方负责记日志与指标。Jev 是叠加在既有逻辑之上的决策层，
 * 它不可用时调用方必须原样走旧路径——把失败做成空而不是异常，调用方就不可能忘记处理。</p>
 */
public interface SystemOneClient {

    /**
     * 批量提问。
     *
     * @param state     被判定的内容（用户消息、回复正文等）
     * @param questions 问题编号 → 问题
     * @param timeout   本次调用的超时上限
     * @return 解析结果；任何失败都返回 empty
     */
    Mono<SystemOneResult> ask(String state, Map<String, SystemOneQuestion> questions, Duration timeout);
}
