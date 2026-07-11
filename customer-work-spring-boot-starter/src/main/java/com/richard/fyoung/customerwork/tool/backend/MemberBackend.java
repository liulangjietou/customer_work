package com.richard.fyoung.customerwork.tool.backend;

import reactor.core.publisher.Mono;

/**
 * 会员/账户后端（扩展点）：对接你自己的会员中心 / 积分 / 账户系统。
 * 默认 {@link MockMemberBackend}；提供自定义 Bean 即可覆盖。
 * @author owlzhangfq@gmail.com
 */
public interface MemberBackend {

    /** 查询会员积分。 */
    Mono<String> queryPoints(String userId);

    /** 查询会员等级与权益。 */
    Mono<String> queryMemberLevel(String userId);

    /** 账户/登录问题处置指引。 */
    Mono<String> resolveAccountIssue(String issue);
}
