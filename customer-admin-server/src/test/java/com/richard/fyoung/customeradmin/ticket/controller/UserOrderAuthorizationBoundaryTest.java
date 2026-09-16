package com.richard.fyoung.customeradmin.ticket.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaTokenContextForSpringInJakartaServlet;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customeradmin.ticket.client.CustomerWorkTicketClient;
import com.richard.fyoung.customeradmin.ticket.config.CurrentAgentResolver;
import com.richard.fyoung.customeradmin.ticket.config.CustomerWorkClientConfig;
import com.richard.fyoung.customeradmin.ticket.config.CustomerWorkClientProperties;
import com.richard.fyoung.customeradmin.ticket.service.UserOrderService;
import com.richard.fyoung.customerwork.safety.security.AgentAccessCredential;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实 Sa-Token、订单服务和 HTTP 签名客户端；上游仅记录网络边界，不代替 APP 的真实 SQL 测试。 */
class UserOrderAuthorizationBoundaryTest {
    private static final String PATH = "/api/ticket/orders";
    private static final String SECRET = "owned-order-boundary-secret";
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private HttpServer server;
    private int upstreamStatus = 200;
    private SaTokenConfig previousConfig;
    private SaTokenDao previousDao;
    private SaTokenContext previousContext;
    private StpInterface previousPermissions;
    private StpLogic previousLogic;
    private SaTokenDaoDefaultImpl testDao;
    private MockMvc mvc;
    private String token;
    private List<String> permissions;

    @BeforeEach
    void setUp() throws Exception {
        previousConfig = SaManager.getConfig();
        previousDao = SaManager.getSaTokenDao();
        previousContext = SaManager.getSaTokenContext();
        previousPermissions = SaManager.getStpInterface();
        previousLogic = StpUtil.getStpLogic();
        SaManager.setConfig(new SaTokenConfig().setTokenName("Authorization").setIsReadCookie(false));
        testDao = new SaTokenDaoDefaultImpl();
        SaManager.setSaTokenDao(testDao);
        SaManager.setSaTokenContext(new SaTokenContextForSpringInJakartaServlet());
        permissions = List.of("user-order:view", "user-order:edit");
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return permissions;
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return List.of();
            }
        });
        StpUtil.setStpLogic(new StpLogic(previousLogic.getLoginType()));
        token = StpUtil.getStpLogic().createLoginSession("42");
        TenantContext.set("tenant-a");
        StpUtil.getTokenSessionByToken(token).set("username", "signed-seat");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/customer/agent/orders", exchange -> {
            requests.incrementAndGet();
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Agent-Token"));
            receivedPath.set(exchange.getRequestURI().toString());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"total\":0,\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        var properties = new CustomerWorkClientProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAgentSecret(SECRET);
        var restClient = new CustomerWorkClientConfig().customerWorkRestClient(properties, new CurrentAgentResolver());
        var service = new UserOrderService(new CustomerWorkTicketClient(restClient));
        mvc = MockMvcBuilders.standaloneSetup(new UserOrderController(service))
            .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addInterceptors(new SaInterceptor(handler -> StpUtil.checkLogin())).build();
    }

    @AfterEach
    void restoreGlobalState() {
        if (server != null) server.stop(0);
        TenantContext.clear();
        StpUtil.setStpLogic(previousLogic);
        SaManager.setStpInterface(previousPermissions);
        SaManager.setSaTokenContext(previousContext);
        SaManager.setSaTokenDao(previousDao);
        SaManager.setConfig(previousConfig);
        testDao.destroy();
    }

    @Test
    void anonymousRequestsCannotReachTheCustomerService() throws Exception {
        mvc.perform(get(PATH + "/page")).andExpect(jsonPath("$.code").value(10001));
        assertEquals(0, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/page", "/A-OWN"})
    void editPermissionDoesNotGrantReadPermission(String path) throws Exception {
        permissions = List.of("user-order:edit");
        mvc.perform(get(PATH + path).header("Authorization", token))
            .andExpect(jsonPath("$.code").value(20001));
        assertEquals(0, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"modify-address", "cancel"})
    void readOnlySeatCannotSendMutationRequests(String action) throws Exception {
        permissions = List.of("user-order:view");
        mvc.perform(post(PATH + "/A-OWN/" + action).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"newAddress\":\"新地址\",\"reason\":\"取消\"}"))
            .andExpect(jsonPath("$.code").value(20001));
        assertEquals(0, requests.get());
    }

    @Test
    void queryUsesServerLoginAndTenantAndPreservesPagination() throws Exception {
        long before = System.currentTimeMillis();
        mvc.perform(get(PATH + "/page").header("Authorization", token).header("X-Tenant-Id", "tenant-b")
                .param("agentId", "forged-seat").param("tenantId", "tenant-b")
                .param("pageNum", "2").param("pageSize", "7").param("userId", "owner"))
            .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(0));
        var identity = AgentAccessCredential.verifyIdentity(receivedToken.get(), SECRET, System.currentTimeMillis()).orElseThrow();
        assertEquals("signed-seat", identity.agentId());
        assertEquals("tenant-a", identity.tenantId());
        assertTrue(identity.expiresAtMs() >= before + 119_000);
        assertTrue(identity.expiresAtMs() <= System.currentTimeMillis() + 120_000);
        assertTrue(receivedPath.get().contains("page=2"));
        assertTrue(receivedPath.get().contains("size=7"));
        assertTrue(receivedPath.get().contains("userId=owner"));
        assertEquals(1, requests.get());
    }

    @Test
    void editSeatSignsActualAddressMutation() throws Exception {
        permissions = List.of("user-order:edit");
        mvc.perform(post(PATH + "/A-OWN/modify-address").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"newAddress\":\"新地址\"}"))
            .andExpect(jsonPath("$.code").value(0));
        assertEquals("/api/customer/agent/orders/A-OWN/modify-address", receivedPath.get());
        assertTrue(receivedBody.get().contains("新地址"));
        assertEquals("tenant-a", AgentAccessCredential.verifyIdentity(receivedToken.get(), SECRET,
            System.currentTimeMillis()).orElseThrow().tenantId());
    }

    @ParameterizedTest
    @CsvSource({"404,40013", "409,40014", "503,40012"})
    void upstreamFailureCannotBecomeSuccessfulCancellation(int httpStatus, int expectedCode) throws Exception {
        upstreamStatus = httpStatus;
        mvc.perform(post(PATH + "/A-OWN/cancel").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"取消\"}"))
            .andExpect(jsonPath("$.code").value(expectedCode));
        assertEquals(1, requests.get());
    }
}
