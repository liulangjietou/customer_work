package com.richard.fyoung.customerwork.safety.subjectquota;

import java.util.Optional;

/**
 * 用户 → 等级 的绑定查询 SPI。
 *
 * <p>抽成 SPI 而不是让配额领域直接依赖账户领域：配额只需要回答"这个用户是哪一档"，
 * 不该因此认识用户名、密码哈希、头像这些与它无关的东西。实现方是
 * {@code data.user} 包（等级列就落在 {@code cw_user} 上），装配见 {@code UserAccountConfig}。</p>
 * @author owlzhangfq@gmail.com
 */
@FunctionalInterface
public interface SubjectLevelBinding {

    /**
     * 查用户绑定的等级编码。
     *
     * @return 未绑定或查不到时返回 empty，调用方回落配置里的默认档
     */
    Optional<String> levelCodeOf(String userId);
}
