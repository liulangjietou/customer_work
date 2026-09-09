package com.richard.fyoung.customerwork.data.attachment;

import com.richard.fyoung.customerwork.core.constant.MetricTags;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主备双引擎的视觉 OCR：主引擎失败时退到备用引擎。
 *
 * <p><b>要解决的问题</b>：项目本来就有两个 OCR 引擎——视觉大模型与自建 PaddleOCR serving，
 * 但此前是二选一：{@code engine=model} 时视觉模型 API 抖动、限流或欠费，附件解析直接落 FAILED，
 * 用户看到的是"解析失败"，<b>而旁边那个能用的引擎闲着</b>。
 * 项目在对话模型那侧做了失败转移、熔断、分级路由一整套，附件这条链路一样都没有。</p>
 *
 * <p><b>为什么值得降级而不是直接失败</b>：客服场景里"有结果"明显优于"没结果"。
 * 自建 PaddleOCR 的识别质量不如视觉大模型，但它成本为零、可用性独立于外部模型 API——
 * 用户传的截图能读出七成文字，也比一句"解析失败"有用。</p>
 *
 * <p><b>默认不降级，配了才降</b>：不能假设备用引擎在这个部署里存在（PaddleOCR 需要自建 serving）。
 * 留空即单引擎，与既有行为完全一致；显式配置 {@code fallback-engine} 才启用。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public class FallbackVisionOcrService implements VisionOcrService {

    private static final Logger log = LoggerFactory.getLogger(FallbackVisionOcrService.class);

    /** 降级计数指标：回答"主引擎到底有多不稳"，以及降级本身有没有救回来。 */
    private static final String M_FALLBACK = "customerwork.attachment.ocr.fallback";
    private static final String TAG_FROM = "from";
    private static final String TAG_TO = "to";
    private static final String RESULT_RECOVERED = "recovered";
    private static final String RESULT_BOTH_FAILED = "both-failed";

    private final VisionOcrService primary;
    private final VisionOcrService fallback;
    private final String primaryEngine;
    private final String fallbackEngine;
    private final MeterRegistry meterRegistry;

    public FallbackVisionOcrService(VisionOcrService primary, VisionOcrService fallback,
                                    String primaryEngine, String fallbackEngine,
                                    MeterRegistry meterRegistry) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryEngine = primaryEngine;
        this.fallbackEngine = fallbackEngine;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String recognize(byte[] imageBytes, String mimeType) {
        try {
            return primary.recognize(imageBytes, mimeType);
        } catch (Exception primaryError) {
            // 降级本身必须留痕：它意味着主引擎不可用，而用户侧看起来一切正常
            log.error("vision ocr primary engine failed, falling back, code={} from={} to={}",
                "VISION-OCR-FALLBACK", primaryEngine, fallbackEngine, primaryError);
            return recognizeWithFallback(imageBytes, mimeType, primaryError);
        }
    }

    private String recognizeWithFallback(byte[] imageBytes, String mimeType, Exception primaryError) {
        try {
            String text = fallback.recognize(imageBytes, mimeType);
            count(RESULT_RECOVERED);
            log.info("vision ocr recovered by fallback engine, engine={}", fallbackEngine);
            return text;
        } catch (Exception fallbackError) {
            count(RESULT_BOTH_FAILED);
            // 抛主引擎的异常并把备用的挂成 suppressed：主引擎才是这个部署期望用的那个，
            // 它的失败原因更有诊断价值；两个都保留，排查时不必再翻日志对时间
            primaryError.addSuppressed(fallbackError);
            throw primaryError instanceof RuntimeException runtime
                ? runtime : new IllegalStateException("vision ocr failed on both engines", primaryError);
        }
    }

    private void count(String result) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(M_FALLBACK, TAG_FROM, primaryEngine, TAG_TO, fallbackEngine,
            MetricTags.RESULT, result).increment();
    }
}
