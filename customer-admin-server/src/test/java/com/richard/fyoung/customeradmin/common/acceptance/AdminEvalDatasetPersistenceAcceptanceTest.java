package com.richard.fyoung.customeradmin.common.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.gateway.CustomerWorkDbProperties;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.config.EvalGateway;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customeradmin.eval.dto.EvalCaseSaveRequest;
import com.richard.fyoung.customeradmin.eval.dto.EvalDatasetImportRequest;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.tenant.AdminCrossDbTenantPlugins;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReviewStatus;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 评测工作集与版本治理的真实客服库验收；只操作本类创建的独立数据库。 */
class AdminEvalDatasetPersistenceAcceptanceTest {
    private static final String HOST = System.getenv().getOrDefault("MYSQL_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("MYSQL_PORT", "3306"));
    private static final String USER = System.getenv().getOrDefault("ADMIN_MYSQL_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("ADMIN_MYSQL_PASSWORD", "root");
    private static final String TENANT = "eval-acceptance";
    private static String database;
    private static JdbcTemplate jdbc;
    private static EvalGatewayProvider provider;
    private static EvalDatasetAdminService service;

    @BeforeAll
    static void initializeOwnDatabase() throws Exception {
        String candidate = "admin_eval_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + candidate);
            database = candidate;
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url(database), USER, PASSWORD));
        var tenant = new AdminTenantProperties();
        tenant.setEnabled(true);
        var properties = new CustomerWorkDbProperties();
        properties.setHost(HOST);
        properties.setPort(PORT);
        properties.setUsername(USER);
        properties.setPassword(PASSWORD);
        properties.setDatabase(database);
        provider = new EvalGatewayProvider(properties, new AdminCrossDbTenantPlugins(tenant));
        provider.dataset();
        service = new EvalDatasetAdminService(provider, new ObjectMapper());
    }

