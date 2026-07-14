-- =============================================================================
-- SQL 配置管理（Flyway V14，仅本地/测试 profile 自动执行，生产手工同步）
-- =============================================================================
-- 参考 gmcf-operate 的"数据源 + 可参数化 SQL + 参数元数据 + 列转换器"四表模型，落地一个
-- 通用查询能力：管理员配置外部只读数据源与命名参数 SQL（:param），前端按 defineKey 渲染
-- 参数表单执行查询返回动态列结果。有意裁剪（不照搬 gmcf-operate 的服务端渲染包袱）：
--   1. query_sql/count_sql 明文存 TEXT（不做 Base64 转义）；
--   2. 去掉 Groovy paramVerify 脚本（任意脚本执行风险），参数校验由类型/必填元数据承担；
--   3. 列转换器不用 Class.forName 反射，改为内置枚举 transform_type（DATE_FORMAT/VALUE_MAP）。
-- 安全边界：强制只读 SELECT（保存时 SqlValidator 校验 + 执行时 readOnly 连接双保险），
-- 查询超时/最大行数由全局配置 admin.sql-config.* 兜底，防止误打生产库。
--
-- 手工同步注意：走 stdin 管道 apply 时客户端字符集可能回退 latin1 导致中文 COMMENT 字节级写坏，
-- 故本文件首行显式 SET NAMES utf8mb4（Flyway JDBC 连接不受影响，此行对其无害）。
--
-- 菜单/权限种子：id 跳跃分配到 110 起（V13 的 AI 编码审计已把 id 用到 100，中间留出安全空间）。
-- 超级管理员（super_admin）无需 sys_role_permission 记录——AdminStpInterfaceImpl 对超管直接
-- 返回全部权限点（沿用 V12/V13 同款做法，普通角色需在角色管理页手工授权）。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `sql_datasource` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`        VARCHAR(64) NOT NULL COMMENT '数据源名称（唯一，供 SQL 定义下拉选择）',
    `jdbc_url`    VARCHAR(512) NOT NULL COMMENT 'JDBC 连接串',
    `username`    VARCHAR(128) NOT NULL COMMENT '连接用户名',
    `password`    VARCHAR(512) NOT NULL COMMENT '连接密码（AES-GCM 密文，永不明文返回）',
    `enabled`     TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0禁用 / 1启用',
    `remark`      VARCHAR(255) COMMENT '备注',
    `create_by`   BIGINT COMMENT '创建人ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`   BIGINT COMMENT '更新人ID',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_sql_datasource_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL 配置外部数据源';

