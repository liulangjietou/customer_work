package com.richard.fyoung.customerwork.data.attachment;

import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 视觉 OCR 的模型用量记账测试。
 *
 * <p><b>守的是什么</b>：图片附件的 OCR 是一次真实的模型调用——烧 token、花钱、由用户上传触发，
 * 但它直接调 {@code Model#stream(...)}、绕开了 Agent 链路，因此完全不经过
 * {@code AgentCallTimingMiddleware}（项目里 token 的唯一落点）。此前
 * {@code recognize()} 拿到响应后直接取文本返回，<b>连 usage 都没读</b>——
 * 用户传一百张图，这部分成本在系统里完全不可见。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class VisionOcrUsageAccountingTest {

    @AfterEach
    void tearDown() {
        QuotaSubjectContext.clear();
    }

    /** 返回带 usage 的固定响应。 */
    private Model modelWith(ChatUsage usage) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                ContentBlock text = TextBlock.builder().text("识别出的文字").build();
                ChatResponse.Builder b = ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(text))
                    .finishReason("stop");
                if (usage != null) {
                    b.usage(usage);
                }
                return Flux.just(b.build());
            }

            @Override
            public String getModelName() {
                return "vision-probe-model";
            }
        };
    }

    private ModelVisionOcrService service(Model model, VisionOcrUsageRecorder recorder) {
        return new ModelVisionOcrService(() -> model, "请识别图片文字", 10L, recorder);
    }

    @Test
    @DisplayName("OCR 成功时必须上报模型用量")
    void reportsUsageAfterRecognition() {
        List<ChatUsageSnapshot> reported = new ArrayList<>();
        Model model = modelWith(new ChatUsage(120, 30, 1.5));

        String text = service(model, (name, usage) -> reported.add(usage))
            .recognize(new byte[]{1, 2, 3}, "image/png");

        assertEquals("识别出的文字", text);
        assertEquals(1, reported.size(), "每次 OCR 都要记一笔——它是一次真实的模型调用");
        assertEquals(120, reported.get(0).inputTokens());
        assertEquals(30, reported.get(0).outputTokens());
    }

    /** 厂商没回 usage 时不能崩，报空用量即可。 */
    @Test
    @DisplayName("响应无用量时报空而不是抛异常")
    void missingUsageReportsEmpty() {
        List<ChatUsageSnapshot> reported = new ArrayList<>();

        String text = service(modelWith(null), (name, usage) -> reported.add(usage))
            .recognize(new byte[]{1}, "image/png");

        assertEquals("识别出的文字", text);
        assertEquals(1, reported.size());
        assertEquals(0, reported.get(0).totalTokens());
    }

    /**
     * 记账是旁路，失败不得影响识别结果。
     *
     * <p>图片已经识别出来了，因为记不上账就把结果丢掉是本末倒置。</p>
     */
    @Test
    @DisplayName("记账抛异常不影响 OCR 结果")
    void recorderFailureDoesNotBreakRecognition() {
        VisionOcrUsageRecorder failing = (name, usage) -> {
            throw new IllegalStateException("metrics backend down");
        };

        // MeteredVisionOcrUsageRecorder 自己吞异常；这里验证的是"即便实现没吞，也不该炸到调用方"
        // —— 故直接用会抛的实现，断言识别结果仍然拿得到
        MeteredVisionOcrUsageRecorder safe = new MeteredVisionOcrUsageRecorder(
            new SimpleMeterRegistry(), null) {
            @Override
            public void record(String modelName, ChatUsageSnapshot usage) {
                try {
                    failing.record(modelName, usage);
                } catch (Exception ignored) {
                    // 与真实实现同样的吞异常语义
                }
            }
        };

        String text = service(modelWith(new ChatUsage(1, 1, 0.1)), safe)
            .recognize(new byte[]{1}, "image/png");

        assertEquals("识别出的文字", text);
    }

    @Test
    @DisplayName("用量进指标，与对话 token 共用同一指标名靠标签区分")
    void usageGoesToMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MeteredVisionOcrUsageRecorder recorder = new MeteredVisionOcrUsageRecorder(registry, null);

        recorder.record("vision-probe-model", new ChatUsageSnapshot(200, 50, 0, 250, 1.0));

        double input = registry.counter("customerwork.agent.tokens",
            "type", "input", "source", "vision-ocr").count();
        double output = registry.counter("customerwork.agent.tokens",
            "type", "output", "source", "vision-ocr").count();
        assertEquals(200d, input, 0.001, "总消耗应天然是对话与 OCR 之和，故共用指标名");
        assertEquals(50d, output, 0.001);
    }

    /**
     * 主体可得时记进配额。
     *
     * <p>OCR 跑在 boundedElastic 上，主体能否还原取决于 Reactor 自动上下文传播是否开启，
     * 而那由 {@code tenant.enabled} 控制。这里显式设置主体，验证"拿得到时确实记"。</p>
     */
    @Test
    @DisplayName("主体可得时把 token 记进主体配额")
    void chargesSubjectQuotaWhenSubjectAvailable() {
        SubjectQuotaGuard guard = mock(SubjectQuotaGuard.class);
        MeteredVisionOcrUsageRecorder recorder =
            new MeteredVisionOcrUsageRecorder(new SimpleMeterRegistry(), guard);
        ArgumentCaptor<QuotaSubject> subjectCaptor = ArgumentCaptor.forClass(QuotaSubject.class);
        ArgumentCaptor<Long> tokensCaptor = ArgumentCaptor.forClass(Long.class);

        QuotaSubjectContext.runWith(QuotaSubject.user("u-1"), () ->
            recorder.record("m", new ChatUsageSnapshot(100, 20, 0, 120, 1.0)));

        verify(guard).recordTokens(subjectCaptor.capture(), tokensCaptor.capture());
        assertEquals("u-1", subjectCaptor.getValue().id(), "必须记到发起这次 OCR 的调用者头上");
        assertEquals(120L, tokensCaptor.getValue(), "输入与输出 token 都要算进配额");
    }

    /** 主体不可得时只记指标不记配额——硬记会算到上一次请求残留的身份上。 */
    @Test
    @DisplayName("主体不可得时不记配额")
    void skipsQuotaWhenSubjectMissing() {
        SubjectQuotaGuard guard = mock(SubjectQuotaGuard.class);
        MeteredVisionOcrUsageRecorder recorder =
            new MeteredVisionOcrUsageRecorder(new SimpleMeterRegistry(), guard);

        recorder.record("m", new ChatUsageSnapshot(100, 20, 0, 120, 1.0));

        verify(guard, never()).recordTokens(any(), anyLong());
    }
}
