package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MockMemberBackend;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会员/账户工具组单测：积分 / 等级 / 账户问题。
 * @author owlzhangfq@gmail.com
 */
class MemberToolsTest {

    private final MemberTools tools = new MemberTools(new MockMemberBackend());

    @Test
    void queryPoints_shouldReturnPoints() {
        StepVerifier.create(tools.queryPoints("U1001"))
            .assertNext(r -> assertTrue(r.contains("积分")))
            .verifyComplete();
    }

    @Test
    void queryMemberLevel_shouldReturnLevelAndBenefits() {
        StepVerifier.create(tools.queryMemberLevel("U1001"))
            .assertNext(r -> assertTrue(r.contains("会员") && r.contains("权益")))
            .verifyComplete();
    }

    @Test
    void resolveAccountIssue_shouldGiveGuidance() {
        StepVerifier.create(tools.resolveAccountIssue("登录不上"))
            .assertNext(r -> assertTrue(r.contains("登录") || r.contains("密码")))
            .verifyComplete();
    }
}
