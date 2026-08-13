package com.richard.fyoung.customerwork.capability.badcase;

/**
 * badcase 的来源通道。
 *
 * <p>两条通道的信号强度不同，筛选时要区别对待：用户点踩是<b>主观但真实</b>的不满，
 * 哪怕回复在规则上挑不出毛病；质检失败是<b>客观但机械</b>的规则命中，可能只是话术不合规范
 * 而用户其实满意。混成一堆看会让运营用同一把尺子衡量两种完全不同的问题。</p>
 * @author owlzhangfq@gmail.com
 */
public enum BadcaseSource {

    /** 用户主动点踩（{@code FeedbackService} 的 DOWN 反馈）。 */
    NEGATIVE_FEEDBACK,

    /** 系统质检不通过（{@code QualityFeedbackRecorder} 命中规则扣分）。 */
    QUALITY_FAILURE
}
