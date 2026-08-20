package com.richard.fyoung.customerwork.core.constant;

/**
 * 库表通用启用状态列（{@code status}）的取值。
 *
 * <p>客服端与后台的绝大多数配置表都用同一个 {@code tinyint status} 表达"启不启用"，
 * 这个 {@code 1} 此前在 18 个类里以 {@code STATUS_ENABLED} / {@code ENABLED} 两种命名各写一遍。
 * 数字本身不会写歪，但"到底哪个值是启用"这件事散在 18 处、没有一处说明，
 * 新写一张表时只能靠翻别的类去猜——集中一处是为了让这个约定有个可引用的答案。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class StatusFlags {

    /** 启用。 */
    public static final int ENABLED = 1;

    /** 停用。 */
    public static final int DISABLED = 0;

    private StatusFlags() {
    }
}
