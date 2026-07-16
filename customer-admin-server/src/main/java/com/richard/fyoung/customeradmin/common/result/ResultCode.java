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

    // 5xxxx 系统兜底
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