    @BeforeEach
    void prepareWorkingSet() {
        TenantContext.set(TENANT);
        jdbc.execute("DROP TRIGGER IF EXISTS reject_eval_import");
        jdbc.update("DELETE FROM cw_eval_case");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    static void removeOwnDatabase() throws Exception {
        if (provider != null) provider.close();
        if (database == null) return;
        try (var connection = DriverManager.getConnection(url(""), USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void workingSetRoundTripPreservesTenantAndRejectsForeignUpdates() {
        service.createCase(EvalType.QUALITY, request("own-case", "原问题"));
        service.updateCase(EvalType.QUALITY, "own-case", request("own-case", "修订问题"));
        assertEquals("修订问题", jdbc.queryForObject(
            "SELECT input FROM cw_eval_case WHERE case_id='own-case'", String.class));
        assertTrue(service.exportCases(EvalType.QUALITY).stream()
            .anyMatch(item -> item.caseId().equals("own-case") && item.input().equals("修订问题")));
        TenantContext.set("EVAL-ACCEPTANCE");
        assertFalse(service.listCases(EvalType.QUALITY).stream().anyMatch(item -> item.caseId().equals("own-case")));
        assertThrows(BizException.class, () -> service.updateCase(EvalType.QUALITY,
            "own-case", request("own-case", "不得覆盖")));
        TenantContext.set(TENANT);
        assertEquals("修订问题", provider.dataset().caseStore().find(EvalType.QUALITY, "own-case").orElseThrow().input());
        service.deleteCase(EvalType.QUALITY, "own-case");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cw_eval_case WHERE case_id='own-case'", Integer.class));
    }

    @Test
    void failedAtomicImportPreservesExistingRowsAndCanRetry() {
        service.createCase(EvalType.QUALITY, request("existing-case", "导入前内容"));
        jdbc.execute("CREATE TRIGGER reject_eval_import BEFORE INSERT ON cw_eval_case FOR EACH ROW "
            + "BEGIN IF NEW.case_id='reject-case' THEN SIGNAL SQLSTATE '45000' "
            + "SET MESSAGE_TEXT='acceptance import failure'; END IF; END");
        var request = new EvalDatasetImportRequest(List.of(
            request("existing-case", "不得部分更新"), request("reject-case", "第二条")));
        assertThrows(RuntimeException.class, () -> service.importCases(EvalType.QUALITY, request));
        assertEquals("导入前内容", jdbc.queryForObject(
            "SELECT input FROM cw_eval_case WHERE case_id='existing-case'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cw_eval_case WHERE case_id='reject-case'", Integer.class));
        jdbc.execute("DROP TRIGGER reject_eval_import");
        service.importCases(EvalType.QUALITY, request);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM cw_eval_case", Integer.class));
        assertEquals("不得部分更新", provider.dataset().caseStore().find(EvalType.QUALITY, "existing-case").orElseThrow().input());
    }

    @Test
    void immutableVersionRequiresAnotherReviewerAndRetainsOriginalSnapshot() {
        service.createCase(EvalType.QUALITY, request("version-case", "版本内原问题"));
        var version = asUser(7L, () -> service.createVersion(EvalType.QUALITY, "acceptance-" + UUID.randomUUID()));
        assertEquals(7L, version.createdBy());
        var snapshot = service.requireSnapshot(version.snapshotVersionId());
        assertTrue(snapshot.isContentIntact());
        assertThrows(BizException.class, () -> asUser(7L,
            () -> service.review(version.releaseId(), EvalDatasetReviewStatus.APPROVED, "不得自己审核")));
        assertThrows(BizException.class, () -> service.requireApprovedQualityRelease(version.releaseId()));
        var approved = asUser(8L,
            () -> service.review(version.releaseId(), EvalDatasetReviewStatus.APPROVED, "审核通过"));
        assertEquals(8L, approved.reviewedBy());
        assertEquals(EvalDatasetReviewStatus.APPROVED, service.requireApprovedQualityRelease(version.releaseId()).status());
        assertThrows(BizException.class, () -> asUser(9L,
            () -> service.review(version.releaseId(), EvalDatasetReviewStatus.REJECTED, "不可覆盖已有审核")));
        service.updateCase(EvalType.QUALITY, "version-case", request("version-case", "新工作集内容"));
        assertEquals(snapshot, service.requireSnapshot(version.snapshotVersionId()));
        TenantContext.set("EVAL-ACCEPTANCE");
        assertThrows(BizException.class, () -> service.requireApprovedQualityRelease(version.releaseId()));
    }

    @Test
    void concurrentNewCaseCreationRejectsDuplicateInsteadOfOverwriting() throws Exception {
        EvalGateway gateway = provider.dataset();
        EvalCaseStore synchronizedReads = spy(gateway.caseStore());
        var barrier = new CyclicBarrier(2);
        // 两个真实 SELECT 均完成后才允许继续，稳定复现查重与写入之间的竞争窗口。
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            barrier.await(10, TimeUnit.SECONDS);
            return result;
        }).when(synchronizedReads).findByType(EvalType.QUALITY);
        var synchronizedProvider = mock(EvalGatewayProvider.class);
        when(synchronizedProvider.dataset()).thenReturn(new EvalGateway(gateway.runStore(),
            synchronizedReads, gateway.snapshotStore(), gateway.releaseStore()));
        var concurrentService = new EvalDatasetAdminService(synchronizedProvider, new ObjectMapper());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> createConcurrentCase(concurrentService, "甲提交内容"));
            var second = executor.submit(() -> createConcurrentCase(concurrentService, "乙提交内容"));
            List<Boolean> outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1L, outcomes.stream().filter(Boolean::booleanValue).count(),
                "Concurrent POST requests must not silently overwrite the first created case");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cw_eval_case WHERE case_id='concurrent-case'", Integer.class));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private boolean createConcurrentCase(EvalDatasetAdminService target, String input) {
        TenantContext.set(TENANT);
        try {
            target.createCase(EvalType.QUALITY, request("concurrent-case", input));
            return true;
        } catch (BizException failure) {
            assertEquals(ResultCode.RESOURCE_DUPLICATE, failure.getResultCode());
            return false;
        } finally {
            TenantContext.clear();
        }
    }

    private static <T> T asUser(long id, Supplier<T> action) {
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::isLogin).thenReturn(true);
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(id);
            return action.get();
        }
    }

    private static EvalCaseSaveRequest request(String id, String input) {
        return new EvalCaseSaveRequest(id, input, "验收期望要点", "验收", true, null);
    }

    private static String url(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
    }
}
