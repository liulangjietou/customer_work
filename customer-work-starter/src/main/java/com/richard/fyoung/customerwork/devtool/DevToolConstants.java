package com.richard.fyoung.customerwork.devtool;

/**
 * 开发者工具箱公共字面量。
 *
 * <p>编码名是<b>对外接口契约</b>（前端下拉框传什么、后端认什么），JWT 与编解码两个工具必须认同一套；
 * 此前各写一份，加一种编码只改一处的话，另一个工具会把它当非法值拒掉。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class DevToolConstants {

    /** 时间展示与 cron 推演的默认时区。 */
    public static final String DEFAULT_ZONE = "Asia/Shanghai";

    public static final String ENCODING_UTF8 = "UTF8";
    public static final String ENCODING_HEX = "HEX";
    public static final String ENCODING_BASE64 = "BASE64";

    private DevToolConstants() {
    }
}
