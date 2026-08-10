package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转人工关键词检测单测：命中/未命中、空白输入、关键词未配置。
 * @author owlzhangfq@gmail.com
 */
class HandoffKeywordDetectorTest {

    private HandoffKeywordDetector detector(CustomerWorkProperties props) {
        return new HandoffKeywordDetector(props);
    }

    @Test
    void hit_shouldMatchDefaultKeywords() {
        HandoffKeywordDetector d = detector(new CustomerWorkProperties());
        assertTrue(d.hit("我要转人工"));
        assertTrue(d.hit("  请帮我找人工客服 "));
    }

    @Test
    void hit_shouldMissWhenNoKeywordPresent() {
        HandoffKeywordDetector d = detector(new CustomerWorkProperties());
        assertFalse(d.hit("这个耳机怎么连蓝牙"));
    }

    @Test
    void hit_shouldReturnFalseForBlankOrNull() {
        HandoffKeywordDetector d = detector(new CustomerWorkProperties());
        assertFalse(d.hit(null));
        assertFalse(d.hit("   "));
    }

    @Test
    void hit_shouldReturnFalseWhenKeywordsNotConfigured() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getTicket().setHandoffKeywords(List.of());
        assertFalse(detector(props).hit("我要转人工"));
    }
}
