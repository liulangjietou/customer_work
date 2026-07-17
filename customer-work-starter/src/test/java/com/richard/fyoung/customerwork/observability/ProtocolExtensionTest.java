package com.richard.fyoung.customerwork.observability;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Studio / TTS 扩展点单测：默认关闭（不连接外部服务、不构建音频模型）。
 * @author owlzhangfq@gmail.com
 */
class ProtocolExtensionTest {

    @Test
    void tts_shouldBeDisabled_byDefault() {
        TtsHookProvider provider = new TtsHookProvider(new CustomerWorkProperties());
        assertFalse(provider.isEnabled());
        assertTrue(provider.create().isEmpty(), "默认不应构建 TTS Hook");
    }

    @Test
    void studio_shouldBeDisabled_byDefault() {
        assertFalse(new StudioConfigurer(new CustomerWorkProperties()).isEnabled());
    }

    @Test
    void studio_shouldBeDisabled_whenEnabledButNoUrl() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getObservability().getStudio().setEnabled(true);
        assertFalse(new StudioConfigurer(props).isEnabled(), "未配置 url 视为未启用");
    }
}
