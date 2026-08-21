package com.richard.fyoung.customeradmin.system.loginimage.controller;

import com.richard.fyoung.customeradmin.system.loginimage.service.LoginCarouselImageService;
import com.richard.fyoung.customeradmin.system.loginimage.service.LoginImageStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 登录页公开图片必须限定在轮播图自己的对象命名空间或存量精确引用内。 */
class LoginImagePublicControllerTest {

    @Test
    void arbitrarySharedBucketKey_shouldNotBeReadableThroughPublicLoginRoute() throws Exception {
        LoginCarouselImageService imageService = mock(LoginCarouselImageService.class);
        LoginImageStorageService storageService = mock(LoginImageStorageService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        String key = "202608/private-chat-attachment.pdf";
        when(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE))
            .thenReturn("/api/login-images/" + key);

        ResponseEntity<byte[]> response = new LoginImagePublicController(imageService, storageService).image(request);

        assertEquals(404, response.getStatusCode().value());
        verify(storageService, never()).read(key);
    }

    @Test
    void namespacedLoginImage_shouldBeReadableBeforeDatabaseInsert() throws Exception {
        LoginCarouselImageService imageService = mock(LoginCarouselImageService.class);
        LoginImageStorageService storageService = mock(LoginImageStorageService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        String key = "202608/login-image-12345678-1234-1234-1234-123456789abc.webp";
        byte[] image = new byte[] {1, 2, 3};
        when(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE))
            .thenReturn("/api/login-images/" + key);
        when(storageService.ownsKey(key)).thenReturn(true);
        when(storageService.read(key)).thenReturn(image);

        ResponseEntity<byte[]> response = new LoginImagePublicController(imageService, storageService).image(request);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(image, response.getBody());
    }

    @Test
    void referencedLegacyLoginImage_shouldRemainReadable() throws Exception {
        LoginCarouselImageService imageService = mock(LoginCarouselImageService.class);
        LoginImageStorageService storageService = mock(LoginImageStorageService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        String key = "202608/legacy-login.png";
        byte[] image = new byte[] {4, 5, 6};
        when(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE))
            .thenReturn("/api/login-images/" + key);
        when(imageService.isReferencedImageUrl("/api/login-images/" + key)).thenReturn(true);
        when(storageService.read(key)).thenReturn(image);

        ResponseEntity<byte[]> response = new LoginImagePublicController(imageService, storageService).image(request);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(image, response.getBody());
    }
}
