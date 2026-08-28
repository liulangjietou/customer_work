-- 对外开放实例的后台注册用户试用档。
--
-- 为什么不复用 admin-default：那一档（1 小时 / 200 万 token / 200 次）是按内部员工的
-- 使用形态定的——智能体调试、VibeCoding 这类重负载任务。对外开放后，注册者是陌生人，
-- 烧的是平台自己的模型额度，拿这一档等于给每个注册账号一小时 200 万 token 的授信。
--
-- 也不复用 C 端的 free：那一档是 USER 类型（终端用户），而后台登录用户按 ADMIN_USER 计
-- （两套 ID 空间，混用会让计数键碰撞，见 CLAUDE.md B7 约定第 ①）。
--
-- 额度取 admin-default 的十分之一：够走通"建一个智能体、聊几轮、看看效果"这条试用路径，
-- 不够拿来跑批。真实付费客户由运营在后台单独提档。
--
-- 纯种子迁移（无结构变化）：CustomerWorkSchemaMigrator#resolveBaselineVersion 只能靠
-- 这行数据认出本迁移，那边已补 public-trial 的判定；ON DUPLICATE KEY 是第二道保险。
INSERT INTO `cw_subject_quota_level`
    (`tenant_id`, `level_code`, `level_name`, `subject_type`, `window_seconds`,
     `token_limit`, `request_limit`, `exceed_action`, `enabled`, `remark`,
     `created_at_ms`, `updated_at_ms`)
VALUES
    ('default', 'public-trial', '对外试用', 'ADMIN_USER', 3600, 200000, 50, 'BLOCK', 1,
     '对外开放实例的注册用户默认档，配 admin.subject-quota.default-level=public-trial 生效',
     UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE `level_code` = `level_code`;
