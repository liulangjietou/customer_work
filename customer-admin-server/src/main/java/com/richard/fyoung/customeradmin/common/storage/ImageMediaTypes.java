package com.richard.fyoung.customeradmin.common.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Locale;
import java.util.Map;

/**
 * 图片出图链路的两个小工具：从请求路径取存储 key、按扩展名判 Content-Type。
 *
 * <p>菜单图标与登录轮播图两条出图接口共用，逻辑只此一份。改造前这两条路径由
 * {@code StaticResourceConfig} 映射本地目录，Content-Type 由 Spring 的资源处理器推断；
 * 改走 Controller 出字节后需要自己给出——不给的话浏览器会按 {@code application/octet-stream}
 * 触发下载而不是渲染图片。</p>
 * @author owlzhangfq@gmail.com
 */
public final class ImageMediaTypes {

    private static final Map<String, MediaType> BY_EXTENSION = Map.of(
        "png", MediaType.IMAGE_PNG,
        "jpg", MediaType.IMAGE_JPEG,
        "jpeg", MediaType.IMAGE_JPEG,
        "gif", MediaType.IMAGE_GIF,
        "webp", MediaType.parseMediaType("image/webp"),
        "svg", MediaType.parseMediaType("image/svg+xml"));

    private ImageMediaTypes() {
    }

    /** 按 key 的扩展名判 Content-Type；未知扩展名回落二进制流（浏览器会下载而非渲染，但不会出错）。 */
    public static MediaType byExtension(String key) {
        int dot = key == null ? -1 : key.lastIndexOf('.');
        if (dot < 0 || dot == key.length() - 1) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return BY_EXTENSION.getOrDefault(
            key.substring(dot + 1).toLowerCase(Locale.ROOT), MediaType.APPLICATION_OCTET_STREAM);
    }

    /**
     * 从请求里取出 URL 前缀之后的存储 key。
     *
     * <p>key 形如 {@code 202608/{uuid}.png}（含斜杠），{@code @PathVariable} 遇斜杠会截断，
     * 故用 {@code /**} 通配后从 {@link HandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE}
     * 取完整路径再剥前缀。存量 key 无 {@code yyyyMM/} 前缀，同样走得通。</p>
     *
     * @param urlPrefix 形如 {@code /api/login-images/}
     */
    public static String extractKey(HttpServletRequest request, String urlPrefix) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (path == null) {
            path = request.getRequestURI();
        }
        int idx = path.indexOf(urlPrefix);
        return idx < 0 ? "" : path.substring(idx + urlPrefix.length());
    }
}
