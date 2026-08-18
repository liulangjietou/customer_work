package com.richard.fyoung.customeradmin.datascope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 参与"仅本人"过滤的表及其归属列。
 *
 * <p><b>这里是白名单，与租户维度的黑名单方向相反，这是刻意的。</b>租户维度"能加列的全加"，
 * 漏一张就是串数据；用户维度恰恰相反——绝大多数表是租户内共享的配置资产，
 * 误加一张的后果是同租户成员之间协作断掉（A 建的智能体 B 用不了），
 * 而这种故障不会报错，只表现为"数据莫名其妙少了"，比串数据更难排查。
 * 因此只有"确实由某个后台用户产出、且他人不该看见"的表才进这份清单。</p>
 *
 * <p><b>刻意不在清单里的几类</b>：</p>
 * <ul>
 *   <li><b>租户内共享的配置资产</b>：{@code ai_agent}、{@code ai_knowledge_base}、{@code ai_skill}、
 *       {@code ai_mcp}、{@code ai_channel_robot}、{@code ai_model_config}、{@code sql_*}、
 *       字典/敏感词/限流规则。它们虽有 {@code create_by}，但语义是"谁录的"而非"谁的"。</li>
 *   <li><b>归属映射表本身</b>：{@code ai_chat_session_owner} 只是"谁发起了哪个会话"的索引，
 *       它要与框架的会话状态表联查来排除他人会话（见 {@code ChatSessionOwnerService}）。
 *       那条 SQL 用的是"排除明确属于别人的"这一反向条件，若拦截器再自动加一遍正向条件，
 *       两者 AND 起来恒假，排除条件会整个失效——比不过滤更糟，因为它看起来是生效的。</li>
 *   <li><b>子表</b>：{@code ai_code_knowledge_chunk}、{@code ai_scheduled_task_run}、
 *       {@code ai_agent_*} 关联表。可见性跟随主表，查询一律带主表 ID，
 *       在子表上再挂一份归属列等于维护两套事实。</li>
 *   <li><b>运行时产物</b>：{@code ai_agent_task}（框架子智能体后台任务）、{@code ai_channel_session}、
 *       {@code cw_agent_call_log}。产生于智能体运行线程而非 Web 请求，压根填不出归属人。</li>
 *   <li><b>已在 Service 层按人过滤的</b>：{@code ai_site_message} 的每个接口都显式带了收件人 ID，
 *       {@code workbench_token} 的列举/吊销都带着令牌所属 {@code user_id}。再加一层是重复防御，
 *       且这两张表的 {@code user_id} 语义是"收件人/持有人"而非"创建人"，混在一处只会误导。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public final class DataScopeTables {

    /** 归属列名：由后台用户创建的资源。 */
    private static final String COLUMN_CREATE_BY = "create_by";

    /** 归属列名：以"操作人/发起人"记账的流水表，建表时就叫 user_id，不为统一命名去改历史表。 */
    private static final String COLUMN_USER_ID = "user_id";

    /** 表名（小写）→ 归属列名。 */
    private static final Map<String, String> OWNER_COLUMNS;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        // ---- 智能体工作区：个人的项目与会话 ----
        map.put("ai_project", COLUMN_CREATE_BY);
        map.put("ai_project_session", COLUMN_CREATE_BY);
        // 对话里上传的文件，含解析出的正文，泄露面等同于对话内容本身
        map.put("ai_chat_attachment", COLUMN_CREATE_BY);
        // ---- 智能体工作区：VibeCoding ----
        map.put("ai_code_knowledge_index", COLUMN_CREATE_BY);
        map.put("ai_code_review_task", COLUMN_USER_ID);
        map.put("ai_coding_audit_log", COLUMN_USER_ID);
        // ---- 我的工作台：账号本，行里直接存着目标站点的密码 ----
        map.put("workbench_site", COLUMN_CREATE_BY);
        // ---- 定时任务：任务由人配置，手动触发即以本人身份执行 ----
        map.put("ai_scheduled_task", COLUMN_CREATE_BY);
        // ---- 操作日志：个人行为记录 ----
        map.put("sys_operation_log", COLUMN_USER_ID);
        OWNER_COLUMNS = Collections.unmodifiableMap(map);
    }

    private DataScopeTables() {
    }

    /**
     * 取该表的归属列；不参与用户维度过滤时返回 {@code null}。
     *
     * <p>表名统一转小写比对：MySQL 表名大小写敏感性依赖服务器配置（本机就开着
     * {@code lower_case_table_names=1}），比对时不受其影响。</p>
     */
    public static String ownerColumnOf(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }
        return OWNER_COLUMNS.get(tableName.trim().toLowerCase(Locale.ROOT));
    }

    /** 全部参与过滤的表（只读），供测试与文档核对。 */
    public static Map<String, String> ownerColumns() {
        return OWNER_COLUMNS;
    }
}
