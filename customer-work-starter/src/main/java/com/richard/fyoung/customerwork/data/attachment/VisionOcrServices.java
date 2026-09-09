package com.richard.fyoung.customerwork.data.attachment;

import org.springframework.util.StringUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * {@link VisionOcrService} 静态工厂：按 {@code customer-work.attachment.ocr.engine} 选择 OCR 引擎实现。
 *
 * <p>选型逻辑只写这一份，starter 的 {@link AttachmentConfig} 与 admin 的 {@code AdminAttachmentConfig} 都调它，
 * 避免两处装配各自判 engine 而漂移。两个引擎：<br>
 * - {@code model}（默认）：视觉大模型 OCR（{@link ModelVisionOcrService}），视觉模型惰性构建，
 *   模型如何构建由调用方传入的 {@code visionModelSupplier} 决定（starter 与 admin 的 Key 回落策略不同）。<br>
 * - {@code paddleocr}：自建 PaddleOCR 开源 serving（{@link PaddleOcrVisionOcrService}），走 HTTP，不需模型 Key。</p>
 * @author owlzhangfq@gmail.com
 */
public final class VisionOcrServices {

    private static final Logger log = LoggerFactory.getLogger(VisionOcrServices.class);

    /** 引擎标识：自建 PaddleOCR 开源 serving。 */
    private static final String ENGINE_PADDLEOCR = "paddleocr";

    private VisionOcrServices() {
    }

    /**
     * 按配置的 engine 创建视觉 OCR 服务。
     *
     * @param properties          附件配置（取 {@code ocr.engine} 与对应引擎的子配置）
     * @param visionModelSupplier engine=model 时惰性构建视觉模型的工厂（缺 Key 不影响启动，首次识别才构建）
     * @return 选定引擎的 {@link VisionOcrService} 实现
     */
    /** 兼容既有调用：不记账。 */
    public static VisionOcrService create(AttachmentProperties properties, Supplier<Model> visionModelSupplier) {
        return create(properties, visionModelSupplier, VisionOcrUsageRecorder.noop());
    }

    public static VisionOcrService create(AttachmentProperties properties, Supplier<Model> visionModelSupplier,
                                          VisionOcrUsageRecorder usageRecorder) {
        return create(properties, visionModelSupplier, usageRecorder, null);
    }

    /**
     * 构造 OCR 服务；配了 {@code fallback-engine} 时包成主备双引擎。
     *
     * <p>备用引擎与主引擎相同时视为未配置——那不是降级，只是把同一个失败重做一遍。</p>
     */
    public static VisionOcrService create(AttachmentProperties properties, Supplier<Model> visionModelSupplier,
                                          VisionOcrUsageRecorder usageRecorder,
                                          MeterRegistry meterRegistry) {
        AttachmentProperties.Ocr ocr = properties.getOcr();
        String primaryEngine = normalize(ocr.getEngine());
        VisionOcrService primary = build(primaryEngine, properties, visionModelSupplier, usageRecorder);

        String fallbackEngine = normalize(ocr.getFallbackEngine());
        if (!StringUtils.hasText(fallbackEngine) || fallbackEngine.equals(primaryEngine)) {
            return primary;
        }
        VisionOcrService fallback = build(fallbackEngine, properties, visionModelSupplier, usageRecorder);
        log.info("vision ocr fallback enabled, primary={} fallback={}", primaryEngine, fallbackEngine);
        return new FallbackVisionOcrService(primary, fallback, primaryEngine, fallbackEngine, meterRegistry);
    }

    private static VisionOcrService build(String engine, AttachmentProperties properties,
                                          Supplier<Model> visionModelSupplier,
                                          VisionOcrUsageRecorder usageRecorder) {
        AttachmentProperties.Ocr ocr = properties.getOcr();
        if (ENGINE_PADDLEOCR.equals(engine)) {
            AttachmentProperties.Paddle paddle = ocr.getPaddle();
            log.info("vision ocr engine: paddleocr (self-hosted serving, base-url={})", paddle.getBaseUrl());
            return new PaddleOcrVisionOcrService(paddle.getBaseUrl(), paddle.getOcrPath(), paddle.getTimeoutSeconds());
        }
        log.info("vision ocr engine: model (vision LLM, provider={}, model={})", ocr.getProvider(), ocr.getModelName());
        return new ModelVisionOcrService(visionModelSupplier, ocr.getPrompt(), ocr.getTimeoutSeconds(),
            usageRecorder);
    }

    private static String normalize(String engine) {
        return engine == null ? "" : engine.trim().toLowerCase();
    }
}