CREATE TABLE IF NOT EXISTS `sql_define` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `define_key`    VARCHAR(64) NOT NULL COMMENT '定义编码（唯一，通用查询接口按此 key 执行）',
    `datasource_id` BIGINT NOT NULL COMMENT '关联数据源ID（逻辑关联 sql_datasource.id）',
    `sql_describe`  VARCHAR(255) NOT NULL COMMENT 'SQL 用途描述',
    `query_sql`     TEXT NOT NULL COMMENT '查询 SQL（命名参数 :paramName，强制只读 SELECT/WITH）',
    `count_sql`     TEXT COMMENT '总数 SQL（可空；为空则前端只有上/下一页无总数）',
    `auto_load`     TINYINT NOT NULL DEFAULT 0 COMMENT '打开页面是否自动执行：0否 / 1是',
    `enabled`       TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0禁用 / 1启用',
    `remark`        VARCHAR(255) COMMENT '备注',
    `create_by`     BIGINT COMMENT '创建人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT COMMENT '更新人ID',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    UNIQUE KEY `uk_sql_define_key` (`define_key`),
    INDEX `idx_sql_define_datasource` (`datasource_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL 查询定义';

CREATE TABLE IF NOT EXISTS `sql_define_param` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `define_id`     BIGINT NOT NULL COMMENT '所属 SQL 定义ID',
    `param_name`    VARCHAR(64) NOT NULL COMMENT '参数名（对应 SQL 里的 :paramName）',
    `param_desc`    VARCHAR(255) COMMENT '参数说明（前端表单 label）',
    `param_type`    VARCHAR(16) NOT NULL COMMENT '参数类型：STRING / INTEGER / DATETIME',
    `required`      TINYINT NOT NULL DEFAULT 0 COMMENT '是否必填：0否 / 1是',
    `default_value` VARCHAR(255) COMMENT '默认值（支持 ${now}/${now-14d}/${now-2h} 时间表达式）',
    `drop_down`     TEXT COMMENT '下拉选项（JSON 对象 {"值":"显示名"}，可空）',
    `is_page_num`   TINYINT NOT NULL DEFAULT 0 COMMENT '是否分页页码参数：其值换算为 offset 绑定',
    `is_page_size`  TINYINT NOT NULL DEFAULT 0 COMMENT '是否分页页大小参数：绑定 pageSize 本身',
    `sort`          INT NOT NULL DEFAULT 0 COMMENT '表单排序（升序）',
    `create_by`     BIGINT COMMENT '创建人ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`     BIGINT COMMENT '更新人ID',
    `update_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    INDEX `idx_sql_define_param_define` (`define_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL 查询参数元数据';

CREATE TABLE IF NOT EXISTS `sql_field_transform` (
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `define_id`        BIGINT NOT NULL COMMENT '所属 SQL 定义ID',
    `field_name`       VARCHAR(64) NOT NULL COMMENT '匹配的结果列名',
    `transform_type`   VARCHAR(16) NOT NULL COMMENT '转换类型：DATE_FORMAT / VALUE_MAP',
    `transform_config` VARCHAR(512) NOT NULL COMMENT '转换配置（DATE_FORMAT 为格式串 / VALUE_MAP 为 JSON 映射）',
    `create_by`        BIGINT COMMENT '创建人ID',
    `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_by`        BIGINT COMMENT '更新人ID',
    `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 / 1删除',
    INDEX `idx_sql_field_transform_define` (`define_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL 结果列转换器';

-- 一级目录：SQL配置管理（顶层菜单 parent_id=0，无路由 path，仅作分组）。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (110, 0, 'SQL配置管理', 'sql-config', 1, '', 'DataLine', 'library', 6);

-- 二级菜单：数据源管理 / SQL定义。
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `type`, `path`, `icon`, `icon_type`, `sort`) VALUES
    (111, 110, '数据源管理', 'sql-datasource', 1, '/sql/datasource', 'Coin', 'library', 1),
    (112, 110, 'SQL定义',   'sql-define',     1, '/sql/define',     'Document', 'library', 2);

-- 三级：按钮/接口权限点（type=2）。sql-query:view/export 挂目录（110）下——通用查询页由管理员在
-- 菜单管理里自建 /sql/query?defineKey=xxx 报表菜单，其查询/导出权限统一由这两个点控制。
INSERT INTO `sys_permission` (`parent_id`, `perm_name`, `perm_code`, `type`, `sort`) VALUES
    (111, '查看数据源', 'sql-datasource:view',   2, 1),
    (111, '新增数据源', 'sql-datasource:add',    2, 2),
    (111, '编辑数据源', 'sql-datasource:edit',   2, 3),
    (111, '删除数据源', 'sql-datasource:delete', 2, 4),
    (112, '查看SQL定义', 'sql-define:view',   2, 1),
    (112, '新增SQL定义', 'sql-define:add',    2, 2),
    (112, '编辑SQL定义', 'sql-define:edit',   2, 3),
    (112, '删除SQL定义', 'sql-define:delete', 2, 4),
    (110, '执行SQL查询', 'sql-query:view',   2, 5),
    (110, '导出SQL查询', 'sql-query:export', 2, 6);

-- 一些配置样例 为了演示和验证
INSERT INTO `sql_datasource` (`id`, `name`, `jdbc_url`, `username`, `password`, `enabled`, `remark`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1, '审计日志测试查看', 'jdbc:mysql://localhost:3306/agent_scope_customer_work', 'root', 'J9DLVag0gGSLSKGUpocWq72j0qZ4cuke/NYhL9fW3XU=', 1, '', 1, '2026-07-14 15:57:55', 1, '2026-07-14 15:57:55', 0);
INSERT INTO `sql_define` (`id`, `define_key`, `datasource_id`, `sql_describe`, `query_sql`, `count_sql`, `auto_load`, `enabled`, `remark`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1, 'defineKey', 1, '审计日志', 'SELECT id, session_id AS \'会话ID\',duration_ms as \'操作耗时\', create_time AS \'创建时间\'\nFROM customer_admin.ai_coding_audit_log WHERE create_time >= :startTime\nORDER BY id DESC LIMIT :pageNum, :pageSize', 'SELECT count(*)\nFROM customer_admin.ai_coding_audit_log WHERE create_time >= :startTime', 1, 1, '', 1, '2026-07-14 15:59:52', 1, '2026-07-14 15:59:52', 0);
INSERT INTO `sql_field_transform` (`id`, `define_id`, `field_name`, `transform_type`, `transform_config`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1, 1, 'create_time', 'DATE_FORMAT', 'yyyy-MM-dd HH:mm:ss', 1, '2026-07-14 16:02:43', 1, '2026-07-14 16:02:43', 0);
INSERT INTO `customer_admin`.`sql_define_param` (`id`, `define_id`, `param_name`, `param_desc`, `param_type`, `required`, `default_value`, `drop_down`, `is_page_num`, `is_page_size`, `sort`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (1, 1, 'startTime', '', 'STRING', 0, '${now-14d}', '', 0, 0, 0, 1, '2026-07-14 16:00:54', 1, '2026-07-14 16:00:54', 0);
INSERT INTO `customer_admin`.`sql_define_param` (`id`, `define_id`, `param_name`, `param_desc`, `param_type`, `required`, `default_value`, `drop_down`, `is_page_num`, `is_page_size`, `sort`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (2, 1, 'pageNum', '分页页码', 'INTEGER', 0, '0', '', 1, 0, 0, 1, '2026-07-14 16:01:32', 1, '2026-07-14 16:01:32', 0);
INSERT INTO `customer_admin`.`sql_define_param` (`id`, `define_id`, `param_name`, `param_desc`, `param_type`, `required`, `default_value`, `drop_down`, `is_page_num`, `is_page_size`, `sort`, `create_by`, `create_time`, `update_by`, `update_time`, `deleted`) VALUES (3, 1, 'pageSize', '', 'INTEGER', 0, '30', '', 0, 1, 0, 1, '2026-07-14 16:01:52', 1, '2026-07-14 16:01:52', 0);