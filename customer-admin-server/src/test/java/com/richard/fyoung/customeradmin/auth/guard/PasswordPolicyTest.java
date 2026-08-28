package com.richard.fyoung.customeradmin.auth.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自助注册密码强度。
 *
 * <p>规则刻意只有三条：够长、字母数字混合、不在高频弱口令名单里。
 * 不强制特殊字符——那条规则的实际效果是让人把 {@code Password1} 改成 {@code Password1!}，
 * 强度没变，记不住的概率上升。</p>
 */
class PasswordPolicyTest {

    @Test
    void isStrongEnough_shouldAcceptMixedLetterAndDigitAtMinimumLength() {
        assertTrue(PasswordPolicy.isStrongEnough("secret12"));
        assertTrue(PasswordPolicy.isStrongEnough("A1b2C3d4e5"));
        assertTrue(PasswordPolicy.isStrongEnough("x9!@#$%^&"));
    }

    @Test
    void isStrongEnough_shouldRejectTooShort() {
        assertFalse(PasswordPolicy.isStrongEnough("abc1234"));
        assertFalse(PasswordPolicy.isStrongEnough(""));
        assertFalse(PasswordPolicy.isStrongEnough(null));
    }

    @Test
    void isStrongEnough_shouldRequireBothLetterAndDigit() {
        assertFalse(PasswordPolicy.isStrongEnough("abcdefgh"));
        assertFalse(PasswordPolicy.isStrongEnough("12345678"));
        assertFalse(PasswordPolicy.isStrongEnough("!@#$%^&*"));
    }

    /** 弱口令名单里的条目全都满足"字母+数字"，不单独挡就会全部放行。 */
    @Test
    void isStrongEnough_shouldRejectCommonWeakPasswordsThatPassStructuralRules() {
        assertFalse(PasswordPolicy.isStrongEnough("password1"));
        assertFalse(PasswordPolicy.isStrongEnough("Passw0rd"));
        assertFalse(PasswordPolicy.isStrongEnough("admin123"));
        assertFalse(PasswordPolicy.isStrongEnough("qwerty123"));
        assertFalse(PasswordPolicy.isStrongEnough("ABC12345"));
    }
}
