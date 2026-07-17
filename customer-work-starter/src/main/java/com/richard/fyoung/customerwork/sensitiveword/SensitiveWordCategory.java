package com.richard.fyoung.customerwork.sensitiveword;

/**
 * 敏感词分类（以枚举名字符串落库，便于按类目统计与差异化处置）。
 *
 * <p>演示用占位类目，生产可按合规要求扩展；未知/无法归类的一律 {@link #CUSTOM}。</p>
 * @author owlzhangfq@gmail.com
 */
public enum SensitiveWordCategory {

    /** 涉政。 */
    POLITICS,
    /** 涉黄。 */
    PORN,
    /** 辱骂 / 人身攻击。 */
    ABUSE,
    /** 竞品词（营销口径管控）。 */
    COMPETITOR,
    /** 自定义 / 其它。 */
    CUSTOM;

    /** 安全解析：未知或空值一律归 {@link #CUSTOM}，避免脏数据导致自动机构建失败（fast fail 收口在此）。 */
    public static SensitiveWordCategory fromName(String name) {
        if (name == null || name.isEmpty()) {
            return CUSTOM;
        }
        try {
            return SensitiveWordCategory.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CUSTOM;
        }
    }
}
