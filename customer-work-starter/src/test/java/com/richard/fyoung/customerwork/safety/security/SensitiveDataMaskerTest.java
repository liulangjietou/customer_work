package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏器单测：覆盖手机号/身份证/银行卡/邮箱及自定义正则，确保只替换命中片段。
 * @author owlzhangfq@gmail.com
 */
class SensitiveDataMaskerTest {

    private SensitiveDataMasker masker(java.util.function.Consumer<CustomerWorkProperties.Hooks.Masking> cfg) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        cfg.accept(props.getHooks().getMasking());
        return new SensitiveDataMasker(props);
    }

    @Test
    void shouldMaskPhoneIdCardBankCardEmail() {
        SensitiveDataMasker m = masker(c -> {});   // 默认全开
        assertEquals("联系 *** 处理", m.mask("联系 13800138000 处理"));
        assertTrue(m.mask("身份证 11010119900307721X 已核验").contains("***"));
        assertTrue(m.mask("卡号 6222021234567890123 退款").contains("***"));
        assertEquals("邮箱 *** 已记录", m.mask("邮箱 user.name@example.com 已记录"));
    }

    @Test
    void shouldKeepNonSensitiveTextIntact() {
        SensitiveDataMasker m = masker(c -> {});
        assertEquals("订单 20260613001 已发货", m.mask("订单 20260613001 已发货")); // 9 位订单号不算银行卡
    }

    @Test
    void shouldRespectPerTypeSwitches() {
        SensitiveDataMasker m = masker(c -> {
            c.setMaskPhone(false);
            c.setMaskBankCard(false);
            c.setMaskIdCard(false);
            c.setMaskEmail(false);
        });
        assertFalse(m.hasRules());
        assertEquals("13800138000", m.mask("13800138000"));
    }

    @Test
    void shouldSupportCustomReplacementAndExtraPattern() {
        SensitiveDataMasker m = masker(c -> {
            c.setReplacement("[已隐藏]");
            c.setExtraPatterns(List.of("SECRET-\\d+"));
        });
        assertEquals("token [已隐藏]", m.mask("token SECRET-123"));
    }

    @Test
    void shouldHandleNullAndEmpty() {
        SensitiveDataMasker m = masker(c -> {});
        assertEquals(null, m.mask(null));
        assertEquals("", m.mask(""));
    }
}
