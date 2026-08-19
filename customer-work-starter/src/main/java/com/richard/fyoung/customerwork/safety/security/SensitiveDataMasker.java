package com.richard.fyoung.customerwork.safety.security;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.richard.fyoung.customerwork.infra.config.properties.HooksProperties;

/**
 * 敏感信息脱敏器（手机号 / 身份证 / 银行卡 / 邮箱 + 自定义正则）。
 *
 * <p>由 {@code MaskingHook}（出站回复脱敏）与 {@code AuditHook}（审计入参脱敏）共用。规则从
 * {@code customer-work.hooks.masking.*} 读取并预编译；脱敏只做正则替换，不改变文本其余部分。</p>
 *
 * <p>注意：脱敏只用于<b>对外输出</b>与<b>审计记录</b>，<b>不</b>用于改写工具真实入参
 * （那会破坏 orderId 等业务参数），保证功能正确性。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SensitiveDataMasker {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataMasker.class);

    private final List<Pattern> patterns = new ArrayList<>();
    private final String replacement;

    public SensitiveDataMasker(CustomerWorkProperties properties) {
        HooksProperties.Masking cfg = properties.getHooks().getMasking();
        this.replacement = cfg.getReplacement();
        // 先长后短，避免身份证/银行卡被手机号规则部分吃掉
        if (cfg.isMaskIdCard()) {
            patterns.add(Pattern.compile("(?<!\\d)(\\d{17}[\\dXx]|\\d{15})(?!\\d)"));
        }
        if (cfg.isMaskBankCard()) {
            patterns.add(Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)"));
        }
        if (cfg.isMaskPhone()) {
            patterns.add(Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"));
        }
        if (cfg.isMaskEmail()) {
            patterns.add(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
        }
        for (String extra : cfg.getExtraPatterns()) {
            try {
                patterns.add(Pattern.compile(extra));
            } catch (Exception e) {
                log.error("[MASK] invalid custom regex ignored, code={}, regex={}", "MASKING_PATTERN_INVALID", extra, e);
            }
        }
    }

    /** 对文本做脱敏；null / 空串原样返回。 */
    public String mask(String text) {
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return text;
        }
        String result = text;
        for (Pattern p : patterns) {
            result = p.matcher(result).replaceAll(replacement);
        }
        return result;
    }

    /** 当前是否配置了任何脱敏规则。 */
    public boolean hasRules() {
        return !patterns.isEmpty();
    }
}
