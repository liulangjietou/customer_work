package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.richard.fyoung.customeradmin.common.exception.GlobalExceptionHandler;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.controller.CsatBoardController;
import com.richard.fyoung.customeradmin.ops.service.OpsAdminService;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.capability.csat.CsatSurvey;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实运营库和租户插件验证查询默认分区与边界；不替代 Sa-Token 过滤链授权验收。 */
class AdminCsatAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "csat-acceptance";
    private static String ownDatabase;
    private static OpsGatewayProvider gateway;
    private static OpsAdminService service;
    private MockMvc mvc;

    @BeforeAll
    static void initializeOwnDatabase() throws Exception {
        try (var socket = new Socket()) { socket.connect(new InetSocketAddress(HOST, PORT), 500); }
        catch (Exception unavailable) { assumeTrue(false, "MySQL 不可达，无法验证真实运营分区"); }
        String candidate = "admin_csat_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            ownDatabase = candidate;
        }
        var properties = new CustomerWorkDbProperties();
        properties.setHost(HOST);
        properties.setPort(PORT);
        properties.setUsername(USER);
        properties.setPassword(PASSWORD);
        properties.setDatabase(ownDatabase);
        var tenancy = new AdminTenantProperties();
        tenancy.setEnabled(true);
        gateway = new OpsGatewayProvider(properties, new AdminCrossDbTenantPlugins(tenancy));
        gateway.get();
        service = new OpsAdminService(gateway);
        for (String tenant : List.of(TENANT, "other-tenant", "CSAT-ACCEPTANCE")) {
            String prefix = TENANT.equals(tenant) ? tenant : "foreign-" + tenant;
            TenantContext.runWith(tenant, () -> {
                gateway.get().csat().save(CsatSurvey.invited(prefix + "-own", tenant, 20L).withScore(5, tenant, 30L));
                gateway.get().csat().save(CsatSurvey.invited(prefix + "-shared", "shared", 20L).withScore(2, tenant, 30L));
            });
        }
    }

    @BeforeEach
    void bindTenantAndController() {
        TenantContext.set(TENANT);
        mvc = MockMvcBuilders.standaloneSetup(new CsatBoardController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @AfterAll
    static void removeOwnDatabase() throws Exception {
        if (gateway != null) gateway.close();
        if (ownDatabase != null) {
            try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE " + ownDatabase);
            }
        }
    }

    @Test
    void omittedScopeUsesCurrentTenantForBothSummaryAndList() throws Exception {
        mvc.perform(get("/api/ops/csat/summary").accept(org.springframework.http.MediaType.APPLICATION_JSON).param("windowStartMs", "0").param("windowEndMs", "100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answered").value(1)).andExpect(jsonPath("$.data.csat").value(1.0));
        mvc.perform(get("/api/ops/csat/list").accept(org.springframework.http.MediaType.APPLICATION_JSON).param("windowStartMs", "0").param("windowEndMs", "100"))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].sessionId").value(TENANT + "-own"));
    }

    @Test
    void explicitScopeCannotExposeAnotherTenantOrCaseCollision() {
        assertEquals(List.of(), service.csatSurveys("other-tenant", 0L, 100L));
        var ownRows = service.csatSurveys("shared", 0L, 100L);
        assertEquals(1, ownRows.size());
        assertEquals(TENANT + "-shared", ownRows.get(0).sessionId());
    }

    @Test
    void genuinelyEmptyWindowReturnsSuccessfulZeroSummary() throws Exception {
        mvc.perform(get("/api/ops/csat/summary").accept(org.springframework.http.MediaType.APPLICATION_JSON).param("scopeId", TENANT)
                .param("windowStartMs", "100").param("windowEndMs", "200"))
            .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.invited").value(0));
    }

    private static String url(String database) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + database
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }
}
