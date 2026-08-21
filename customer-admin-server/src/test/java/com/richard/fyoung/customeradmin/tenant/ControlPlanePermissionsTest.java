package com.richard.fyoung.customeradmin.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlanePermissionsTest {

    @Test
    void shouldRecognizeControlPlanePermissionFamiliesAndExactCodes() {
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("tenant:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("menu"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("login-image:edit"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("system-tool:view"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("config-version:rollback"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("billing:quota-edit"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("billing:price-edit"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("billing:export"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("sensitive-word:add"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("sensitive-word:edit"));
        assertTrue(ControlPlanePermissions.isControlPlaneOnly("sensitive-word:delete"));
    }

    @Test
    void shouldKeepTenantSelfServicePermissionsGrantable() {
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("billing:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("sensitive-word:view"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly("role:edit"));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly(null));
        assertFalse(ControlPlanePermissions.isControlPlaneOnly(""));
    }
}
