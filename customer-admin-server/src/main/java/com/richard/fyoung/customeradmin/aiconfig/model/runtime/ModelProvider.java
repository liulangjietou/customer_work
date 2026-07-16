package com.richard.fyoung.customeradmin.aiconfig.model.runtime;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;

import java.util.Arrays;

/**
 * 支持的模型厂商枚举。集中承载厂商编码，并做未知厂商的 fast fail 收口（{@link #of(String)}），
 * 避免 provider 字符串在 {@link AdminModelFactory} 里散落硬编码判断。
 *
 * <p>baseUrl 必填由 {@code ModelSaveRequest} 的 {@code @NotBlank} 收口（防御一处），
 * 各厂商默认 Base URL 的预填由前端 {@code ModelManage.vue} 的 providerPresets 负责，后端不重复兜底。</p>
 * @author owlzhangfq@gmail.com
 */
public enum ModelProvider {

    /** OpenAI 及所有 OpenAI 兼容端点（默认厂商）。 */
    OPENAI("openai"),
    /** 阿里云百炼 DashScope 原生协议（通义千问）。 */
    DASHSCOPE("dashscope"),
    /** Anthropic Claude 原生协议。 */
    ANTHROPIC("anthropic"),
    /** Google Gemini（Gemini Developer API，非 Vertex）。 */
    GEMINI("gemini");

    private final String code;

    ModelProvider(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 按 provider 编码解析枚举；未知（含空）编码一律 fast fail，作为唯一的 provider 合法性收口点。
     */
    public static ModelProvider of(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase();
        return Arrays.stream(values())
            .filter(p -> p.code.equals(normalized))
            .findFirst()
            .orElseThrow(() -> new BizException(ResultCode.MODEL_PROVIDER_NOT_SUPPORTED,
                "暂不支持的模型 provider: " + code));
    }
}
