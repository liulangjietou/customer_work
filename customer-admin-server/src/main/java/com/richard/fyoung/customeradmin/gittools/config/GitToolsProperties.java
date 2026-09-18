package com.richard.fyoung.customeradmin.gittools.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 只读 Git 仓库检索 MCP 端点参数：{@code admin.gittools.*}。
 *
 * <p><b>默认关闭</b>。本类不带 {@code @Component}，只由 {@link GitToolsConfiguration} 在
 * {@code enabled=true} 时注册——下面的 {@code @NotBlank} 因此只在启用后才校验，
 * 未启用的实例不需要为它配任何值；启用却漏配时启动即失败（fast fail），
 * 而不是等第一个 MCP 请求进来才暴露。</p>
 *
 * <p>token 只从外部配置注入，不写入仓库。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Validated
@ConfigurationProperties(prefix = "admin.gittools")
public class GitToolsProperties {

    /** 是否启用 gittools MCP 端点。 */
    private boolean enabled = false;

    /** 仓库托管平台：github / gitlab。 */
    @NotBlank
    private String provider = "github";

    /** 固定仓库地址，如 https://github.com/owner/repo；服务只允许访问这一个仓库。 */
    @NotBlank
    private String repository;

    /** Git API 基地址；留空时 GitHub 用 https://api.github.com，GitLab 由仓库地址推导 /api/v4。 */
    private String apiBaseUrl;

    /** 访问 GitHub/GitLab API 的出站 token。 */
    @NotBlank
    private String token;

    /** MCP 客户端调用本端点的入站 Bearer Token，不与后台登录、Open API、A2A 令牌复用。 */
    @NotBlank
    private String serverToken;

    /** 工具未指定 ref 时使用的默认分支或标签。 */
    private String ref = "main";

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(20);

    /** 单次 Git API 响应体上限（字节）。 */
    @Min(1024)
    private int maxResponseBytes = 2 * 1024 * 1024;

    /** 单个文件内容上限（字节）。 */
    @Min(1024)
    private int maxFileBytes = 512 * 1024;
}
