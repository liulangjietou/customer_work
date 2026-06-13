package com.richard.fyoung.customerwork.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nacos 配置中心提示词服务单测：初始拉取、热更新监听、缺省回退（离线，mock ConfigService）。
 * @author owlzhangfq@gmail.com
 */
class NacosPromptServiceTest {

    private CustomerWorkProperties props() {
        CustomerWorkProperties p = new CustomerWorkProperties();
        p.getNacos().setPromptDataId("customer-work-system-prompt");
        p.getNacos().setGroup("DEFAULT_GROUP");
        return p;
    }

    @Test
    void currentPrompt_shouldBeEmpty_byDefault() {
        assertTrue(new NacosPromptService(props()).currentPrompt().isEmpty(),
            "未绑定 Nacos 时应为空，由调用方回退内置提示词");
    }

    @Test
    void bind_shouldLoadInitialAndHotUpdate() throws Exception {
        NacosPromptService service = new NacosPromptService(props());
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(eq("customer-work-system-prompt"), eq("DEFAULT_GROUP"), anyLong()))
            .thenReturn("你是 Nacos 下发的客服提示词 v1");

        service.bind(configService);

        // 初始拉取生效
        assertEquals("你是 Nacos 下发的客服提示词 v1", service.currentPrompt().orElseThrow());

        // 捕获监听器并模拟 Nacos 推送新配置 -> 热更新
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        verify(configService).addListener(eq("customer-work-system-prompt"), eq("DEFAULT_GROUP"), captor.capture());
        captor.getValue().receiveConfigInfo("你是 Nacos 下发的客服提示词 v2（已热更新）");

        assertEquals("你是 Nacos 下发的客服提示词 v2（已热更新）", service.currentPrompt().orElseThrow());
    }

    @Test
    void bind_shouldIgnoreBlankInitialConfig() throws Exception {
        NacosPromptService service = new NacosPromptService(props());
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(any(), any(), anyLong())).thenReturn("   ");

        service.bind(configService);

        assertTrue(service.currentPrompt().isEmpty(), "空白配置不应生效");
    }
}
