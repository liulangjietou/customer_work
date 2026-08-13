package com.richard.fyoung.customerwork.capability.badcase;

/**
 * badcase 查询条件。
 *
 * <p>收成一个对象而不是摊成一串参数：筛选维度还会长（按类目、按时间窗），
 * 每加一个就改一次 Store 接口签名会波及全部实现。</p>
 *
 * @param status 处理状态；{@code null} 表示不限
 * @param source 来源通道；{@code null} 表示不限
 * @param offset 偏移量（从 0 开始）
 * @param limit  返回条数上限
 * @author owlzhangfq@gmail.com
 */
public record BadcaseQuery(BadcaseStatus status, BadcaseSource source, int offset, int limit) {

    /** 待筛选队列：运营日常打开界面看到的就是这个。 */
    public static BadcaseQuery pending(int offset, int limit) {
        return new BadcaseQuery(BadcaseStatus.PENDING, null, offset, limit);
    }
}
