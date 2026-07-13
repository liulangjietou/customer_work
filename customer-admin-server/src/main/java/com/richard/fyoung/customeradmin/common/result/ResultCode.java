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

    // 4xxxx 外部依赖类
    MODEL_TEST_TIMEOUT(40001, "模型连通性测试超时"),
    MODEL_TEST_FAILED(40002, "模型连通性测试失败"),
    MCP_CONNECT_FAILED(40003, "MCP 连接失败"),
    AGENT_CAPABILITY_NOT_SUPPORTED(40004, "智能体不支持该能力"),
    AGENT_DISABLED(40005, "智能体未启用"),
    GIT_COMMAND_FAILED(40006, "Git 命令执行失败"),
    GIT_ASSISTANT_AI_FAILED(40007, "AI 生成失败，请稍后重试"),

    // 5xxxx 系统兜底
    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
