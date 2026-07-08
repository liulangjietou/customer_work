package com.richard.fyoung.customeradmin.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子数据回归测试：V2__seed_data.sql / mysql/admin-schema.sql 里 admin 账号的 BCrypt 哈希
 * 必须能被 "admin" 明文校验通过——防止未来有人手改种子脚本时手误写错哈希值，
 * 导致默认超管账号登录不了（本测试与 {@code AuthService#INITIAL_ADMIN_PASSWORD_HASH} 使用同一常量）。
 * @author owlzhangfq@gmail.com
 */
class SeedAdminPasswordTest {

    /** 与 V2__seed_data.sql / mysql/admin-schema.sql 里 admin 用户的 password 字段保持一致。 */
    private static final String SEED_HASH =
        "$2a$10$M7Z.8TA1.6l01JSeZRGAb.olJkoDmvk4JSX81kNlZ5rzE1LCsDCFC";

    @Test
    void seedHash_shouldMatchDefaultPassword() {
        assertTrue(new BCryptPasswordEncoder().matches("admin", SEED_HASH),
            "种子脚本里 admin 账号的密码哈希必须能通过 \"admin\" 明文校验，否则默认超管无法登录");
    }
}
