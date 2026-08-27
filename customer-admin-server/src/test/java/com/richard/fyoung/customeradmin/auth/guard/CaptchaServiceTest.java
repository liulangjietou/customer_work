package com.richard.fyoung.customeradmin.auth.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图形验证码。
 *
 * <p>关键性质有两条：<b>一次性消费</b>（否则同一张图可以反复提交，验证码退化成形式检查），
 * 以及<b>大小写不敏感</b>（区分大小写只会显著提高真人失败率，对脚本没有额外成本）。</p>
 */
class CaptchaServiceTest {

    private CaptchaStore store;
    private CaptchaService service;

    @BeforeEach
    void setUp() {
        RegistrationGuardProperties properties = new RegistrationGuardProperties();
        store = new InMemoryCaptchaStore();
        service = new CaptchaService(store, properties.getCaptcha());
    }

    @Test
    void issue_shouldReturnDataUriImageAndVerifiableAnswer() {
        CaptchaChallenge challenge = service.issue();

        assertTrue(challenge.image().startsWith("data:image/png;base64,"));
        assertTrue(challenge.image().length() > "data:image/png;base64,".length());
        assertTrue(service.verify(challenge.captchaId(), peek(challenge.captchaId())));
    }

    @Test
    void verify_shouldBeCaseInsensitive() {
        CaptchaChallenge challenge = service.issue();
        String answer = peek(challenge.captchaId());

        assertTrue(service.verify(challenge.captchaId(), answer.toUpperCase(Locale.ROOT)));
    }

    /** 消费一次即失效，重放同一组凭据必须失败。 */
    @Test
    void verify_shouldConsumeAnswerSoThatReplayFails() {
        CaptchaChallenge challenge = service.issue();
        String answer = peek(challenge.captchaId());

        assertTrue(service.verify(challenge.captchaId(), answer));
        assertFalse(service.verify(challenge.captchaId(), answer));
    }

    @Test
    void verify_shouldRejectBlankAndUnknownCredentials() {
        assertFalse(service.verify(null, "abcd"));
        assertFalse(service.verify("some-id", null));
        assertFalse(service.verify("", ""));
        assertFalse(service.verify("not-exist", "abcd"));
    }

    @Test
    void issue_shouldProduceDistinctCredentialsPerCall() {
        assertNotEquals(service.issue().captchaId(), service.issue().captchaId());
    }

    /**
     * 字符集剔除了易混字形：{@code 0 O 1 I 2 Z 5 S 8 B}。
     *
     * <p>断言按<b>大写</b>比对——图片上画的是大写字形，Store 里存的是小写（校验大小写不敏感）。
     * 按小写检查会把清晰的大写 {@code L} 误判成易混的小写 {@code l}。</p>
     */
    @Test
    void issue_shouldOnlyUseUnambiguousCharacters() {
        String allowed = "34679ACDEFGHJKLMNPQRTUVWXY";
        for (int i = 0; i < 50; i++) {
            CaptchaChallenge challenge = service.issue();
            String answer = peek(challenge.captchaId()).toUpperCase(Locale.ROOT);
            for (char c : answer.toCharArray()) {
                assertTrue(allowed.indexOf(c) >= 0, "验证码出现了字符集之外的字形：" + answer);
            }
        }
    }

    /** 直接读 Store：答案只存在那里，图片是画给人看的。读完写回去不影响后续消费。 */
    private String peek(String captchaId) {
        String answer = store.consume(captchaId);
        store.save(captchaId, answer, 180);
        return answer;
    }
}
