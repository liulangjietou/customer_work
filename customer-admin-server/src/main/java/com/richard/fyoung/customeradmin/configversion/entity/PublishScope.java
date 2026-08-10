package com.richard.fyoung.customeradmin.configversion.entity;

/**
 * 发布范围。
 *
 * <p>灰度以<b>租户</b>为单元而不是流量比例：SaaS 天然按租户切分，
 * 而按比例灰度会让同一租户的用户看到不一致的行为，出了问题反而更难复现。</p>
 * @author owlzhangfq@gmail.com
 */
public enum PublishScope {

    /** 全量：所有租户生效。 */
    FULL,

    /** 灰度：仅指定租户生效，其余租户继续用上一个全量版本。 */
    GRAY;

    public static PublishScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FULL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FULL;
        }
    }
}
