package com.richard.fyoung.customerwork.data.attachment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视觉 OCR 主备双引擎测试。
 *
 * <p><b>守的是什么</b>：项目本来就有两个 OCR 引擎（视觉大模型 / 自建 PaddleOCR），
 * 但此前是二选一——视觉模型 API 抖动、限流或欠费时，附件解析直接落 FAILED，
 * 用户看到"解析失败"，而旁边那个能用的引擎闲着。项目在对话模型那侧做了失败转移、熔断、
 * 分级路由一整套，附件这条链路一样都没有。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class FallbackVisionOcrServiceTest {

    private static final byte[] IMAGE = {1, 2, 3};
    private static final String MIME = "image/png";

    private VisionOcrService ok(String text, AtomicInteger calls) {
        return (bytes, mime) -> {
            calls.incrementAndGet();
            return text;
        };
    }

    private VisionOcrService failing(RuntimeException error, AtomicInteger calls) {
        return (bytes, mime) -> {
            calls.incrementAndGet();
            throw error;
        };
    }

    private FallbackVisionOcrService service(VisionOcrService primary, VisionOcrService fallback,
                                             MeterRegistry registry) {
        return new FallbackVisionOcrService(primary, fallback, "model", "paddleocr", registry);
    }

    @Test
    @DisplayName("主引擎成功时不碰备用引擎")
    void primarySuccessSkipsFallback() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();

        String text = service(ok("主引擎结果", primaryCalls), ok("备用结果", fallbackCalls), null)
            .recognize(IMAGE, MIME);

        assertEquals("主引擎结果", text);
        assertEquals(1, primaryCalls.get());
        assertEquals(0, fallbackCalls.get(), "主引擎成功就不该再调备用——那是白花一次成本");
    }

    /**
     * 主引擎失败时备用接管。
     *
     * <p>客服场景里"有结果"明显优于"没结果"：用户传的截图能读出七成文字，
     * 也比一句"解析失败"有用。</p>
     */
    @Test
    @DisplayName("主引擎失败时由备用引擎接管")
    void fallbackTakesOverOnPrimaryFailure() {
        AtomicInteger fallbackCalls = new AtomicInteger();

        String text = service(
            failing(new IllegalStateException("vision api 429"), new AtomicInteger()),
            ok("备用识别结果", fallbackCalls), null).recognize(IMAGE, MIME);

        assertEquals("备用识别结果", text);
        assertEquals(1, fallbackCalls.get());
    }

    /**
     * 两个都失败时抛<b>主引擎</b>的异常，备用的挂成 suppressed。
     *
     * <p>主引擎才是这个部署期望用的那个，它的失败原因更有诊断价值；
     * 两个都保留，排查时不必再翻日志对时间。</p>
     */
    @Test
    @DisplayName("两个引擎都失败时抛主引擎异常并附带备用异常")
    void bothFailedThrowsPrimaryWithSuppressed() {
        RuntimeException primaryError = new IllegalStateException("vision api 429");
        RuntimeException fallbackError = new IllegalStateException("paddleocr connection refused");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service(
            failing(primaryError, new AtomicInteger()),
            failing(fallbackError, new AtomicInteger()), null).recognize(IMAGE, MIME));

        assertSame(primaryError, thrown, "主引擎的失败原因更有诊断价值，应作为主因抛出");
        assertEquals(1, thrown.getSuppressed().length, "备用引擎的异常必须保留");
        assertSame(fallbackError, thrown.getSuppressed()[0]);
    }

    @Test
    @DisplayName("降级结果进指标：救回来与两个都挂分开计数")
    void fallbackOutcomeGoesToMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();

        service(failing(new IllegalStateException("boom"), new AtomicInteger()),
            ok("ok", new AtomicInteger()), registry).recognize(IMAGE, MIME);

        double recovered = registry.counter("customerwork.attachment.ocr.fallback",
            "from", "model", "to", "paddleocr", "result", "recovered").count();
        assertEquals(1d, recovered, 0.001,
            "降级救回来了要能被观测到——否则主引擎长期不可用也没人知道");
    }

    @Test
    @DisplayName("两个都失败同样计数，与救回来分开")
    void bothFailedIsCountedSeparately() {
        MeterRegistry registry = new SimpleMeterRegistry();

        assertThrows(RuntimeException.class, () -> service(
            failing(new IllegalStateException("a"), new AtomicInteger()),
            failing(new IllegalStateException("b"), new AtomicInteger()), registry).recognize(IMAGE, MIME));

        assertEquals(1d, registry.counter("customerwork.attachment.ocr.fallback",
            "from", "model", "to", "paddleocr", "result", "both-failed").count(), 0.001);
    }

    /** 未配备用引擎时行为与既有单引擎完全一致——不能因为引入降级就改变默认行为。 */
    @Test
    @DisplayName("未配备用引擎时工厂返回单引擎实现")
    void noFallbackConfiguredKeepsSingleEngine() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.getOcr().setEngine("paddleocr");

        VisionOcrService built = VisionOcrServices.create(properties, () -> null,
            VisionOcrUsageRecorder.noop(), null);

        assertTrue(built instanceof PaddleOcrVisionOcrService,
            "留空即单引擎，与既有行为完全一致");
    }

    /** 备用与主引擎相同不算降级——那只是把同一个失败重做一遍。 */
    @Test
    @DisplayName("备用引擎与主引擎相同时视为未配置")
    void sameFallbackEngineIsTreatedAsUnset() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.getOcr().setEngine("paddleocr");
        properties.getOcr().setFallbackEngine("PaddleOCR");

        VisionOcrService built = VisionOcrServices.create(properties, () -> null,
            VisionOcrUsageRecorder.noop(), null);

        assertTrue(built instanceof PaddleOcrVisionOcrService,
            "同一个引擎重试一次不是降级，取值大小写也应归一");
    }
}
