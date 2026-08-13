-- ============================================================================
-- B6 运营闭环（二）：badcase 回流
--
-- 负反馈与质检失败早就在库里了（cw_fact_log 的 negative-feedback / quality-failure），
-- 但消费方只有统计（BusinessAnalyticsService）与故障诊断（DiagnosticService）——没有任何回流。
-- FeedbackService 的注释里自己写着"诚实边界：只记录，不自动回流知识库"。
--
-- 补上的是：badcase 待筛队列 → 人工筛选 → 一键转知识库条目 / 转评测用例。
-- 这和评测中心（V52）是一对：有了评测集才知道改得对不对，有了 badcase 才有评测集的来源。
--
-- 本迁移只加菜单与权限点：cw_badcase / cw_eval_case 两张表都在**客服端库**
-- （回流的两个出口——知识库 FAQ 与评测用例——也都在那边），由 starter 的 SchemaInitializer 建表，
-- admin 经跨库门面直接读写。
--
-- 刻意不做自动回流：模型答错的原因千差万别（知识缺失、检索没召回、话术不当、用户表述歧义），
-- 把最不满那批用户的反馈自动灌进知识库，等于开了一个投毒面。人工筛选这一步必须保留。
-- ============================================================================

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (234, 231, 'badcase回流', 'badcase:view', 1, '/ops/badcase', 'Warning', 'library', 2);

-- 采纳与忽略共用一个权限点：三者都是"对这条 badcase 做出判断"，
-- 拆成三个只会让角色配置变复杂而不带来实际的权限边界
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (235, 234, '筛选与回流', 'badcase:adopt', 2, 1);
