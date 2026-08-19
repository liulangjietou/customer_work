package com.richard.fyoung.customerwork.safety.subjectquota;

/**
 * 限流主体类型：决定"这一份额度是谁的"。
 *
 * <p>四类主体的身份来源完全不同，额度语义也不同，故必须分开而不是硬凑成一个"用户"概念：</p>
 * <ul>
 *   <li>{@link #USER}：客服端已登录的终端用户（JWT subject，落 {@code cw_user}），是本功能的主战场；</li>
 *   <li>{@link #ADMIN_USER}：后台管理系统的登录用户（Sa-Token 登录 ID，落 {@code sys_user}）；</li>
 *   <li>{@link #IP}：匿名调用方（无登录态），只能按来源 IP 归并——同一 NAT 后的多人会共享一份额度，
 *       这是匿名的固有代价，因此匿名档应当配得最紧；</li>
 *   <li>{@link #API_KEY}：服务端接入方，一把 Key 代表一个系统而非一个人，额度量级应当最大。</li>
 * </ul>
 *
 * <p><b>后台用户为什么不能复用 {@link #USER}</b>：{@code sys_user} 与 {@code cw_user} 是两套独立的
 * ID 空间，同一个 ID 值在两边指的是不同的人。混在一个类型里，计数键就会碰撞——
 * 一个后台管理员和一个终端用户莫名其妙共用一份额度，而且谁也查不出为什么。</p>
 * @author owlzhangfq@gmail.com
 */
public enum QuotaSubjectType {

    /** 客服端终端用户（按 cw_user 的 userId 计）。 */
    USER,

    /** 后台管理系统登录用户（按 sys_user 的登录 ID 计）。 */
    ADMIN_USER,

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
