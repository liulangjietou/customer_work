package com.richard.fyoung.customeradmin.tenant;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.controller.SystemToolController;
import com.richard.fyoung.customeradmin.billing.controller.BillingController;
import com.richard.fyoung.customeradmin.configversion.controller.ConfigVersionController;
import com.richard.fyoung.customeradmin.contentguard.controller.SensitiveWordController;
import com.richard.fyoung.customeradmin.system.loginimage.controller.LoginImageAdminController;
import com.richard.fyoung.customeradmin.system.menu.controller.MenuAdminController;
import com.richard.fyoung.customeradmin.system.permission.controller.PermissionController;
import com.richard.fyoung.customeradmin.tenant.controller.TenantController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 控制面身份门禁之外，接口原有权限点也必须继续存在。 */
class ControlPlanePermissionContractTest {

    @Test
    void controlPlaneEndpoints_shouldKeepPermissionPointAnnotations() {
        List<Contract> contracts = List.of(
            contract(TenantController.class, "page", "tenant:view"),
            contract(TenantController.class, "get", "tenant:view"),
            contract(TenantController.class, "options", "tenant:view"),
            contract(TenantController.class, "create", "tenant:add"),
            contract(TenantController.class, "update", "tenant:edit"),
            contract(TenantController.class, "changeStatus", "tenant:edit"),
            contract(TenantController.class, "delete", "tenant:delete"),
            contract(TenantController.class, "switchView", "tenant:view"),
            contract(BillingController.class, "listQuota", "billing:view"),
            contract(BillingController.class, "saveQuota", "billing:quota-edit"),
            contract(BillingController.class, "deleteQuota", "billing:quota-edit"),
            contract(BillingController.class, "listPrice", "billing:view"),
            contract(BillingController.class, "createPrice", "billing:price-edit"),
            contract(BillingController.class, "deletePrice", "billing:price-edit"),
            contract(BillingController.class, "tenantBill", "billing:view"),
            contract(BillingController.class, "platformOverview", "billing:view"),
            contract(BillingController.class, "aggregate", "billing:export"),
            contract(ConfigVersionController.class, "page", "config-version:view"),
            contract(ConfigVersionController.class, "detail", "config-version:view"),
            contract(ConfigVersionController.class, "listByTarget", "config-version:view"),
            contract(ConfigVersionController.class, "rollback", "config-version:rollback"),
            contract(ConfigVersionController.class, "grayRelease", "config-version:gray"),
            contract(SensitiveWordController.class, "create", "sensitive-word:add"),
            contract(SensitiveWordController.class, "update", "sensitive-word:edit"),
            contract(SensitiveWordController.class, "toggle", "sensitive-word:edit"),
            contract(SensitiveWordController.class, "delete", "sensitive-word:delete"),
            contract(SensitiveWordController.class, "importWords", "sensitive-word:add"),
            contract(PermissionController.class, "create", "role:edit"),
            contract(PermissionController.class, "update", "role:edit"),
            contract(PermissionController.class, "delete", "role:edit"),
            contract(MenuAdminController.class, "create", "menu:add"),
            contract(MenuAdminController.class, "update", "menu:edit"),
            contract(MenuAdminController.class, "delete", "menu:delete"),
            contract(MenuAdminController.class, "reorder", "menu:edit"),
            contract(MenuAdminController.class, "publish", "menu:edit"),
            contract(MenuAdminController.class, "uploadIcon", "menu:edit"),
            contract(LoginImageAdminController.class, "upload", "login-image:add"),
            contract(LoginImageAdminController.class, "updateEnabled", "login-image:edit"),
            contract(LoginImageAdminController.class, "reorder", "login-image:edit"),
            contract(LoginImageAdminController.class, "delete", "login-image:delete"),
            contract(SystemToolController.class, "update", "system-tool:edit")
        );

        for (Contract contract : contracts) {
            Method[] methods = Arrays.stream(contract.controller().getDeclaredMethods())
                .filter(method -> method.getName().equals(contract.method()))
                .toArray(Method[]::new);
            assertEquals(1, methods.length, contract.controller().getSimpleName() + "#" + contract.method());
            SaCheckPermission annotation = methods[0].getAnnotation(SaCheckPermission.class);
            assertNotNull(annotation, contract.controller().getSimpleName() + "#" + contract.method());
            assertArrayEquals(new String[]{contract.permission()}, annotation.value(),
                contract.controller().getSimpleName() + "#" + contract.method());
        }
    }

    private Contract contract(Class<?> controller, String method, String permission) {
        return new Contract(controller, method, permission);
    }

    private record Contract(Class<?> controller, String method, String permission) {
    }
}
