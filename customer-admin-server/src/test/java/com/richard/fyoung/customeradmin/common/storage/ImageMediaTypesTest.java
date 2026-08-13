package com.richard.fyoung.customeradmin.common.storage;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ImageMediaTypes} 单测：扩展名判 Content-Type、从请求路径剥前缀取 key。
 *
 * <p>两条都是改走 Controller 出图后新引入的责任（此前由 Spring 的静态资源处理器代劳），
 * 判错的后果是浏览器下载而不是渲染、或者图片 404。</p>
 * @author owlzhangfq@gmail.com
 */
class ImageMediaTypesTest {

    @Test
    void byExtension_shouldMapCommonImageTypes() {
        assertEquals(MediaType.IMAGE_PNG, ImageMediaTypes.byExtension("202608/a.png"));
        assertEquals(MediaType.IMAGE_JPEG, ImageMediaTypes.byExtension("a.jpg"));
        assertEquals(MediaType.IMAGE_JPEG, ImageMediaTypes.byExtension("a.JPEG"));
        assertEquals(MediaType.IMAGE_GIF, ImageMediaTypes.byExtension("a.gif"));
        assertEquals(MediaType.parseMediaType("image/webp"), ImageMediaTypes.byExtension("a.webp"));
        assertEquals(MediaType.parseMediaType("image/svg+xml"), ImageMediaTypes.byExtension("a.svg"));
    }

    @Test
    void byExtension_shouldFallBackToOctetStream() {
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, ImageMediaTypes.byExtension("noextension"));
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, ImageMediaTypes.byExtension("trailingdot."));
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, ImageMediaTypes.byExtension("a.unknown"));
    }

    @Test
    void extractKey_shouldStripPrefix_forNewKeyWithMonthFolder() {
        MockHttpServletRequest request = requestFor("/api/menu-icons/202608/uuid.png");

        assertEquals("202608/uuid.png", ImageMediaTypes.extractKey(request, "/api/menu-icons/"));
    }

    @Test
    void extractKey_shouldStripPrefix_forLegacyFlatKey() {
        // 存量 URL 没有 yyyyMM 前缀，同样要取得出来（否则老图全 404）
        MockHttpServletRequest request = requestFor("/api/menu-icons/uuid.png");

        assertEquals("uuid.png", ImageMediaTypes.extractKey(request, "/api/menu-icons/"));
    }

    @Test
    void extractKey_shouldFallBackToRequestUri_whenAttributeAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/login-images/202608/bg.jpg");

        assertEquals("202608/bg.jpg", ImageMediaTypes.extractKey(request, "/api/login-images/"));
    }

    private static MockHttpServletRequest requestFor(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, path);
        return request;
    }
}
