package com.richard.fyoung.customeradmin.system.menu.controller;

import com.richard.fyoung.customeradmin.system.menu.service.MenuIconStorageService;
import com.richard.fyoung.customeradmin.system.permission.service.PermissionService;
import com.richard.fyoung.customerwork.data.attachment.AttachmentFileStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuIconControllerTest {

    @Test
    void storageNamespace_shouldNotAcceptPrivateAttachmentKeys() {
        MenuIconStorageService storageService = new MenuIconStorageService(mock(AttachmentFileStorage.class));

        assertTrue(storageService.ownsKey(
            "202608/menu-icon-12345678-1234-1234-1234-123456789abc.svg"));
        assertFalse(storageService.ownsKey("202608/12345678123412341234123456789abc.pdf"));
        assertFalse(storageService.ownsKey("../menu-icon-12345678-1234-1234-1234-123456789abc.svg"));
    }

    @Test
    void svgResponse_shouldBeSandboxedAgainstSameOriginScriptExecution() throws Exception {
        MenuIconStorageService storageService = mock(MenuIconStorageService.class);
        PermissionService permissionService = mock(PermissionService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        byte[] svg = "<svg><script>alert(1)</script></svg>".getBytes();
        when(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE))
            .thenReturn("/api/menu-icons/202608/menu-icon-12345678-1234-1234-1234-123456789abc.svg");
        String key = "202608/menu-icon-12345678-1234-1234-1234-123456789abc.svg";
        when(storageService.ownsKey(key)).thenReturn(true);
        when(storageService.read(key)).thenReturn(svg);

        ResponseEntity<byte[]> response = new MenuIconController(storageService, permissionService).icon(request);

        assertEquals(MediaType.parseMediaType("image/svg+xml"), response.getHeaders().getContentType());
        assertEquals("default-src 'none'; sandbox",
            response.getHeaders().getFirst("Content-Security-Policy"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertArrayEquals(svg, response.getBody());
    }

    @Test
    void arbitrarySharedBucketKey_shouldNotBeReadableThroughPublicMenuRoute() throws Exception {
        MenuIconStorageService storageService = mock(MenuIconStorageService.class);
        PermissionService permissionService = mock(PermissionService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        String key = "202608/private-chat-attachment.pdf";
        when(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE))
            .thenReturn("/api/menu-icons/" + key);

        ResponseEntity<byte[]> response = new MenuIconController(storageService, permissionService).icon(request);

        assertEquals(404, response.getStatusCode().value());
        verify(storageService, never()).read(key);
    }

    @Test
    void referencedLegacyMenuIcon_shouldRemainReadable() throws Exception {
        MenuIconStorageService storageService = mock(MenuIconStorageService.class);
        PermissionService permissionService = mock(PermissionService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        String key = "202608/legacy-icon.png";
        byte[] image = new byte[] {1, 2, 3};
        when(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE))
            .thenReturn("/api/menu-icons/" + key);
        when(permissionService.isReferencedImageUrl("/api/menu-icons/" + key)).thenReturn(true);
        when(storageService.read(key)).thenReturn(image);

        ResponseEntity<byte[]> response = new MenuIconController(storageService, permissionService).icon(request);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(image, response.getBody());
    }
}
