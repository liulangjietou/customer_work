package com.richard.fyoung.customeradmin.auth.guard;

import java.util.Set;

/**
 * 自助注册的密码强度判定。
 *
 * <p>只做两件事：长度不低于 8 位、同时含字母与数字，外加一份高频弱口令的黑名单。
 * 刻意不强制特殊字符——那条规则的实际效果是让人把 {@code Password1} 改成
 * {@code Password1!}，强度没变，记不住的概率上升。</p>
 *
 * <p>管理员预建账号不走这里：内部账号的初始密码由管理员设定并要求首次登录改密，
 * 与公网自助注册是两种威胁模型。</p>
 * @author owlzhangfq@gmail.com
 */
public final class PasswordPolicy {

    /** 自助注册的最小长度，比 RegisterRequest 早期的 6 位收紧。 */
    public static final int MIN_LENGTH = 8;

    /** 撞库字典里命中率最高的一批，单独挡掉——它们全都满足"字母+数字"。 */
    private static final Set<String> WEAK_PASSWORDS = Set.of(
        "12345678", "123456789", "1234567890", "password", "password1", "passw0rd",
        "qwerty123", "abc12345", "a1234567", "admin123", "administrator", "iloveyou",
        "1qaz2wsx", "qazwsx123", "zxcvbnm123", "11111111", "88888888");

    private PasswordPolicy() {
    }

    /** 是否满足自助注册的强度要求。 */
    public static boolean isStrongEnough(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return false;
        }
        if (WEAK_PASSWORDS.contains(password.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
            if (hasLetter && hasDigit) {
                return true;
            }
        }
        return false;
    }
}
