-- MCP 工具契约快照与漂移记录（能力差距 P1-9）。
--
-- 【为什么需要】
-- MCP 工具的定义住在远端服务器，随时可能变：加一个必填参数、改一个字段类型、下线一个工具。
-- 而本项目对模型、提示词、路由都建了完整的版本治理（不可变版本、内容指纹、上线认证），
-- 唯独 MCP 没有任何基线——listTools 的结果只拿去调试面板展示，不落库，
-- 于是「今天和昨天是不是同一套工具」没人回答得了。
--
-- 【为什么一次快照一行，而不是一个工具一行】
-- 漂移的主语是整个服务器：工具消失与工具新增同样重要。按工具存行的话，「消失」只能靠
-- 「这次没更新 last_seen」间接推断——用时间戳当信号是本仓库反复踩过的形状
-- （评测取基线、CSAT 分区都栽过）。整体快照比对没有这个含糊：不在新契约里就是消失了。
--
-- 【基线就是上一条快照，不设 is_baseline 状态位】
-- 每次采集插一行，drift_* 记的是「相对上一条的差异」。于是漂移只在发生的那一次被报告，
-- 新快照自然成为下一次的基线，不会反复报同一个漂移，也没有状态位要维护。
-- 形态上像 git log：历史只增，任何时刻都能回答「这个 MCP 当时长什么样」。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_mcp_tool_contract` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_id`      VARCHAR(64)  NOT NULL COMMENT '租户编码',
    `mcp_id`         BIGINT       NOT NULL COMMENT 'ai_mcp.id',
    `contract_hash`  CHAR(64)     NOT NULL COMMENT '规范化契约的 SHA-256（工具名/描述/字段类型/必填项）',
    `tool_count`     INT          NOT NULL DEFAULT 0 COMMENT '本次快照的工具数量',
    `contract_json`  LONGTEXT     NOT NULL COMMENT '规范化后的完整契约，供回溯与 diff 展示',
    `drift_severity` VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT '相对上一条快照：NONE/COMPATIBLE/BREAKING',
    `drift_detail`   LONGTEXT     NULL COMMENT '逐条变更明细（JSON 数组）；无漂移时为空',
    `captured_at`    DATETIME     NOT NULL COMMENT '采集时刻',
    `captured_by`    BIGINT       NULL COMMENT '触发采集的用户；调度采集为空',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_mcp_captured` (`tenant_id`, `mcp_id`, `id` DESC),
    KEY `idx_drift` (`tenant_id`, `drift_severity`, `captured_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP 工具契约快照与漂移记录';

-- 【刻意不新增权限点】契约快照就是 MCP 的工具定义，拿得到 mcp:view 的人本来就能在调试面板
-- 看到同样的内容，它不是一个新的数据出口。采集虽然会连远端，但 test-connectivity 同样连远端、
-- 同样回写库，那里的注释写着「不修改配置，仅探测可达性，复用 mcp:view 即可，不额外新增权限点」——
-- 按同一标准，这里也复用 mcp:view。权限点越多越难维护，新增的判据是「是不是新的数据出口」，
-- 不是「是不是新功能」。
