package com.richard.fyoung.customerwork.safety.subjectquota;

/**
 * 限流主体类型：决定"这一份额度是谁的"。
 *
 * <p>三类主体的身份来源完全不同，额度语义也不同，故必须分开而不是硬凑成一个"用户"概念：</p>
 * <ul>
 *   <li>{@link #USER}：已登录的自然人（JWT subject），额度按人分配，是本功能的主战场；</li>
 *   <li>{@link #IP}：匿名调用方（无登录态），只能按来源 IP 归并——同一 NAT 后的多人会共享一份额度，
 *       这是匿名的固有代价，因此匿名档应当配得最紧；</li>
 *   <li>{@link #API_KEY}：服务端接入方，一把 Key 代表一个系统而非一个人，额度量级应当最大。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public enum QuotaSubjectType {

    /** 已登录用户（按 userId 计）。 */
    USER,

    /** 匿名调用方（按来源 IP 计）。 */
    IP,

    /** 服务端接入方（按 API Key 指纹计）。 */
    API_KEY;

    /** 宽松解析：空/非法回落 {@link #USER}（等级表里最常见的一类，落错只影响这条等级配置的归类展示）。 */
    public static QuotaSubjectType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return USER;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
