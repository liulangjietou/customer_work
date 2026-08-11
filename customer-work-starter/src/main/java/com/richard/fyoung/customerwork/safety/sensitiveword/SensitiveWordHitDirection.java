package com.richard.fyoung.customerwork.safety.sensitiveword;

/**
 * 敏感词命中方向：{@code INBOUND} 用户输入命中，{@code OUTBOUND} AI 输出命中。
 *
 * <p>后台看板按此区分"用户在说什么"与"模型在说什么"——两者的运营处置完全不同：
 * 前者多是恶意用户，后者多是提示词或模型本身要调。</p>
 * @author owlzhangfq@gmail.com
 */
public enum SensitiveWordHitDirection {

    INBOUND, OUTBOUND
}
