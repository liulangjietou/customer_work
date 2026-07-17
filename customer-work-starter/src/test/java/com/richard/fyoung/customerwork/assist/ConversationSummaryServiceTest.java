package com.richard.fyoung.customerwork.assist;

import com.richard.fyoung.customerwork.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.chatlog.ChatMessageStore;
import com.richard.fyoung.customerwork.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.ticket.TicketActorType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话总结服务单测：LLM 用 mock Model 隔离（真实 key 不需要）。覆盖 LLM 成功解析、模型不守格式降级、
 * 模型调用异常降级、空历史降级四条路径——全部 fail-open，永不抛异常。
 * @author owlzhangfq@gmail.com
 */
class ConversationSummaryServiceTest {

    private static final String SESSION = "conv-summary-1";

    private final AgentAssistService assistService = new AgentAssistService();
    private final CustomerWorkProperties properties = new CustomerWorkProperties();

    /** mock Model：一次性调用返回给定文本（模拟 LLM 输出）。 */
    private Model modelReturning(String text) {
        Model model = mock(Model.class);
        ChatResponse resp = mock(ChatResponse.class);
        List<ContentBlock> blocks = List.of(TextBlock.builder().text(text).build());
        when(resp.getContent()).thenReturn(blocks);
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(resp));
        return model;
    }

    private ChatMessageStore storeWith(String... userTexts) {
        InMemoryChatMessageStore store = new InMemoryChatMessageStore();
        int i = 0;
        for (String t : userTexts) {
            store.append(ChatMessage.of("MSG-" + i++, SESSION, null, TicketActorType.USER, "u", t));
        }
        return store;
    }

    @Test
    void summarize_shouldParseStructuredJson_whenModelReturnsValidJson() {
        String json = "{\"oneLineSummary\":\"用户要退款\",\"userIntent\":\"退款\",\"emotion\":\"不满\","
            + "\"triedSolutions\":[\"已引导自助退款\"],\"pendingIssues\":[\"退款未到账\"],"
            + "\"suggestedNextStep\":\"核实订单后手动退款\",\"suggestedReply\":\"您好，马上为您核实\"}";
        ConversationSummaryService service = new ConversationSummaryService(
            modelReturning(json), storeWith("我要退款", "怎么还没到账"), assistService, properties);

        ConversationSummary summary = service.summarize(SESSION);

        assertTrue(summary.fromModel());
        assertEquals("用户要退款", summary.oneLineSummary());
        assertEquals("不满", summary.emotion());
        assertEquals(List.of("退款未到账"), summary.pendingIssues());
        // 结果进缓存供坐席工作台拉取
        assertTrue(service.findLatest(SESSION).isPresent());
    }

    @Test
    void summarize_shouldDegrade_whenModelReturnsNonJson() {
        ConversationSummaryService service = new ConversationSummaryService(
            modelReturning("这是一段不守格式的自由文本"), storeWith("我要退款"), assistService, properties);

        ConversationSummary summary = service.summarize(SESSION);

        assertFalse(summary.fromModel());
        // 降级仍给出可用的规则版建议话术（不为空）
        assertTrue(summary.suggestedReply() != null && !summary.suggestedReply().isEmpty());
    }

    @Test
    void summarize_shouldDegrade_whenModelThrows() {
        Model model = mock(Model.class);
        when(model.stream(any(), any(), any())).thenReturn(Flux.error(new RuntimeException("model down")));
        ConversationSummaryService service = new ConversationSummaryService(
            model, storeWith("我要退款"), assistService, properties);

        ConversationSummary summary = service.summarize(SESSION);

        assertFalse(summary.fromModel());
        assertTrue(summary.suggestedReply() != null && !summary.suggestedReply().isEmpty());
    }

    @Test
    void summarize_shouldDegradeWithoutCallingModel_whenNoHistory() {
        // 空历史：不应调用模型，直接规则降级
        Model model = mock(Model.class);
        when(model.stream(any(), any(), any())).thenReturn(Flux.error(
            new AssertionError("model should not be called when history is empty")));
        ConversationSummaryService service = new ConversationSummaryService(
            model, new InMemoryChatMessageStore(), assistService, properties);

        ConversationSummary summary = service.summarize("empty-session");

        assertFalse(summary.fromModel());
        assertEquals("暂无会话历史", summary.oneLineSummary());
    }
}
