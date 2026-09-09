package com.richard.fyoung.customerwork.data.attachment;

import com.richard.fyoung.customerwork.core.dto.ChatUsageSnapshot;

/**
 * 视觉 OCR 的模型用量记账 SPI。
 *
 * <p><b>要解决的问题</b>：图片附件的 OCR 是一次<b>真实的模型调用</b>——烧 token、花钱、由用户上传触发，
 * 但它直接调 {@code Model#stream(...)}，绕开了 Agent 链路，因此完全不经过
 * {@code AgentCallTimingMiddleware}（项目里 token 的唯一落点）。结果是
 * <b>OCR 消耗的 token 不进配额、不进账单、连指标都没有</b>：用户传一百张图，
 * 这部分成本在系统里完全不可见，而对话模型那边有失败转移、熔断、分级路由、逐段计量一整套。</p>
 *
 * <p>把记账做成 SPI 而不是直接在 OCR 服务里调配额组件，是因为
 * {@code data.attachment} 与 {@code safety.subjectquota} 是两个域——
 * OCR 服务只负责"报出用了多少"，"记到谁头上"由装配侧决定。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public interface VisionOcrUsageRecorder {

    /**
     * 记一次 OCR 的模型用量。
     *
     * <p>实现必须自行吞掉异常——记账是旁路，失败不该让附件解析整个失败：
     * 用户上传的图片已经识别出来了，因为记不上账就把结果丢掉是本末倒置。</p>
     */
    void record(String modelName, ChatUsageSnapshot usage);

    /** 不记账的实现：供离线单测与未装配计量设施的部署使用。 */
    static VisionOcrUsageRecorder noop() {
        return (modelName, usage) -> {
        };
    }
}
