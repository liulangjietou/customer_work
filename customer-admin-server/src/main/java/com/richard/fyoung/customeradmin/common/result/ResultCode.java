package com.richard.fyoung.customeradmin.common.result;

import lombok.Getter;

/**
 * 业务错误码：按需求文档"认证类/权限类/参数类/外部依赖类"四分类分段。
 * @author owlzhangfq@gmail.com
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "ok"),

    // 1xxxx 认证类
    UNAUTHORIZED(10001, "未登录或登录已失效"),
    LOGIN_FAILED(10002, "用户名或密码错误"),
    TOKEN_EXPIRED(10003, "登录状态已过期，请重新登录"),
    SSO_LOGIN_FAILED(10004, "OA账号或密码错误"),
    SSO_SERVICE_UNAVAILABLE(10005, "OA域服务暂不可用，请稍后重试或联系管理员"),
    SSO_NOT_ENABLED(10006, "OA单点登录未开通"),

    // 2xxxx 权限类
    FORBIDDEN(20001, "无权限执行该操作"),
    FORCE_CHANGE_PASSWORD(20002, "首次登录请先修改密码"),

    // 3xxxx 参数类
    PARAM_INVALID(30001, "参数校验失败"),
    PARAM_MISSING(30002, "缺少必填参数"),
    RESOURCE_NOT_FOUND(30003, "资源不存在"),
    RESOURCE_DUPLICATE(30004, "资源已存在，唯一键冲突"),
    RESOURCE_IN_USE(30005, "资源正被引用，无法删除"),
    SCHEDULED_TASK_DISABLED(30006, "定时任务未启用"),
    NO_FILE_CHANGES(30007, "本轮对话暂无文件变更"),
    SQL_NOT_READONLY(30008, "仅允许只读 SELECT/WITH 查询"),
    SQL_PARAM_INVALID(30009, "SQL 查询参数不合法"),

    // 4xxxx 外部依赖类
    MODEL_TEST_TIMEOUT(40001, "模型连通性测试超时"),
    MODEL_TEST_FAILED(40002, "模型连通性测试失败"),
    MCP_CONNECT_FAILED(40003, "MCP 连接失败"),
    AGENT_CAPABILITY_NOT_SUPPORTED(40004, "智能体不支持该能力"),
    AGENT_DISABLED(40005, "智能体未启用"),
    GIT_COMMAND_FAILED(40006, "Git 命令执行失败"),
    GIT_ASSISTANT_AI_FAILED(40007, "AI 生成失败，请稍后重试"),
    SQL_DATASOURCE_UNAVAILABLE(40008, "SQL 数据源不可用"),
    SQL_QUERY_EXECUTE_FAILED(40009, "SQL 查询执行失败"),
    TICKET_STATE_CONFLICT(40010, "工单状态已变化或已被其他坐席处理，请刷新后重试"),
    CUSTOMER_WORK_AUTH_FAILED(40011, "客服坐席服务鉴权失败"),
    CUSTOMER_WORK_UNAVAILABLE(40012, "客服坐席服务暂不可用，请稍后重试"),
    ORDER_NOT_FOUND(40013, "订单不存在"),
    ORDER_STATE_CONFLICT(40014, "订单当前状态不允许该操作"),
    // 40015 已退役：P1-3 bind mount 产物同步落地后 docker 模式一键回滚与 local 等价可用，不再拦截；
    // 保留枚举占位以稳定错误码注册表，不再抛出、也不复用该数值。
    ROLLBACK_NOT_SUPPORTED_IN_DOCKER(40015, "Docker 沙箱模式暂不支持一键回滚"),
    ROLLBACK_BASELINE_MISSING(40016, "会话基线不存在，无法回滚"),
    SCHEDULER_CRON_INVALID(40017, "cron 表达式非法"),
    SYSTEM_TOOL_HTTP_FORBIDDEN(40018, "目标地址不在允许访问范围内，已被安全策略拦截"),
    AI_REVIEW_FAILED(40019, "AI 代码审查失败，请稍后重试"),
    MODEL_PROVIDER_NOT_SUPPORTED(40020, "暂不支持的模型 provider"),
    RUNTIME_PUBLISH_DISABLED(40021, "运行时配置发布未启用"),
    RUNTIME_PUBLISH_FAILED(40022, "运行时配置发布失败"),
    CHANNEL_BINDING_NOT_FOUND(40023, "渠道绑定不存在"),
    PLAN_CONFIRM_NOT_FOUND(40024, "计划确认项不存在或已失效（可能已处理、已超时，或服务重启后挂起态已丢失）"),
    // P3-1 多 Agent 协作编程
    COLLAB_ROLE_FAILED(40025, "协作编程某角色执行失败，流水已中断，请稍后重试"),
    COLLAB_NO_ROLES(40026, "协作编程未配置任何角色"),
    // P3-2 代码知识库问答
    KNOWLEDGE_EMBEDDING_NOT_CONFIGURED(40027, "未配置可用的 DashScope Embedding 模型，无法构建/检索代码知识库"),
    KNOWLEDGE_EMBEDDING_FAILED(40028, "Embedding 向量生成失败，请稍后重试"),
    KNOWLEDGE_INDEX_NOT_FOUND(40029, "代码知识库索引不存在"),
    KNOWLEDGE_PATH_NOT_ALLOWED(40030, "指定的源码路径不在允许的根目录范围内，已被安全策略拦截"),
    KNOWLEDGE_INDEX_BUILDING(40031, "该索引正在构建中，请等待构建完成后再操作"),
    MENU_REORDER_CONFLICT(40032, "菜单排序正在被其他管理员调整，请稍后重试"),
    AGENT_MEMORY_OPERATION_FAILED(40033, "智能体记忆操作失败，请稍后重试"),
    // 外部 RAG 知识库检索（40032/40033 已占用，故从 40034 起）
    KNOWLEDGE_BASE_TEST_FAILED(40034, "知识库连通性测试失败"),
    KNOWLEDGE_BASE_HTTP_FORBIDDEN(40035, "知识库服务地址不在允许访问范围内，已被安全策略拦截"),
    KNOWLEDGE_BASE_SEARCH_FAILED(40036, "知识库检索失败"),

    // 多租户（B1 租户地基）
    TENANT_NOT_FOUND(40037, "租户不存在"),
    TENANT_CODE_DUPLICATE(40038, "租户编码已存在"),
    TENANT_CODE_IMMUTABLE(40039, "租户编码创建后不可修改"),
    TENANT_RESERVED_PROTECTED(40040, "系统保留租户不允许该操作"),
    TENANT_SUSPENDED(40041, "租户已被冻结或已退租，请联系运营方"),
    TENANT_VIEW_FORBIDDEN(40042, "只有平台运营方可以切换租户视角"),

    // 主体级速率配额（B7）：额度用尽不是权限问题也不是参数问题，单独发码，
    // 前端据此给"稍后再试"而不是"联系管理员开权限"
    QUOTA_EXCEEDED(40043, "本时段的 AI 用量额度已用尽，请稍后再试"),

    // 5xxxx 系统兜底
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
