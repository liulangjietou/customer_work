package com.richard.fyoung.customerwork.core.constant;

/**
 * 指标标签键：跨域共用的那几个。
 *
 * <h3>为什么这些字面量要收敛</h3>
 * <p>标签键是<b>监控查询的接口</b>，不是各自的实现细节。同一个语义在不同 meter 上用不同的键
 * （一处 {@code result}、另一处 {@code outcome}），跨 meter 聚合就断了——
 * 而这件事在写代码时完全看不出来，只在有人做看板时才发现拼不起来。</p>
 *
 * <p>反过来说，要统一改一个键名（比如全局把 {@code result} 换成 {@code outcome}）时，
 * 散落各处的定义必然漏改。{@code SharedConstantAlignmentTest} 就是抓这个的——
 * 本类正是被它抓出来后建的。</p>
 *
 * <p><b>只放跨域共用的键</b>。某个域独有的标签（如 token 计量的 source）留在它自己的
 * 常量类里，见 {@link TokenMetrics}。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class MetricTags {

    /**
     * 结果分类：一次操作落到了哪一档。
     *
     * <p>取值由各域自己定义（缓存是 hit/miss/skip，OCR 降级是 recovered/both-failed），
     * 但<b>键必须一致</b>，否则「按结果分类看各能力的健康度」这种查询写不出来。</p>
     */
    public static final String RESULT = "result";

    private MetricTags() {
    }
}
