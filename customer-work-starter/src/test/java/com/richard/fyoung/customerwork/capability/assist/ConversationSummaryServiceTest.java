package com.richard.fyoung.customerwork.capability.assist;

import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessageStore;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void readCurrentMustUseRulesWithoutCallingModelWhenNoSummaryExists() {
        Model model = mock(Model.class);
        var service = new ConversationSummaryService(model, storeWith("退款进度到哪里了"),
            assistService, properties);
        var current = service.readCurrent(SESSION);
        assertFalse(current.fromModel());
        assertTrue(current.userIntent().contains("退款进度"));
        assertEquals("退款进度到哪里了", current.evidence().sources().get(0).excerpt());
        verifyNoInteractions(model);
    }

    @Test
    void readCurrentMustKeepValidSummaryButUseNewEvidenceAfterMessagesChange() {
        var model = modelReturning("{\"oneLineSummary\":\"客户咨询退款\",\"userIntent\":\"退款\","
            + "\"emotion\":\"焦虑\",\"triedSolutions\":[],\"pendingIssues\":[\"核对进度\"],"
            + "\"suggestedNextStep\":\"查证订单\",\"suggestedReply\":\"我会先核对订单记录\"}");
        var store = storeWith("查询退款进度");
        var service = new ConversationSummaryService(model, store, assistService, properties);
        var generated = service.summarize(SESSION);
        assertTrue(generated.fromModel());
        assertEquals(generated, service.readCurrent(SESSION));
        store.append(ChatMessage.of("new-invoice", SESSION, null, TicketActorType.USER,
            "u", "还有发票没有收到"));
        var current = service.readCurrent(SESSION);
        assertFalse(current.fromModel());
        assertTrue(current.userIntent().contains("发票"));
        assertNotEquals(generated.evidence().version(), current.evidence().version());
        verify(model, times(1)).stream(any(), any(), any());
    }

    @Test
    void readCurrentMustPropagateHistoryFailuresAndRequireTenantWhenEnabled() {
        Model model = mock(Model.class);
        var store = mock(ChatMessageStore.class);
        when(store.findBySession(SESSION, null, 30)).thenThrow(new IllegalStateException("history down"));
        var service = new ConversationSummaryService(model, store, assistService, properties);
        assertThrows(IllegalStateException.class, () -> service.readCurrent(SESSION));
        properties.getTenant().setEnabled(true);
        TenantContext.clear();
        assertThrows(com.richard.fyoung.customerwork.safety.tenant.TenantContextMissingException.class,
            () -> service.readCurrent(SESSION));
        verifyNoInteractions(model);
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

    @Test
    void findLatest_shouldIsolateTenantsWithTheSameSessionId() {
        ConversationSummaryService service = new ConversationSummaryService(
            modelReturning("{\"oneLineSummary\":\"租户 A 的私人诉求\"}"), storeWith("我要退款"),
            assistService, properties);
        TenantContext.runWith("tenant-a", () -> service.summarize(SESSION));

        assertTrue(TenantContext.callWith("tenant-b", () -> service.findLatest(SESSION)).isEmpty());
        assertTrue(TenantContext.callWith("tenant-a", () -> service.findLatest(SESSION)).isPresent());
    }

    @Test
    void findLatest_shouldInvalidateWhenHistoryChanges() {
        ChatMessageStore store = storeWith("我要退款");
        ConversationSummaryService service = new ConversationSummaryService(
            modelReturning("{\"oneLineSummary\":\"用户申请退款\"}"), store, assistService, properties);
        service.summarize(SESSION);
        store.append(ChatMessage.of("MSG-new", SESSION, null, TicketActorType.USER, "u", "已经到账了"));

        assertTrue(service.findLatest(SESSION).isEmpty(), "新消息出现后旧摘要不能继续充当当前摘要");
    }

    @Test
    void summarize_shouldNotPresentUnparsedModelTextAsRuleEvidence() {
        ConversationSummaryService service = new ConversationSummaryService(
            modelReturning("退款已经完成，款项已到账"), storeWith("我要退款"), assistService, properties);

        ConversationSummary summary = service.summarize(SESSION);

        assertFalse(summary.fromModel());
        assertEquals("用户最新诉求：我要退款", summary.oneLineSummary());
    }

    @Test
    void summarize_shouldExposeHistoryReadFailureWithoutCallingModel() {
        ChatMessageStore store = mock(ChatMessageStore.class);
        when(store.findBySession(SESSION, null, 30)).thenThrow(new IllegalStateException("storage unavailable"));
        Model model = mock(Model.class);
        ConversationSummaryService service = new ConversationSummaryService(model, store, assistService, properties);

        assertThrows(IllegalStateException.class, () -> service.summarize(SESSION));
        verifyNoInteractions(model);
    }

    @Test
    void summarize_shouldIncludeNewestUserMessageWithinTranscriptBudget() {
        Model model = modelReturning("{\"oneLineSummary\":\"取消退款\"}");
        doAnswer(invocation -> {
            List<Msg> messages = invocation.getArgument(0);
            String prompt = messages.get(0).getTextContent();
            assertTrue(prompt.contains("已经到账，请取消退款"), "截断必须保留最新诉求");
            return Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text("{\"oneLineSummary\":\"取消退款\"}").build()))
                .build());
        }).when(model).stream(any(), any(), any());
        ConversationSummaryService service = new ConversationSummaryService(model,
            storeWith("历史内容".repeat(4000), "已经到账，请取消退款"), assistService, properties);

        ConversationSummary summary = service.summarize(SESSION);
        assertTrue(summary.evidence().truncated());
        assertEquals("已经到账，请取消退款", summary.evidence().sources().get(1).excerpt());
        int transcriptLength = summary.evidence().sources().stream()
            .mapToInt(source -> source.excerpt().length() + "用户：".length()).sum() + 1;
        assertTrue(transcriptLength <= 12_000);
    }

    @Test
    void summarize_shouldBindServerEvidenceAndDetectContentChangesAtTheSameId() {
        ChatMessage original = new ChatMessage(42, "MSG-42", SESSION, "TK-1", TicketActorType.USER,
            "u", "我要退款", 123L);
        ChatMessage revised = new ChatMessage(42, "MSG-42", SESSION, "TK-1", TicketActorType.USER,
            "u", "已到账，无需退款", 123L);
        ChatMessageStore store = mock(ChatMessageStore.class);
        when(store.findBySession(SESSION, null, 30)).thenReturn(List.of(original));
        Model model = modelReturning("{\"oneLineSummary\":\"退款咨询\",\"evidence\":{\"version\":\"forged\"}}");
        ConversationSummaryService service = new ConversationSummaryService(model, store, assistService, properties);
        long before = System.currentTimeMillis();
        ConversationSummary summary = service.summarize(SESSION);

        assertNotEquals("forged", summary.evidence().version());
        assertEquals(List.of(new SummaryEvidence.Source(42, "MSG-42", TicketActorType.USER,
            "我要退款", 123L, false)), summary.evidence().sources());
        assertTrue(summary.evidence().generatedAtMs() >= before);
        assertTrue(summary.evidence().generatedAtMs() <= System.currentTimeMillis());
        assertTrue(service.findLatest(SESSION).isPresent());
        when(store.findBySession(SESSION, null, 30)).thenReturn(List.of(revised));
        assertTrue(service.findLatest(SESSION).isEmpty());
        verify(model, times(1)).stream(any(), any(), any());
    }

    @Test
    void findLatest_shouldRejectUnavailableStorageAndMissingRequiredTenant() {
        ChatMessageStore store = mock(ChatMessageStore.class);
        when(store.findBySession(SESSION, null, 30)).thenReturn(List.of());
        ConversationSummaryService service = new ConversationSummaryService(mock(Model.class), store,
            assistService, properties);
        service.summarize(SESSION);
        when(store.findBySession(SESSION, null, 30)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class, () -> service.findLatest(SESSION));
        properties.getTenant().setEnabled(true);
        assertThrows(com.richard.fyoung.customerwork.safety.tenant.TenantContextMissingException.class,
            () -> service.findLatest(SESSION));
    }

    @Test
    void summarize_shouldKeepUnicodeValidWhenShorteningFallbackText() {
        ConversationSummaryService service = new ConversationSummaryService(modelReturning("invalid"),
            storeWith("字".repeat(119) + "😀尾部"), assistService, properties);

        ConversationSummary summary = service.summarize(SESSION);

        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(summary.oneLineSummary()));
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(summary.userIntent()));
    }
}
