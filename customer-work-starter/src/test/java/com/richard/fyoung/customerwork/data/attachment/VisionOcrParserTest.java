package com.richard.fyoung.customerwork.data.attachment;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视觉 OCR 单测：假 {@link Model} 返回固定文本，验证 {@link ModelVisionOcrService} 组消息并收集文本，
 * 以及视觉模型惰性构建（首次识别才构建、之后缓存复用）。全程离线，不触真实模型调用。
 * @author owlzhangfq@gmail.com
 */
class VisionOcrParserTest {

    /** 假视觉模型：把每次 stream 调用固定回一段 OCR 文本。 */
    private static Model fakeVisionModel(String recognizedText) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ChatResponse resp = ChatResponse.builder()
                    .content(List.<ContentBlock>of(TextBlock.builder().text(recognizedText).build()))
                    .build();
                return Flux.just(resp);
            }

            @Override
            public String getModelName() {
                return "fake-vision";
            }
        };
    }

    @Test
    void recognize_shouldCollectModelText() {
        ModelVisionOcrService service = new ModelVisionOcrService(
            () -> fakeVisionModel("图片中的文字"), "prompt", 5);
        String text = service.recognize(new byte[]{1, 2, 3}, "image/png");
        assertEquals("图片中的文字", text);
    }

    @Test
    void recognize_shouldBuildModelLazilyAndCache() {
        AtomicInteger buildCount = new AtomicInteger(0);
        ModelVisionOcrService service = new ModelVisionOcrService(() -> {
            buildCount.incrementAndGet();
            return fakeVisionModel("ocr");
        }, "prompt", 5);
        // 构造时不应触发模型构建（惰性）
        assertEquals(0, buildCount.get(), "构造不应立即构建视觉模型");

        service.recognize(new byte[]{1}, "image/png");
        service.recognize(new byte[]{2}, "image/png");
        assertEquals(1, buildCount.get(), "模型应只构建一次并缓存复用");
    }

    @Test
    void visionOcrParser_shouldDelegateToService() throws Exception {
        VisionOcrService service = (bytes, mime) -> "识别结果";
        VisionOcrParser parser = new VisionOcrParser(service);
        assertTrue(parser.supports("png", "image/png"));
        assertTrue(parser.supports("webp", "image/webp"));
        assertEquals("识别结果", parser.parse(new byte[]{1}, "a.png", "png", "image/png").text());
    }
}
