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
        // 只在「首个分片到达之前」失败才切兜底。
        // 若主模型已经吐出过分片再失败，继续切兜底会把"主模型的前半段 + 兜底的完整新输出"拼在一起，
        // 用户看到重复错乱的文字。此时宁可让错误透传（上层做截断/兜底文案），也不做流中途拼接。
        return primary.stream(messages, tools, options)
            .switchOnFirst((signal, flux) -> {
                if (signal.isOnError()) {
                    Throwable error = signal.getThrowable();
                    log.warn("[Model] 主模型 {} 首个分片前失败，切换兜底模型 {}：{}",
                        primary.getModelName(), fallback.getModelName(),
                        error == null ? "?" : error.getMessage());
                    return fallback.stream(messages, tools, options);
                }
                // 首个分片已到达（或正常空完成）：主模型流接管，后续不再切兜底
                return flux;
            });
    }

    @Override
    public String getModelName() {
        return primary.getModelName() + "|fallback:" + fallback.getModelName();
    }
}
