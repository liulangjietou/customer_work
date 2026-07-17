package com.richard.fyoung.customerwork.routing;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工单分类器单测：mock Model 隔离 LLM。覆盖成功解析、非法 JSON 降级、模型异常降级、requiredSkill=null 归一。
 * @author owlzhangfq@gmail.com
 */
class TicketClassifierTest {

    private final CustomerWorkProperties properties = new CustomerWorkProperties();

    private Model modelReturning(String text) {
        Model model = mock(Model.class);
        ChatResponse resp = mock(ChatResponse.class);
        List<ContentBlock> blocks = List.of(TextBlock.builder().text(text).build());
        when(resp.getContent()).thenReturn(blocks);
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(resp));
        return model;
    }

    @Test
    void classify_shouldParseValidJson() {
        String json = "{\"category\":\"退款\",\"requiredSkill\":\"refund\",\"priority\":\"HIGH\",\"emotion\":\"愤怒\"}";
        TicketClassifier classifier = new TicketClassifier(modelReturning(json), properties);

        TicketClassification c = classifier.classify("用户投诉退款未到账", null);

        assertEquals("退款", c.category());
        assertEquals("refund", c.requiredSkill());
        assertEquals(TicketPriority.HIGH, c.priority());
        assertEquals("愤怒", c.emotion());
    }

    @Test
    void classify_shouldNormalizeNullSkill() {
        String json = "{\"category\":\"咨询\",\"requiredSkill\":\"null\",\"priority\":\"LOW\",\"emotion\":\"平静\"}";
        TicketClassifier classifier = new TicketClassifier(modelReturning(json), properties);

        assertNull(classifier.classify("随便问问", null).requiredSkill());
    }

    @Test
    void classify_shouldFallback_whenModelReturnsNonJson() {
        TicketClassifier classifier = new TicketClassifier(modelReturning("我不会输出 JSON"), properties);

        TicketClassification c = classifier.classify("投诉", null);

        assertEquals(TicketClassification.DEFAULT_CATEGORY, c.category());
        assertEquals(TicketPriority.MEDIUM, c.priority());
        assertNull(c.requiredSkill());
    }

    @Test
    void classify_shouldFallback_whenModelThrows() {
        Model model = mock(Model.class);
        when(model.stream(any(), any(), any())).thenReturn(Flux.error(new RuntimeException("down")));
        TicketClassifier classifier = new TicketClassifier(model, properties);

        TicketClassification c = classifier.classify("投诉", null);

        assertEquals(TicketClassification.DEFAULT_CATEGORY, c.category());
        assertEquals(TicketPriority.MEDIUM, c.priority());
    }
}
