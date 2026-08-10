package com.richard.fyoung.customerwork.devtool;

import org.springframework.util.StringUtils;

/**
 * 开发者工具箱纯函数入口的参数防御工具（fast-fail 单一收口点）。
 *
 * <p>整条工具链只在 Ops 方法入口做一次参数校验，实现内部不再层层判空。参数非法一律抛
 * {@link IllegalArgumentException}，异常信息带精确中文说明，供上层 {@code DevToolboxTools}
 * 收敛为可自我纠正的 error JSON 回给 LLM。</p>
 *
 * @author owlzhangfq@gmail.com
 */
final class DevToolArgs {

    private DevToolArgs() {
    }

    /** 校验字符串非空白（null/空串/纯空格均非法），返回原值以便链式使用。 */
    static String requireNonBlank(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    /** 校验对象非 null（允许空字符串，如 base64/url 编码空串是合法输入）。 */
    static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为 null");
        }
        return value;
    }
}
