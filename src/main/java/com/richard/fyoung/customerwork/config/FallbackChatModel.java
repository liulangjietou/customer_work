package com.richard.fyoung.customerwork.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 私有化兜底模型（对应「模型层进阶 · 多模型 + fallback」）。
 *
 * <p>实现统一的 {@link Model} 抽象：优先走主模型，主模型调用出错时自动切换到兜底模型
 * （典型：国产/云端主模型 + 本地 Ollama 私有化兜底，满足金融/政务的高可用与数据主权要求）。
 * 对上层 Agent 完全透明。</p>
 * @author owlzhangfq@gmail.com
 */
public class FallbackChatModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    private final Model primary;
    private final Model fallback;

    public FallbackChatModel(Model primary, Model fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return primary.stream(messages, tools, options)
            .onErrorResume(error -> {
                log.warn("[Model] 主模型 {} 调用失败，切换兜底模型 {}：{}",
                    primary.getModelName(), fallback.getModelName(), error.getMessage());
                return fallback.stream(messages, tools, options);
            });
    }

    @Override
    public String getModelName() {
        return primary.getModelName() + "|fallback:" + fallback.getModelName();
    }
}
