package com.richard.fyoung.customeradmin.aiconfig.mcp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpContractSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.dto.McpDebugToolVO;
import com.richard.fyoung.customeradmin.aiconfig.mcp.entity.AiMcpToolContract;
import com.richard.fyoung.customeradmin.aiconfig.mcp.mapper.AiMcpToolContractMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.mcp.McpToolDescriptor;
import com.richard.fyoung.customerwork.tool.mcp.contract.McpContractDrift;
import com.richard.fyoung.customerwork.tool.mcp.contract.McpDriftSeverity;
import com.richard.fyoung.customerwork.tool.mcp.contract.McpToolChange;
import com.richard.fyoung.customerwork.tool.mcp.contract.McpToolContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 工具契约的快照与漂移检测（能力差距 P1-9）。
 *
 * <h3>它解决什么</h3>
 * <p>MCP 工具的定义住在远端服务器，随时可能变。项目对模型、提示词、路由都有完整的版本治理，
 * 唯独 MCP 没有任何基线：{@code listTools} 的结果只拿去调试面板展示、不落库，
 * 「今天和昨天是不是同一套工具」没人回答得了。</p>
 *
 * <h3>三条设计约定</h3>
 * <ol>
 *   <li><b>基线就是上一条快照</b>，不设状态位。每次采集插一行，漂移只在发生的那一次被记下，
 *       新快照自然成为下一次的基线——既不会反复报同一个漂移，也没有状态要维护；</li>
 *   <li><b>只报告，不阻断</b>。检测到 BREAKING 也不会自动停用这个 MCP：上游演进是常态，
 *       为一次漂移把业务能力整个关掉代价远大于收益，而且判定依据是我们这边的基线，
 *       基线本身可能已经过期。要不要停由人决定；</li>
 *   <li><b>连远端这一步复用 {@link McpService#listDebugTools}</b>，不另起一套。
 *       那里已经备齐了独立线程池（避免 {@code block()} 占 Tomcat 线程）、硬超时、
 *       凭据解析与错误文案，重写一份必然在某个细节上跑偏。代价是多一次
 *       {@link McpDebugToolVO} → {@link McpToolDescriptor} 的转换，两者字段一一对应。</li>
 * </ol>
 *
 * @author owlzhangfq@gmail.com
 */
@Service
public class McpContractService {

    private static final Logger log = LoggerFactory.getLogger(McpContractService.class);

    private static final String CODE_CAPTURE_FAIL = "MCP-CONTRACT-CAPTURE-FAIL";
    private static final String CODE_DRIFT_BREAKING = "MCP-CONTRACT-DRIFT-BREAKING";
    private static final String COL_TENANT = "tenant_id";
    private static final String COL_MCP = "mcp_id";
    private static final String COL_ID = "id";

    /** 历史列表的默认条数：足够看清最近的演进，又不会把一整年的快照灌进前端。 */
    private static final int DEFAULT_HISTORY_LIMIT = 20;

    private final McpService mcpService;
    private final AiMcpToolContractMapper contractMapper;
    private final ObjectMapper objectMapper;

    public McpContractService(McpService mcpService,
                              AiMcpToolContractMapper contractMapper,
                              ObjectMapper objectMapper) {
        this.mcpService = mcpService;
        this.contractMapper = contractMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 采集一次契约快照，与上一条比对后落库。
     *
     * @param mcpId      MCP 配置 ID
     * @param operatorId 触发采集的用户；调度采集传 {@code null}
     */
    public CompletableFuture<McpContractSnapshotVO> capture(Long mcpId, Long operatorId) {
        // CompletableFuture 回调不继承 Servlet 线程的租户上下文，先把当前租户抓在手里。
        // 这条约定在本仓库已经复发过一次（知识库连通性回写），漏了会让落库撞租户拦截器 fail-closed，
        // 而表现是「前端显示成功、库里没有」。
        String tenantId = TenantContext.get();

        return mcpService.listDebugTools(mcpId)
            .thenApply(tools -> TenantContext.callWith(tenantId,
                () -> persistSnapshot(mcpId, operatorId, toDescriptors(tools))));
    }

    /** 最近若干条快照，最新在前。 */
    public List<McpContractSnapshotVO> history(Long mcpId, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, 100);
        QueryWrapper<AiMcpToolContract> wrapper = new QueryWrapper<AiMcpToolContract>()
            .eq(COL_MCP, mcpId)
            .orderByDesc(COL_ID)
            .last("LIMIT " + size);
        return contractMapper.selectList(wrapper).stream().map(this::toVo).toList();
    }

    /** 最新一条快照；从未采集过返回 {@code null}。 */
    public McpContractSnapshotVO latest(Long mcpId) {
        AiMcpToolContract latest = latestEntity(mcpId);
        return latest == null ? null : toVo(latest);
    }

    private McpContractSnapshotVO persistSnapshot(Long mcpId, Long operatorId,
                                                 List<McpToolDescriptor> descriptors) {
        McpToolContract current = McpToolContract.of(descriptors);
        AiMcpToolContract previous = latestEntity(mcpId);
        McpContractDrift drift = McpContractDrift.between(toContract(previous), current);

        AiMcpToolContract row = new AiMcpToolContract();
        row.setMcpId(mcpId);
        row.setContractHash(current.hash());
        row.setToolCount(current.toolCount());
        row.setContractJson(current.canonicalJson());
        row.setDriftSeverity(drift.severity().name());
        row.setDriftDetail(drift.changes().isEmpty() ? null : writeChanges(drift.changes()));
        row.setCapturedAt(LocalDateTime.now());
        row.setCapturedBy(operatorId);
        contractMapper.insert(row);

        if (drift.isBreaking()) {
            // 破坏性漂移单独记 error：它意味着模型按旧契约发出的调用会开始失败，
            // 而失败点在远端、错误信息通常只是一句参数校验不过，不从这里看根本联想不到契约变了。
            log.error("mcp tool contract breaking drift, code={}, mcpId={}, changes={}",
                CODE_DRIFT_BREAKING, mcpId, drift.changes());
        } else if (drift.hasDrift()) {
            log.info("[MCP] tool contract drift detected, mcpId={}, severity={}, changes={}",
                mcpId, drift.severity(), drift.changes().size());
        }
        return toVo(row);
    }

    private AiMcpToolContract latestEntity(Long mcpId) {
        QueryWrapper<AiMcpToolContract> wrapper = new QueryWrapper<AiMcpToolContract>()
            .eq(COL_MCP, mcpId)
            .orderByDesc(COL_ID)
            .last("LIMIT 1");
        return contractMapper.selectOne(wrapper);
    }

    /**
     * 从落库的快照还原契约。
     *
     * <p>只需要 hash 就能判定「有没有变」，但逐条差异要靠 {@code tools}——
     * 因此存的是规范化 JSON 而不只是指纹。反序列化失败时按<b>没有基线</b>处理
     * （本次只建立基线、不报漂移），而不是让整次采集失败：坏掉的历史行不该挡住新快照。</p>
     */
    private McpToolContract toContract(AiMcpToolContract row) {
        if (row == null || row.getContractJson() == null) {
            return null;
        }
        try {
            var views = objectMapper.readValue(row.getContractJson(),
                new TypeReference<java.util.TreeMap<String, McpToolContract.ToolView>>() {
                });
            return new McpToolContract(row.getContractHash(),
                row.getToolCount() == null ? views.size() : row.getToolCount(),
                row.getContractJson(), views);
        } catch (Exception e) {
            log.error("mcp contract snapshot deserialize failed, treat as no baseline, code={}, snapshotId={}",
                CODE_CAPTURE_FAIL, row.getId(), e);
            return null;
        }
    }

    private String writeChanges(List<McpToolChange> changes) {
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (Exception e) {
            log.error("mcp contract drift serialize failed, code={}", CODE_CAPTURE_FAIL, e);
            return null;
        }
    }

    private List<McpToolChange> readChanges(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<McpToolChange>>() {
            });
        } catch (Exception e) {
            log.error("mcp contract drift deserialize failed, code={}", CODE_CAPTURE_FAIL, e);
            return List.of();
        }
    }

    /** {@link McpDebugToolVO} 与 {@link McpToolDescriptor} 字段一一对应，转换只为跨越模块边界。 */
    private List<McpToolDescriptor> toDescriptors(List<McpDebugToolVO> tools) {
        return tools == null ? List.of() : tools.stream()
            .map(t -> new McpToolDescriptor(t.name(), t.description(), t.schemaType(),
                t.properties(), t.required()))
            .toList();
    }

    private McpContractSnapshotVO toVo(AiMcpToolContract row) {
        return new McpContractSnapshotVO(row.getId(), row.getContractHash(), row.getToolCount(),
            row.getDriftSeverity() == null ? McpDriftSeverity.NONE.name() : row.getDriftSeverity(),
            readChanges(row.getDriftDetail()), row.getCapturedAt());
    }
}
