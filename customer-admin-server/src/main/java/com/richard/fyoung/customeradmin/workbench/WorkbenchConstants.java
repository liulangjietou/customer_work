package com.richard.fyoung.customeradmin.workbench;

/**
 * 内网工作台常量：自动登录配置的枚举值与默认值、个人访问令牌前缀。
 * 集中在此避免选择器/模式字符串魔法值散落各处。
 * @author owlzhangfq@gmail.com
 */
public final class WorkbenchConstants {

    private WorkbenchConstants() {
    }

    // 填充模式
    public static final String FILL_MODE_AUTO = "auto";
    public static final String FILL_MODE_TYPING = "typing";

    // 提交方式
    public static final String SUBMIT_MODE_CLICK = "click";
    public static final String SUBMIT_MODE_FORM = "formSubmit";

    // 自动登录时序默认值（毫秒）
    public static final int DEFAULT_INIT_DELAY_MS = 500;
    public static final int DEFAULT_SUBMIT_DELAY_MS = 300;

    // 个人访问令牌
    public static final String TOKEN_PREFIX = "wbt_";
    /** 令牌前缀展示长度（含 wbt_，如 wbt_ab12cd34）。 */
    public static final int TOKEN_DISPLAY_PREFIX_LEN = 12;
}
