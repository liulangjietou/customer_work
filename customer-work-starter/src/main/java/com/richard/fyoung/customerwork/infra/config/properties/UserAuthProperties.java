package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 终端用户账户鉴权配置。
 *
 * <p>{@code store-mode} 决定 {@code UserAccountService} 的账户持久化方式；{@code jwt-secret} /
 * {@code jwt-expire-hours} 供上层签发用户登录态令牌（starter 只提供账户与密码校验能力，令牌签发在接入层）。</p>
 */
@Data
public class UserAuthProperties {
    /** 存储模式：memory（进程内，默认）| jdbc（跨实例共享）。 */
    private String storeMode = "memory";
    /** 用户登录态 JWT 签名密钥（生产必须用环境变量注入覆盖）。 */
    private String jwtSecret = "";
    /** 用户登录态有效期（小时，默认 7 天）。 */
    private int jwtExpireHours = 168;
    /** 用户头像上传配置。 */
    private final Avatar avatar = new Avatar();

    /**
     * 用户头像上传配置。
     *
     * <p>头像本体存 MinIO（走 {@code AttachmentFileStorage} SPI，项目内不落盘），故这里没有目录配置。
     * {@code maxSizeBytes} 为单文件大小上限（超过即中断，默认 2MB）；{@code urlPrefix} 为对外访问 URL 前缀，
     * 落在 {@code /api} 下以复用前端 Vite 代理规则。</p>
     */
    @Data
    public static class Avatar {
        /** 单文件大小上限（字节，默认 2MB）。 */
        private long maxSizeBytes = 2L * 1024 * 1024;
        /** 对外访问 URL 前缀（须以 / 开头和结尾）。 */
        private String urlPrefix = "/api/avatars/";
    }
}
