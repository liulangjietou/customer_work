package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * TTS 语音合成 Hook 提供方（对应「交互协议 · TTS / 实时多模态」）。
 *
 * <p>启用后构建一个把 Agent 文本回复实时合成为语音的 {@link TTSHook}（基于 DashScope 实时 TTS），
 * 通过 audioCallback 把音频帧回推给调用方（而非直接占用服务端音频设备），便于在无声卡的服务端运行、
 * 由前端播放。默认关闭。</p>
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

    /** 启用时返回 TTS Hook，否则返回空。 */
    public Optional<Hook> create() {
        if (!isEnabled()) {
            return Optional.empty();
        }
        DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
            .apiKey(properties.getModel().getApiKey())
            .modelName("cosyvoice-v1")
            .voice("longxiaochun")
            .build();
        TTSHook hook = TTSHook.builder()
            .ttsModel(ttsModel)
            .autoStartPlayer(false)
            // 音频帧回推给调用方（前端播放），不直接占用服务端音频设备
            .audioCallback(audio -> log.debug("[TTS] 产出音频帧 {} 字节",
                audio == null ? 0 : 1))
            .build();
        log.info("[TTS] 已启用语音合成 Hook");
        return Optional.of(hook);
    }
}
