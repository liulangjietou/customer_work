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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link VisionOcrServices} 引擎切换测试：验证按 {@code ocr.engine} 选出正确的实现类型，
 * 且 engine=paddleocr 时不触碰视觉模型 supplier（惰性、不构建）。
 * @author owlzhangfq@gmail.com
 */
class VisionOcrServicesTest {

    private static Model fakeModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(ChatResponse.builder()
                    .content(List.<ContentBlock>of(TextBlock.builder().text("x").build())).build());
            }

            @Override
            public String getModelName() {
                return "fake";
            }
        };
    }

    @Test
    void create_shouldReturnModelServiceByDefault() {
        AttachmentProperties props = new AttachmentProperties();
        // 默认 engine=model
        VisionOcrService service = VisionOcrServices.create(props, VisionOcrServicesTest::fakeModel);
        assertInstanceOf(ModelVisionOcrService.class, service);
    }

    @Test
    void create_shouldReturnPaddleServiceWhenEngineIsPaddleocr() {
        AttachmentProperties props = new AttachmentProperties();
        props.getOcr().setEngine("paddleocr");
        props.getOcr().getPaddle().setBaseUrl("http://localhost:8868");

        AtomicBoolean supplierTouched = new AtomicBoolean(false);
        VisionOcrService service = VisionOcrServices.create(props, () -> {
            supplierTouched.set(true);
            return fakeModel();
        });

        assertInstanceOf(PaddleOcrVisionOcrService.class, service);
        assertFalse(supplierTouched.get(), "paddleocr 引擎不应触碰视觉模型 supplier");
    }
}
