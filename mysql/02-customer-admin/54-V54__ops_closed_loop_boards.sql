-- ============================================================================
-- B6 运营闭环（三）：语义缓存 / 提示词版本 / 满意度 / 知识盲区 / 死信队列 五个看板
--
-- 五张表都在**客服端库**，admin 经同一个跨库门面（OpsGatewayProvider，共用一个连接池）读写：
-- 它们同属一批、都是低频运营查询，各开一个池只是白占连接数。
--
-- 注：**分级路由刻意没有页面**。它的效果数据是进程内计数（TieredRoutingModel 的档位命中数），
-- 多副本各算各的、重启归零，做成看板会让人误以为那是全局口径。真要看它省了多少钱，
-- 该看的是 B3 已有的 cw_tenant_usage_daily 成本账单。
-- ============================================================================

INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (236, 231, '语义缓存', 'semantic-cache:view', 1, '/ops/semantic-cache', 'Lightning', 'library', 3),
    (237, 231, '提示词版本', 'prompt-version:view', 1, '/ops/prompt-version', 'Document', 'library', 4),
    (238, 231, '满意度看板', 'csat:view', 1, '/ops/csat', 'Star', 'library', 5),
    (239, 231, '知识盲区', 'knowledge-gap:view', 1, '/ops/knowledge-gap', 'QuestionFilled', 'library', 6),
    (240, 231, '死信队列', 'dead-letter:view', 1, '/ops/dead-letter', 'RefreshRight', 'library', 7);

-- 按钮权限：只给会改变数据的动作单独设点，纯查看跟着菜单权限走
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    -- 清缓存是破坏性动作（清掉后一段时间内命中率归零），与查看分开
    (241, 236, '清除缓存', 'semantic-cache:evict', 2, 1),
    -- 补知识会往知识库写入，直接影响线上回答
    (242, 239, '补充知识', 'knowledge-gap:fill', 2, 1),
    -- 重开死信会触发对下游的真实重投
    (243, 240, '重开死信', 'dead-letter:reopen', 2, 1);
