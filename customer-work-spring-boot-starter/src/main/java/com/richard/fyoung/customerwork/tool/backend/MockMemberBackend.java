package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 会员/账户后端的默认演示实现。生产替换为真实会员中心 / 积分 / 账户系统。
 * @author owlzhangfq@gmail.com
 */
public class MockMemberBackend implements MemberBackend {

    private static final Logger log = LoggerFactory.getLogger(MockMemberBackend.class);

    @Override
    public Mono<String> queryPoints(String userId) {
        log.info("[MockMemberBackend] query points: {}", userId);
        return Mono.just("会员 " + userId + " 当前积分 1280 分，可抵扣 12.8 元；本月底将有 200 分到期，建议尽快使用。")
            .delayElement(Duration.ofMillis(60));
    }

    @Override
    public Mono<String> queryMemberLevel(String userId) {
        log.info("[MockMemberBackend] query level: {}", userId);
        return Mono.just("会员 " + userId + " 等级：黄金会员；权益：免运费、专属客服、生日双倍积分；"
                + "再消费 500 元可升级铂金。")
            .delayElement(Duration.ofMillis(60));
    }

    @Override
    public Mono<String> resolveAccountIssue(String issue) {
        log.info("[MockMemberBackend] account issue: {}", issue);
        return Mono.just("关于「" + issue + "」：若无法登录，请尝试『验证码登录』或重置密码；"
                + "若提示账号异常，可能触发风控，请提供注册手机号由人工核验解封。")
            .delayElement(Duration.ofMillis(60));
    }
}
