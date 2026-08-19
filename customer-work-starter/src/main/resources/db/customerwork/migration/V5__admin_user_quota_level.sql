-- 后台管理系统登录用户的配额档位。
--
-- 单独一档而不是复用 free：后台用户是内部员工，跑的是智能体调试、VibeCoding 这类重负载，
-- 按 C 端档位限会让他们干不了活；但完全不限又挡不住"某个脚本挂在后台狂刷"这种真实事故。
-- 窗口取 1 小时（后台操作本就是低频长任务，30 分钟窗口对一次长调试来说太短）。
--
-- 独立成 V5 而不是并进 V4：V4 已在环境上执行过，改动它会让 Flyway 的 checksum 校验失败。
--
-- INSERT 必须幂等：本文件是纯种子迁移，没有任何结构变化，"完整镜像接管"那套判定
-- （CustomerWorkSchemaMigrator#resolveBaselineVersion）只能靠数据认出它。判定那边已经补了
-- 对应的数据检查，这里的 ON DUPLICATE KEY 是第二道保险——种子重复插入不该让整个迁移炸掉。
INSERT INTO `cw_subject_quota_level`
    (`tenant_id`, `level_code`, `level_name`, `subject_type`, `window_seconds`,
     `token_limit`, `request_limit`, `exceed_action`, `enabled`, `remark`,
     `created_at_ms`, `updated_at_ms`)
VALUES
    ('default', 'admin-default', '后台用户', 'ADMIN_USER', 3600, 2000000, 200, 'BLOCK', 1,
     '后台登录用户默认档，与 SubjectQuotaProperties 内置档一致',
     UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000),
    ('default', 'admin-power', '后台高配', 'ADMIN_USER', 3600, 10000000, 1000, 'BLOCK', 1,
     '给需要跑大批量调试的账号单独提档',
     UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000)
ON DUPLICATE KEY UPDATE `level_code` = `level_code`;
