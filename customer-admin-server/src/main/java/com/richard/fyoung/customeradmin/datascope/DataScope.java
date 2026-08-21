package com.richard.fyoung.customeradmin.datascope;

import java.util.Arrays;
import java.util.Locale;

/**
 * 角色的数据范围：决定这个角色的人在页面上能看到哪些行。
 *
 * <p>与租户隔离是两个正交的维度——租户维度由 {@code TenantLineInnerInterceptor} 强制，
 * 回答"哪个租户的数据"；本枚举回答"这个租户里，谁的数据"。两者叠加即"当前租户 + 本人"。</p>
 *
 * <p>范围挂在角色（{@code sys_role.data_scope}）上而不是写死在代码里：谁能看全量属于运营策略，
 * 会随组织变化，焊进版本里意味着每加一个角色就要改代码发版。</p>
 * @author owlzhangfq@gmail.com
 */
public enum DataScope {

    /** 当前租户视角内的全部数据；仅控制面角色可持有，租户切换另行校验权限点。 */
    ALL,

    /** 当前租户内的全部数据。租户管理员默认此范围，本租户成员的产出物互相可见。 */
    TENANT,

    /** 仅本人创建的数据。默认范围——新建角色若不显式指定，按最小权限落这一档。 */
    SELF;

    /** 一个用户可能挂多个角色，取其中最宽的一档（权限叠加而非取交集，与权限点的并集语义一致）。 */
    public static DataScope widest(DataScope left, DataScope right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        // 枚举声明顺序即由宽到窄，ordinal 小的更宽
        return left.ordinal() <= right.ordinal() ? left : right;
    }

    /**
     * 解析库里存的字符串；空值或无法识别时回落 {@link #SELF}。
     *
     * <p>回落到最窄而不是最宽：脏数据的后果应当是"看得少"，不能是"看到全部租户"。</p>
     */
    public static DataScope parse(String value) {
        if (value == null || value.isBlank()) {
            return SELF;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(scope -> scope.name().equals(normalized))
            .findFirst()
            .orElse(SELF);
    }
}
