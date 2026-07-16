-- =============================================================================
-- OA 域账号（LDAP/AD）单点登录支持（Flyway V6，仅本地/测试 profile 自动执行）
-- =============================================================================
-- 说明：
--   1. 新增 login_type 区分账号来源：LOCAL 本地账号（密码存本表 BCrypt 哈希）/
--      LDAP 域账号（密码由企业 AD 域控管理，本表不存密码，登录时实时向 AD 发起 Bind 校验，
--      见 LdapAuthService + AuthService#ssoLogin）。
--   2. password 列改为可空：LDAP 账号首次登录由 AuthService#ssoLogin 自动创建 sys_user 记录，
--      不落地/不知道用户的域密码，避免误存一个不可用的假密码造成误导。
--   3. LDAP 账号默认无强制改密逻辑（AuthService#login 的 INITIAL_ADMIN_PASSWORD_HASH 判断
--      仅比对 LOCAL 账号密码哈希，LDAP 账号该字段为 NULL，天然跳过）。
-- =============================================================================

ALTER TABLE `sys_user`
    MODIFY COLUMN `password` VARCHAR(128) NULL COMMENT '密码（BCrypt 加密存储，LDAP 账号为 NULL）',
    ADD COLUMN `login_type` VARCHAR(16) NOT NULL DEFAULT 'LOCAL' COMMENT '账号来源：LOCAL本地账号 / LDAP域账号(OA单点登录)' AFTER `nickname`;
