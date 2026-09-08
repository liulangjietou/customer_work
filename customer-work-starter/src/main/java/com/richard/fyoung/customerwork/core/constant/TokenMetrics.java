package com.richard.fyoung.customerwork.core.constant;

/**
 * token 用量指标的名称与标签。
 *
 * <p><b>为什么要收敛</b>：对话链路的 token 由 {@code AgentCallTimingMiddleware} 记，
 * 视觉 OCR 的由 {@code MeteredVisionOcrUsageRecorder} 记，两者<b>必须落在同一个指标上</b>——
 * 这样"这个部署总共烧了多少 token"天然是两者之和，不必让人记得再去加第二个指标。
 * 各写一份字面量则是两个真相来源：改了一处忘了另一处，指标会静默分裂成两条曲线，
 * 而看板上只会显示其中一条，没有任何报错。</p>
 *
 * <p>来源用 {@link #TAG_SOURCE} 区分：不加这个标签的话，两条链路的用量会混成一个数，
 * 想回答"OCR 到底占多少"就只能去翻日志。</p>
 *
 * <p><b>两条链路必须用同一套标签键</b>，这不是整洁问题：Micrometer 要求同名 meter 的标签键集合一致，
 * Prometheus registry 在标签键不一致时会直接拒绝（"requires that all meters with the same name
 * have the same set of tag keys"）。所以给 OCR 加 {@code source} 的同时，
 * 对话链路那侧也必须补上——只给一边加会让指标在生产的 Prometheus 上报错，而本地
 * SimpleMeterRegistry 不会。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class TokenMetrics {

    /** 指标名：对话与视觉 OCR 共用。 */
    public static final String NAME = "customerwork.agent.tokens";

    /** 标签：输入 / 输出。 */
    public static final String TAG_TYPE = "type";

    /** 标签：用量来源，用于把 OCR 从对话总量里拆出来。 */
    public static final String TAG_SOURCE = "source";

    public static final String TYPE_INPUT = "input";

    public static final String TYPE_OUTPUT = "output";

    /**
     * 来源：对话链路。
     *
     * <p>刻意不叫 {@code "agent"}——那个词在本项目里太笼统（OCR 同样是智能体能力的一部分），
     * 且与配置回滚补丁里的 JSON 字段名撞值。</p>
     */
    public static final String SOURCE_CONVERSATION = "conversation";

    /** 来源：附件图片的视觉 OCR。 */
    public static final String SOURCE_VISION_OCR = "vision-ocr";

    private TokenMetrics() {
    }
}
