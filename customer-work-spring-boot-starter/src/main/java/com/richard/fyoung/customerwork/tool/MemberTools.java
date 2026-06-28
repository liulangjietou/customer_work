package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MemberBackend;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 会员/账户工具组：积分 / 等级权益 / 账户问题。业务委托给可替换的 {@link MemberBackend}。
 * @author owlzhangfq@gmail.com
 */
public class MemberTools {

    private final MemberBackend backend;

    public MemberTools(MemberBackend backend) {
        this.backend = backend;
    }

    @Tool(description = "查询会员积分余额与即将到期积分。用户问'我有多少积分''积分能干嘛'时调用。")
    public Mono<String> queryPoints(
            @ToolParam(name = "userId", description = "会员/用户标识")
            String userId) {
        return backend.queryPoints(userId);
    }

    @Tool(description = "查询会员等级与对应权益。用户问'我是什么会员''有什么权益''怎么升级'时调用。")
    public Mono<String> queryMemberLevel(
            @ToolParam(name = "userId", description = "会员/用户标识")
            String userId) {
        return backend.queryMemberLevel(userId);
    }

    @Tool(description = "处理账户/登录类问题并给出指引。用户反馈'登不上''账号异常''改密码'时调用。")
    public Mono<String> resolveAccountIssue(
            @ToolParam(name = "issue", description = "账户问题描述")
            String issue) {
        return backend.resolveAccountIssue(issue);
    }
}
