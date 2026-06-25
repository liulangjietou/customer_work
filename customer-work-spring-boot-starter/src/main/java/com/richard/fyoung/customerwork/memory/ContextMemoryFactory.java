package com.richard.fyoung.customerwork.memory;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 上下文压缩配置工厂（对应特性「智能上下文压缩」，AgentScope 2.0 迁移版）。
 *
 * <p>1.x 的 {@code AutoContextMemory} 在 2.0 已移除，等价能力由 Harness 的
 * {@link CompactionConfig} 提供：长对话超过消息 / token 阈值时自动压缩历史、卸载大工具结果、
 * 保留最近若干轮原文，保证上下文有界，缓解"长对话上下文爆炸、Token 成本失控"。</p>
 *
 * <p>压缩在 {@code HarnessAgent} 上经 {@code CompactionMiddleware} 生效（见
 * {@code CustomerServiceAgentFactory} 的 HarnessAgent 装配）。关闭压缩时返回 {@code null}，
 * 调用方据此跳过 {@code .compaction(...)} 配置。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ContextMemoryFactory {

    private static final Logger log = LoggerFactory.getLogger(ContextMemoryFactory.class);

    private final CustomerWorkProperties properties;
    private final Model model;

    public ContextMemoryFactory(CustomerWorkProperties properties, Model model) {
        this.properties = properties;
        this.model = model;
    }

    /**
     * 构建上下文压缩配置；未开启压缩时返回 {@code null}。
     */
    public CompactionConfig createCompaction() {
        CustomerWorkProperties.Context cfg = properties.getContext();
        if (!cfg.isCompressionEnabled()) {
            return null;
        }
        CompactionConfig compaction = CompactionConfig.builder()
            .triggerTokens((int) cfg.getMaxToken())
            .triggerMessages(cfg.getMsgThreshold())
            .keepMessages(cfg.getLastKeep())
            .model(model)
            .build();
        log.info("context compaction enabled: triggerTokens={} triggerMessages={} keepMessages={}",
            cfg.getMaxToken(), cfg.getMsgThreshold(), cfg.getLastKeep());
        return compaction;
    }
}
