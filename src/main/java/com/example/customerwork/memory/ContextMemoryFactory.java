package com.example.customerwork.memory;

import com.example.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 短期记忆工厂（对应特性「智能上下文压缩」）。
 *
 * <p>根据配置返回会话级短期记忆实现：</p>
 * <ul>
 *   <li>关闭压缩：普通 {@link InMemoryMemory}；</li>
 *   <li>开启压缩：{@link AutoContextMemory}——长对话超过 token / 消息阈值时自动压缩历史、
 *       卸载大工具结果、保留最近若干轮原文，保证上下文始终有界，
 *       直接缓解"长对话上下文爆炸、Token 成本失控"。</li>
 * </ul>
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

    /** 为一个会话创建短期记忆实例。 */
    public Memory create() {
        CustomerWorkProperties.Context cfg = properties.getContext();
        if (!cfg.isCompressionEnabled()) {
            return new InMemoryMemory();
        }
        AutoContextConfig autoConfig = AutoContextConfig.builder()
            .maxToken(cfg.getMaxToken())
            .msgThreshold(cfg.getMsgThreshold())
            .lastKeep(cfg.getLastKeep())
            .build();
        log.debug("启用智能上下文压缩 maxToken={} msgThreshold={} lastKeep={}",
            cfg.getMaxToken(), cfg.getMsgThreshold(), cfg.getLastKeep());
        return new AutoContextMemory(autoConfig, model);
    }
}
