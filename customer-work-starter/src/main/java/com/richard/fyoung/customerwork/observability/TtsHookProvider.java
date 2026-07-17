package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.hook.Hook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * TTS 语音合成 Hook 提供方（AgentScope 2.0 已下线该能力，保留为文档化空实现）。
 *
 * <p><b>不可迁移说明</b>：1.x 的 {@code io.agentscope.core.hook.TTSHook} 与
 * {@code io.agentscope.core.model.tts.DashScopeRealtimeTTSModel} 在 2.0 中已从核心移除
 * （核心不再内置 TTS，"Core no longer ships TTS"），需直接集成厂商实时语音 SDK。详见
 * {@code docs/MIGRATION-2.0.md} 的"不可迁移能力"一节。</p>
 *
 * <p>为保持装配与下游兼容，本类保留但 {@link #create()} 恒返回空：开启 TTS 配置时记录一条提示日志，
 * 引导改用外部 TTS SDK。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class TtsHookProvider {

    private static final Logger log = LoggerFactory.getLogger(TtsHookProvider.class);

    private final CustomerWorkProperties properties;

    public TtsHookProvider(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.getProtocol().getTts().isEnabled();
    }

    /**
     * 2.0 核心已移除 TTS Hook，恒返回空。开启配置时给出迁移提示。
     */
    public Optional<Hook> create() {
        if (isEnabled()) {
            log.info("[TTS] TTS hook removed in AgentScope 2.0 core; integrate a vendor realtime-TTS "
                + "SDK at the gateway/front-end instead. See docs/MIGRATION-2.0.md");
        }
        return Optional.empty();
    }
}
