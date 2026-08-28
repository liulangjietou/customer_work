package com.richard.fyoung.customeradmin.publicdeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对外开放实例上的内部运维工具闸门。
 *
 * <p>这三个前缀背后是"在已配数据源上执行任意 SQL""读出目标站点密码""以服务端身份发起
 * HTTP 请求"三类能力，破坏面与调用者是谁无关，因此对外实例上一律拒绝，超管也不例外。</p>
 */
class InternalToolGuardInterceptorTest {

    private InternalToolGuardInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new InternalToolGuardInterceptor(new ObjectMapper());
    }

    @Test
    void preHandle_shouldRejectInternalToolEndpoints() throws Exception {
        for (String uri : new String[] {
            "/api/sql/query/adhoc", "/api/sql/datasource", "/api/sql/define/list",
            "/api/workbench/site", "/api/workbench/token", "/api/workbench/agent/callback",
            "/api/devtools", "/api/devtools/http/send", "/api/devtools/cert/parse"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertFalse(interceptor.preHandle(request(uri), response, null), uri + " 应被拒绝");
            assertEquals(403, response.getStatus(), uri);
            assertTrue(response.getContentAsString()
                    .contains(String.valueOf(ResultCode.FEATURE_NOT_AVAILABLE.getCode())),
                uri + " 应返回功能未开放错误码");
        }
    }

    /**
     * 免登接口尤其要挡住。
     *
     * <p>{@code /api/workbench/agent/**} 在 Sa-Token 白名单里（ScriptCat 脚本回调走
     * X-Workbench-Token），对外实例上留着它等于在公网开了一条不需要登录态的入口。
     * 这也是本拦截器必须排在登录校验之前的原因。</p>
     */
    @Test
    void preHandle_shouldRejectAnonymousWorkbenchCallback() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request("/api/workbench/agent/site/1"), response, null));
        assertEquals(403, response.getStatus());
    }

    @Test
    void preHandle_shouldPassThroughUnrelatedEndpoints() throws Exception {
        for (String uri : new String[] {
            "/api/auth/login", "/api/aiconfig/agent/page", "/api/workspace/demo/chat/stream",
            "/api/menu/routes", "/api/system/user/page"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertTrue(interceptor.preHandle(request(uri), response, null), uri + " 不应被拦");
            assertEquals(200, response.getStatus(), uri);
        }
    }

    /**
     * 命中判定按路径段边界，不是裸的字符前缀。
     *
     * <p>裸前缀会把 {@code /api/sqlx/...} 这类相邻命名空间一起拦掉。今天没有这样的路径，
     * 但一个按字符前缀匹配的安全闸门迟早会误伤某个新接口，而误伤表现为 403，
     * 排查时很难第一眼联想到这道闸门。</p>
     */
    @Test
    void preHandle_shouldMatchOnPathSegmentBoundary() throws Exception {
        for (String passing : new String[] {
            "/api/sqlx/query", "/api/workbenchx/site", "/api/devtoolsxyz", "/api/sql-console/x"}) {
            assertTrue(interceptor.preHandle(request(passing), new MockHttpServletResponse(), null),
                passing + " 是相邻命名空间，不该被误伤");
        }
        // 命名空间本身（无子路径）同样要拦：DevToolCalcController 就直接挂在 /api/devtools 上
        for (String blockedUri : new String[] {"/api/devtools", "/api/sql", "/api/workbench"}) {
            assertFalse(interceptor.preHandle(request(blockedUri), new MockHttpServletResponse(), null),
                blockedUri + " 是内部工具命名空间本身，应被拦");
        }
    }

    private HttpServletRequest request(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }
}
