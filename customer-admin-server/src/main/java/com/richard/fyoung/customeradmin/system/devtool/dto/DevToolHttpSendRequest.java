package com.richard.fyoung.customeradmin.system.devtool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 开发者工具箱 HTTP 请求工具的发送入参：一次任意目标地址的 HTTP(S) 调用描述。
 *
 * <p>headers 用键值对列表而非 Map：请求头允许同名重复（如多个 Cookie），且与前端行编辑器的
 * 数据形态天然一致。body 为原始文本，Content-Type 由前端按 Body 类型放进 headers 一并传入，
 * 后端不单独建模。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class DevToolHttpSendRequest {

    /** 请求方法，仅支持常用七种（大写）。 */
    @NotBlank(message = "请求方法不能为空")
    @Pattern(regexp = "GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS", message = "不支持的请求方法")
    private String method;

    /** 目标地址，仅支持 http/https。 */
    @NotBlank(message = "URL 不能为空")
    @Size(max = 4096, message = "URL 过长")
    private String url;

    /** 请求头列表，允许同名重复。 */
    @Valid
    private List<HeaderItem> headers = new ArrayList<>();

    /** 请求体原始文本；GET/HEAD/OPTIONS 请求忽略该字段。 */
    @Size(max = 1_048_576, message = "请求体不能超过 1MB")
    private String body;

    /** 单个请求头键值对。 */
    @Data
    public static class HeaderItem {

        @NotBlank(message = "请求头名称不能为空")
        @Size(max = 256, message = "请求头名称过长")
        private String name;

        @Size(max = 8192, message = "请求头值过长")
        private String value;
    }
}
