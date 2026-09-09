package com.richard.fyoung.customerwork.data.attachment;

import com.richard.fyoung.customerwork.core.constant.TokenMetrics;
import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubject;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectContext;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把视觉 OCR 的 token 用量记进指标与主体配额。
 *
 * <p><b>两件事分开做，因为它们的可得性不同</b>：</p>
 * <ul>
 *   <li><b>指标一定记</b>（只要接了 Micrometer）。它回答的是"这个部署到底在 OCR 上烧了多少 token"，
 *       这个问题在任何部署形态下都成立。用与对话 token 同一个指标名、靠 {@code source} 标签区分，
 *       这样"总消耗"天然是两者之和，不必让人记得去加第二个指标；</li>
 *   <li><b>配额尽力记</b>。主体来自 {@code QuotaSubjectContext}，而 OCR 跑在
 *       {@code boundedElastic} 上——能不能还原出主体取决于 Reactor 自动上下文传播是否开启，
 *       而那由 {@code customer-work.tenant.enabled} 控制、starter 侧默认关闭。
 *       取不到就只记指标不记配额，并按 info 说明原因：单租户部署本就没有"调用者配额"这回事，
 *       按 error 报会让人以为坏了。</li>
 * </ul>
 *
 * @author owlzhangfq@gmail.com
 */
public class MeteredVisionOcrUsageRecorder implements VisionOcrUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(MeteredVisionOcrUsageRecorder.class);

    private final MeterRegistry meterRegistry;
    private final SubjectQuotaGuard subjectQuotaGuard;

    public MeteredVisionOcrUsageRecorder(MeterRegistry meterRegistry, SubjectQuotaGuard subjectQuotaGuard) {
        this.meterRegistry = meterRegistry;
        this.subjectQuotaGuard = subjectQuotaGuard;
    }

    @Override
    public void record(String modelName, ChatUsageSnapshot usage) {
        if (usage == null) {
            return;
        }
        try {
            recordMetrics(usage);
            recordSubjectQuota(usage);
        } catch (Exception e) {
            // 记账是旁路：图片已经识别出来了，因为记不上账就让附件解析失败是本末倒置
            log.error("vision ocr usage record failed, code={} model={}",
                "VISION-OCR-USAGE-FAIL", modelName, e);
        }
    }

    private void recordMetrics(ChatUsageSnapshot usage) {
        if (meterRegistry == null) {
            return;
        }
        if (usage.inputTokens() > 0) {
            meterRegistry.counter(TokenMetrics.NAME, TokenMetrics.TAG_TYPE, TokenMetrics.TYPE_INPUT,
                    TokenMetrics.TAG_SOURCE, TokenMetrics.SOURCE_VISION_OCR)
                .increment(usage.inputTokens());
        }
        if (usage.outputTokens() > 0) {
            meterRegistry.counter(TokenMetrics.NAME, TokenMetrics.TAG_TYPE, TokenMetrics.TYPE_OUTPUT,
                    TokenMetrics.TAG_SOURCE, TokenMetrics.SOURCE_VISION_OCR)
                .increment(usage.outputTokens());
        }
    }

    /**
     * 记进主体配额。
     *
     * <p>与对话侧同一套口径（{@code AgentCallTimingMiddleware#recordSubjectQuotaUsage}）：
     * 主体取不到就不记，硬记只会算到某个上一次请求残留的身份上。</p>
     */
    private void recordSubjectQuota(ChatUsageSnapshot usage) {
        if (subjectQuotaGuard == null) {
            return;
        }
        long total = (long) usage.inputTokens() + usage.outputTokens();
        if (total <= 0) {
            return;
        }
        QuotaSubject subject = QuotaSubjectContext.get();
        if (subject == null) {
            // 未开自动上下文传播（tenant.enabled=false）或内部任务触发，本就没有"调用者"可言
            log.info("vision ocr usage not charged to any subject, tokens={} —— "
                + "quota subject unavailable on this thread", total);
            return;
        }
        subjectQuotaGuard.recordTokens(subject, total);
    }
}
