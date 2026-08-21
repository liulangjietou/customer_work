-- =============================================================================
-- compose 本地一键起中间件时的后台库初始化（mysql 容器首次初始化自动执行）。
-- 仅建库：customer_admin 的表结构由 admin-server dev profile 的 Flyway
-- （01-V1__init_schema.sql 起）或生产 DBA 变更流程维护，此处不越权建表。
-- 字符集与业务库保持一致（utf8mb4）。
-- =============================================================================
CREATE DATABASE IF NOT EXISTS `customer_admin` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
