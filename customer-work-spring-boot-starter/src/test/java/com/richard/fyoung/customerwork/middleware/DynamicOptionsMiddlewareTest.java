package com.richard.fyoung.customerwork.middleware;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态生成参数中间件单测（onModelCall 按意图自适应）：关键词命中与精确档参数合并。
 * @author owlzhangfq@gmail.com
 */
class DynamicOptionsMiddlewareTest {

    private DynamicOptionsMiddleware enabledMiddleware() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        CustomerWorkProperties.Hooks.DynamicOptions cfg = props.getHooks().getDynamicOptions();
        cfg.setEnabled(true);
        cfg.setPreciseTemperature(0.1);
        cfg.setPreciseReasoningEffort("high");
        return new DynamicOptionsMiddleware(props);
    }

    @Test
    void hitsPreciseKeyword_shouldMatchHighRiskWords() {
        DynamicOptionsMiddleware mw = enabledMiddleware();
        assertTrue(mw.hitsPreciseKeyword("我要投诉并退款"));
        assertFalse(mw.hitsPreciseKeyword("你好，今天天气不错"));
    }

    @Test
    void mergePrecise_shouldOverrideTemperatureAndEffort() {
        DynamicOptionsMiddleware mw = enabledMiddleware();
        GenerateOptions effective = GenerateOptions.builder().temperature(0.8).maxTokens(1500).build();

        GenerateOptions merged = mw.mergePrecise(effective);

        assertEquals(0.1, merged.getTemperature(), 1e-9, "温度应被精确档覆盖");
        assertEquals(1500, merged.getMaxTokens(), "未覆盖项应沿用原值");
    }
}
