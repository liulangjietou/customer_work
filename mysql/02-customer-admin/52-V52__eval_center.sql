-- ============================================================================
-- B6 运营闭环（一）：一级菜单 + 评测中心
--
-- capability/eval 下的 IntentEvalRunner / QualityEvalRunner / JudgeModel 早就写好了，
-- 但全仓除了自己的单测之外没有任何调用方——没有后台入口、没有定时任务、没有 CI 门禁，
-- 等于体温计造好了放在抽屉里。改提示词、换模型时只能靠人肉体感判断好坏。
--
-- 本迁移只加菜单与权限点：运行记录表 cw_eval_run / cw_eval_case 落在**客服端库**
-- （评测跑在客服端，那里才有真实的 orchestrator 与模型链），由 starter 的 SchemaInitializer 建表，
-- admin 经跨库门面读写——照内容风控三表与配额表的先例。
--
-- 菜单单开一级"运营闭环"而不是挂进"AI 配置"：B6 一共 7 个页面，全塞进去会让那个菜单撑到 16 项，
-- 而这 7 项本身是一组内聚的东西（评测 → badcase 回流 → 补知识/补用例 → 再评测）。
-- ============================================================================

-- 一级菜单（id 从 231 起，230 是 V51 配置版本占用的最后一个）
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (231, 0, '运营闭环', 'ops', 1, '/ops', 'TrendCharts', 'library', 4);

-- 评测中心
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (232, 231, '评测中心', 'eval:view', 1, '/ops/eval', 'DataAnalysis', 'library', 1);

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (233, 232, '触发评测', 'eval:run', 2, 1);
