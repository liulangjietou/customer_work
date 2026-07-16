package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 转人工关键词命中检测：用户消息 trim 后包含任一配置关键词即命中。
 *
 * <p>关键词取自 {@code customer-work.ticket.handoff-keywords}；未配置（空集合）则一律不命中。
 * 这是"用户主动喊转人工"的快车道——命中即建单转人工，不进 LLM，见 {@code ChatDispatchService}。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class HandoffKeywordDetector {

    private final CustomerWorkProperties properties;

    public HandoffKeywordDetector(CustomerWorkProperties properties) {
        this.properties = properties;
    }

    /** 消息是否命中任一转人工关键词（消息为空白或关键词未配置均返回 false）。 */
    public boolean hit(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        List<String> keywords = properties.getTicket().getHandoffKeywords();
        if (CollectionUtils.isEmpty(keywords)) {
            return false;
        }
        String text = message.trim();
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
