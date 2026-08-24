package com.richard.fyoung.customeradmin.workspace.callstats.dto;

/**
 * 安全重放请求。mode 仅接受 MOCK/DRY_RUN；mockAnswer 可由隔离测试桩注入候选输出以验证 diff，
 * 为空时使用原回答，任何字段都不能开启真实生产工具调用。
 */
public record AgentReplayExecuteRequest(String mode, String mockAnswer) {
}
