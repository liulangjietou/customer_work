package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpContractSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcpToolContract;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpToolContractMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.mcp.contract.McpChangeType;
import com.richard.fyoung.customerwork.tool.mcp.contract.McpDriftSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 契约采集与漂移落库。
 *
 * @author owlzhangfq@gmail.com
 */
class McpContractServiceTest {

    private static final Long MCP_ID = 7L;
    private static final String TENANT = "tenant-a";

    private final AiMcpToolContractMapper contractMapper = mock(AiMcpToolContractMapper.class);
    private final McpService mcpService = mock(McpService.class);
    private final McpContractService service =
        new McpContractService(mcpService, contractMapper, new ObjectMapper());

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("首次采集只建立基线，不报漂移")
    void firstCaptureEstablishesBaseline() {
        givenRemoteTools(List.of(tool("refund", "退款", Map.of("orderNo", schema("string")),
            List.of("orderNo"))));
        whenNoPreviousSnapshot();
        AtomicReference<AiMcpToolContract> inserted = captureInsert();

        McpContractSnapshotVO vo = service.capture(MCP_ID, 1L).join();

        assertEquals(McpDriftSeverity.NONE.name(), vo.driftSeverity());
        assertEquals(1, vo.toolCount());
        assertTrue(vo.changes().isEmpty());
        assertNotNull(inserted.get().getContractJson(), "规范化契约必须落库，否则下次只能比指纹、比不出逐条差异");
        assertEquals(null, inserted.get().getDriftDetail(), "无漂移时不该写空数组，留 null 更省也更好查");
    }

    @Test
    @DisplayName("必填项变多要落成 BREAKING，并把逐条差异一起存下来")
    void breakingDriftIsPersistedWithChanges() {
        // 上一条快照：refund 只要求 orderNo
        AiMcpToolContract previous = snapshotOf(List.of(
            tool("refund", "退款", Map.of("orderNo", schema("string"), "note", schema("string")),
                List.of("orderNo"))));
        when(contractMapper.selectOne(any())).thenReturn(previous);
        // 远端现在把 note 也变成必填
        givenRemoteTools(List.of(tool("refund", "退款",
            Map.of("orderNo", schema("string"), "note", schema("string")),
            List.of("orderNo", "note"))));
        AtomicReference<AiMcpToolContract> inserted = captureInsert();

        McpContractSnapshotVO vo = service.capture(MCP_ID, 1L).join();

        assertEquals(McpDriftSeverity.BREAKING.name(), vo.driftSeverity());
        assertTrue(vo.changes().stream()
                .anyMatch(c -> c.type() == McpChangeType.REQUIRED_ADDED && "note".equals(c.detail())),
            "没报出 note 变成必填，实际=" + vo.changes());
        assertNotNull(inserted.get().getDriftDetail(),
            "差异只在日志里而不落库的话，运营翻历史时看不到当时到底变了什么");
    }

    /**
     * 落库那一刻必须还在发起采集时的租户上下文里。
     *
     * <p><b>不能只断言 insert 被调用过</b>：单测没挂 MyBatis-Plus 拦截器，缺上下文也不会抛，
     * 那种断言恒真。本仓库为这个形状修过一次（知识库连通性回写），
     * 表现是前端显示成功、库里没有，同时抛一个 500。</p>
     *
     * <p>成因是 {@code CompletableFuture} 的回调跑在另一个线程上，不继承 ThreadLocal。</p>
     *
     * <p><b>造这个场景比看起来难</b>：第一版用 {@code supplyAsync(...)} 喂一个立刻完成的 future，
     * 变异测试（把 {@code TenantContext.callWith} 去掉）<b>没有打红</b>——
     * {@code thenApply} 在上游已完成时会<b>同步跑在调用线程上</b>，那时租户上下文还在，
     * 断言因此恒真。必须让 future 在 {@code capture()} 返回之后、由另一个线程完成，
     * 回调才会真的落在那个没有上下文的线程上。</p>
     */
    @Test
    @DisplayName("异步回调里落库必须带着发起时的租户上下文")
    void persistRestoresTenantContextInAsyncCallback() throws Exception {
        CompletableFuture<List<McpDebugToolVO>> pending = new CompletableFuture<>();
        when(mcpService.listDebugTools(MCP_ID)).thenReturn(pending);
        whenNoPreviousSnapshot();
        AtomicReference<String> tenantAtInsert = new AtomicReference<>();
        when(contractMapper.insert(any(AiMcpToolContract.class))).thenAnswer(invocation -> {
            tenantAtInsert.set(TenantContext.get());
            return 1;
        });

        CompletableFuture<McpContractSnapshotVO> result =
            TenantContext.callWith(TENANT, () -> service.capture(MCP_ID, 1L));

        // 在租户作用域之外、另一个线程上完成它——回调只能跑在这个线程上
        Thread completer = new Thread(() ->
            pending.complete(List.of(tool("refund", "退款", Map.of(), List.of()))));
        completer.start();
        completer.join();
        result.join();

        assertEquals(TENANT, tenantAtInsert.get(),
            "落库那一刻租户上下文丢了——持久层的租户过滤会 fail-closed，"
                + "而单测里没有拦截器，只断言 insert 被调用过是照不出来的");
    }

    private void givenRemoteTools(List<McpDebugToolVO> tools) {
        when(mcpService.listDebugTools(MCP_ID)).thenReturn(CompletableFuture.completedFuture(tools));
    }

    private void whenNoPreviousSnapshot() {
        when(contractMapper.selectOne(any())).thenReturn(null);
    }

    private AtomicReference<AiMcpToolContract> captureInsert() {
        AtomicReference<AiMcpToolContract> holder = new AtomicReference<>();
        when(contractMapper.insert(any(AiMcpToolContract.class))).thenAnswer(invocation -> {
            holder.set(invocation.getArgument(0));
            return 1;
        });
        return holder;
    }

    /** 用真实的采集路径造一条「上一次的快照」，避免手写 JSON 与实现漂移。 */
    private AiMcpToolContract snapshotOf(List<McpDebugToolVO> tools) {
        var contract = com.richard.fyoung.customerwork.tool.mcp.contract.McpToolContract.of(
            tools.stream().map(t -> new com.richard.fyoung.customerwork.tool.mcp.McpToolDescriptor(
                t.name(), t.description(), t.schemaType(), t.properties(), t.required())).toList());
        AiMcpToolContract row = new AiMcpToolContract();
        row.setId(1L);
        row.setMcpId(MCP_ID);
        row.setContractHash(contract.hash());
        row.setToolCount(contract.toolCount());
        row.setContractJson(contract.canonicalJson());
        return row;
    }

    private static McpDebugToolVO tool(String name, String description,
                                       Map<String, Object> properties, List<String> required) {
        return new McpDebugToolVO(name, description, "object", properties, required);
    }

    private static Map<String, Object> schema(String type) {
        return Map.of("type", type);
    }
}
