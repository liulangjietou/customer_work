package com.richard.fyoung.customerwork.safety.security.spotlight;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户消息里的<b>附件解析文本</b>重新包装成隔离块。
 *
 * <p><b>要解决的问题</b>：前端（H5 的 {@code Chat.vue} 与后台的 {@code ChatPanel.vue}）会把附件的
 * 解析结果按固定格式拼进消息正文再发出去，服务端收到的是一整串文本。于是 OCR 出来的内容以
 * "用户本人亲手打的字"的身份进入模型上下文，完全绕开了 {@link ContentSpotlighter} 的隔离——
 * 而该机制本来就是为这类不可信内容造的，只是当时只接了工具结果与知识库召回两条入口。</p>
 *
 * <p><b>为什么在服务端按格式识别、而不是让前端传结构化字段</b>：附件拼接格式是两个前端共用的既有契约
 * （见 {@link #ATTACHMENT_BLOCK}），服务端据此还原边界即可，不必改动 HTTP/WS 的报文结构。
 * 代价是这个格式成了前后端契约的一部分——改前端拼接格式必须同步改这里的正则，
 * 因此两边都留了指向对方的注释。</p>
 *
 * <p><b>识别不到就原样返回</b>：用户手打的普通消息不受任何影响，零误伤。
 * 这是确定性的文本变换，不涉及模型判断。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class AttachmentTextSpotlighter {

    /**
     * 前端拼接的附件段格式（前后端契约）：
     * <pre>
     * 【附件：文件名】
     * ---
     * 解析出的正文
     * ---
     * </pre>
     * 对应前端 {@code buildMessageWithAttachments()}，两个前端同款实现。
     */
    private static final Pattern ATTACHMENT_BLOCK = Pattern.compile(
        "【附件：(?<name>[^】]*)】\\s*\\n-{3,}\\n(?<body>.*?)\\n-{3,}",
        Pattern.DOTALL);

    private AttachmentTextSpotlighter() {
    }

    /**
     * 把消息里的每一个附件段替换成隔离块；没有附件段则原样返回。
     *
     * @param userText 用户消息全文（可能含前端拼接的附件段）
     * @return 附件段已被隔离标记包裹的文本
     */
    public static String wrapAttachments(String userText) {
        if (!StringUtils.hasText(userText)) {
            return userText;
        }
        Matcher matcher = ATTACHMENT_BLOCK.matcher(userText);
        if (!matcher.find()) {
            return userText;
        }
        matcher.reset();
        StringBuilder out = new StringBuilder(userText.length() + 128);
        while (matcher.find()) {
            String name = matcher.group("name");
            String body = matcher.group("body");
            // 文件名也来自用户上传，一并放进块内，避免被拿来夹带指令
            String isolated = ContentSpotlighter.wrap(UntrustedSource.USER_ATTACHMENT,
                "文件名：" + name + "\n" + body);
            matcher.appendReplacement(out, Matcher.quoteReplacement(isolated));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
