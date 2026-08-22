package com.richard.fyoung.customeradmin.aiconfig.mcp.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpSaveRequest;
import com.richard.fyoung.customeradmin.common.log.OperationLog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** MCP 配置详情与有副作用调试调用的权限边界契约。 */
class McpControllerSecurityContractTest {

    @Test
    void configDetail_shouldRequireEdit_whilePageOnlyRequiresView() {
        assertPermission("page", "mcp:view");
        assertPermission("get", "mcp:edit");
    }

    @Test
    void debugToolsMayRemainReadOnly_butToolCallRequiresEditAndAudit() {
        assertPermission("debugTools", "mcp:view");
        assertPermission("debugCall", "mcp:edit");
        assertNotNull(method("debugCall").getAnnotation(OperationLog.class));
    }

    @Test
    void saveRequestSerialization_shouldNotLeakConfigIntoOperationLog() throws Exception {
        McpSaveRequest request = new McpSaveRequest("secure", "http",
            "{\"headers\":{\"Authorization\":\"Bearer audit-secret\"}}", null, 1);

        String serialized = new ObjectMapper().writeValueAsString(request);

        assertFalse(serialized.contains("audit-secret"));
        assertFalse(serialized.contains("config"));
    }

    private void assertPermission(String methodName, String permission) {
        SaCheckPermission annotation = method(methodName).getAnnotation(SaCheckPermission.class);
        assertNotNull(annotation, methodName);
        assertArrayEquals(new String[]{permission}, annotation.value(), methodName);
    }

    private Method method(String methodName) {
        Method[] methods = Arrays.stream(McpController.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(methodName))
            .toArray(Method[]::new);
        assertEquals(1, methods.length, methodName);
        return methods[0];
    }
}
