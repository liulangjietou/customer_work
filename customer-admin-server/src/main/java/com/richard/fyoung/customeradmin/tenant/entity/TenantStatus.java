package com.richard.fyoung.customeradmin.tenant.entity;

/**
 * 租户生命周期状态。
 *
 * <p>冻结与退租都只改状态、不删数据：退租后的数据主权（导出/清理）属于合规议题，
 * 由后续批次的租户数据导出能力承接，这里绝不做级联删除。</p>
 * @author owlzhangfq@gmail.com
 */
public enum TenantStatus {

    /** 正常，可登录可调用。 */
    ACTIVE,

    /** 冻结：登录与 API 一律拒绝，数据保留（欠费、违规处置等场景）。 */
    SUSPENDED,

    /** 退租：等同冻结，额外标记该租户已终止合作，数据待导出或清理。 */
    TERMINATED;

    /** 是否允许登录与接口调用。 */
    public boolean allowsAccess() {
        return this == ACTIVE;
    }

    /** 宽松解析：库里存的是字符串，脏值一律当作不可访问处理（fail-closed）。 */
    public static TenantStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return SUSPENDED;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SUSPENDED;
        }
    }
}
