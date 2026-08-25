package com.richard.fyoung.customeradmin.workspace.callstats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.constant.StatsGranularity;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AppAgentCallStatsGatewayProvider;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallSegmentVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsDetailVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsPageVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsQuery;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsRowVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallReplayManifestVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsSource;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsSummaryVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallTrendVO;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsQueryParam;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsTrendRow;
import com.richard.fyoung.customerwork.data.calllog.TrendGranularity;
import com.richard.fyoung.customerwork.data.calllog.AgentReplaySnapshot;
import com.richard.fyoung.customerwork.data.calllog.entity.AgentCallLogDO;
import com.richard.fyoung.customerwork.data.calllog.entity.AgentCallSegmentDO;
import com.richard.fyoung.customerwork.data.calllog.entity.AgentCallSummaryDO;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体调用耗时统计查询服务：按 {@code source} 路由到 ADMIN 本库 / APP 客服端库门面，做分页/详情/汇总/
 * 趋势/删除，并把持久层 DO 转成前端契约 VO（时间戳→{@code yyyy-MM-dd HH:mm:ss}、问题/回答预览截断）。
 *
 * <p>读侧 page/summary/trend 走 admin 的 ext Mapper（带 sessionType 过滤 + trend 各段平均）；详情按 id 取
 * 主记录（starter BaseMapper#selectById）+ 明细段；删除复用 starter Store（主记录 + 分段级联）。APP 源不可
 * 达时门面构建阶段即抛 {@link ResultCode#CUSTOMER_WORK_UNAVAILABLE}（见 {@link AppAgentCallStatsGatewayProvider}）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class AgentCallStatsService {

    private static final Logger log = LoggerFactory.getLogger(AgentCallStatsService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 列表视图问题/回答预览截断长度。 */
    private static final int PREVIEW_MAX_LENGTH = 200;
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String REPLAY_BLOCKED_REASON =
        "默认仅 MOCK；DRY_RUN 需 agent-call-stats:replay 权限且服务必须显式运行在 isolated 环境";

    private final AgentCallStatsGateway adminGateway;
    private final AppAgentCallStatsGatewayProvider appGatewayProvider;

    public AgentCallStatsService(AgentCallStatsGateway adminAgentCallStatsGateway,
                                 AppAgentCallStatsGatewayProvider appGatewayProvider) {
        this.adminGateway = adminAgentCallStatsGateway;
        this.appGatewayProvider = appGatewayProvider;
    }

    /** 分页查询（{total, rows}）。 */
    public AgentCallStatsPageVO page(AgentCallStatsQuery query) {
        AgentCallStatsGateway gateway = gatewayOf(query.getSource());
        AgentCallStatsQueryParam param = toParam(query, true);
        long total = gateway.extMapper().countBy(param);
        List<AgentCallStatsRowVO> rows = new ArrayList<>();
        if (total > 0) {
            for (AgentCallLogDO logDO : gateway.extMapper().findPage(param)) {
                rows.add(toRow(logDO));
            }
        }
        return new AgentCallStatsPageVO(total, rows);
    }

    /** 明细（全量字段 + 分段列表）。id 不存在抛 {@link ResultCode#RESOURCE_NOT_FOUND}。 */
    public AgentCallStatsDetailVO detail(long id, String source) {
        AgentCallStatsGateway gateway = gatewayOf(source);
        AgentCallLogDO logDO = gateway.logMapper().selectById(id);
        if (logDO == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "调用记录不存在: " + id);
        }
        List<AgentCallSegmentDO> segments = gateway.segmentMapper().findByCallLogId(id);
        return toDetail(logDO, segments);
    }

    /**
     * 返回重放清单。查看权限只获取事实；真正发起 MOCK/DRY_RUN 走独立 POST 与权限点。
     */
    public AgentCallReplayManifestVO replayManifest(long id, String source) {
        AgentCallStatsDetailVO detail = detail(id, source);
        return new AgentCallReplayManifestVO(
            3,
            "MOCK_DEFAULT",
            true,
            REPLAY_BLOCKED_REASON,
            AgentCallStatsSource.parse(source).name(),
            detail.getId(),
            detail.getTraceId(),
            detail.getRequestId(),
            detail.getAgentCode(),
            detail.getSessionType(),
            detail.getQuestion(),
            detail.getAnswer(),
            detail.getStartTime(),
            detail.getRuntimeRevision(),
            detail.getRuntimeContentHash(),
            detail.getExperimentId(),
            detail.getExperimentRevision(),
            detail.getExperimentArm(),
            detail.getExperimentDeploymentId(),
            detail.getExperimentBucket(),
            detail.getVersionBinding(),
            detail.getSegments() == null ? List.of() : List.copyOf(detail.getSegments()),
            detail.getReplaySnapshot(),
            List.of("MOCK", "DRY_RUN"),
            captureWarnings(detail));
    }

    /** 汇总统计。 */
    public AgentCallStatsSummaryVO summary(AgentCallStatsQuery query) {
        AgentCallStatsGateway gateway = gatewayOf(query.getSource());
        AgentCallSummaryDO summaryDO = gateway.extMapper().summary(toParam(query, false));
        return toSummary(summaryDO);
    }

    /** 趋势聚合（按天/小时，含各段平均耗时）。 */
    public List<AgentCallTrendVO> trend(AgentCallStatsQuery query) {
        AgentCallStatsGateway gateway = gatewayOf(query.getSource());
        TrendGranularity granularity = StatsGranularity.HOUR.equalsIgnoreCase(query.getGranularity())
            ? TrendGranularity.HOUR : TrendGranularity.DAY;
        List<AgentCallStatsTrendRow> rows = gateway.extMapper().trend(toParam(query, false), granularity.mysqlFormat());
        List<AgentCallTrendVO> result = new ArrayList<>();
        if (!CollectionUtils.isEmpty(rows)) {
            for (AgentCallStatsTrendRow row : rows) {
                result.add(toTrend(row));
            }
        }
        return result;
    }

    /**
     * 删除一条调用（主记录 + 分段级联，复用 starter Store）。
     *
     * <p><b>只允许删 ADMIN 本库的记录</b>：APP 源是客服端运行库，写入方是 8080 那条链路，
     * 后台只查不写。这道判定与 {@code AppAgentCallStatsGatewayProvider} 的只读连接池是
     * 双保险——只读池会让误写在驱动层报 SQLException，那对使用者是一串看不懂的堆栈；
     * 这里先 fast-fail，给出"这条数据的写入方不是后台"这个真正有用的信息。</p>
     */
    public boolean delete(long id, String source) {
        AgentCallStatsSource parsed = AgentCallStatsSource.parse(source);
        if (parsed == AgentCallStatsSource.APP) {
            throw new BizException(ResultCode.CUSTOMER_WORK_READONLY, "客服端调用日志由客服端链路写入，后台不支持删除");
        }
        boolean deleted = gatewayOf(source).store().delete(id);
        log.info("agent call stats deleted, source={}, id={}, deleted={}", parsed, id, deleted);
        return deleted;
    }

    /** 按 source 路由门面：APP 走惰性只读门面（不可达抛明确业务异常），否则 ADMIN 本库门面。 */
    private AgentCallStatsGateway gatewayOf(String source) {
        return AgentCallStatsSource.parse(source) == AgentCallStatsSource.APP
            ? appGatewayProvider.get() : adminGateway;
    }

    private AgentCallStatsQueryParam toParam(AgentCallStatsQuery query, boolean paged) {
        AgentCallStatsQueryParam param = new AgentCallStatsQueryParam();
        param.setUsername(trimToNull(query.getUsername()));
        param.setAgentCode(trimToNull(query.getAgentCode()));
        param.setSessionType(trimToNull(query.getSessionType()));
        param.setRequestId(trimToNull(query.getRequestId()));
        param.setSessionId(trimToNull(query.getSessionId()));
        param.setTraceId(trimToNull(query.getTraceId()));
        param.setRuntimeRevision(trimToNull(query.getRuntimeRevision()));
        param.setExperimentId(query.getExperimentId());
        param.setExperimentArm(trimToNull(query.getExperimentArm()));
        param.setStartFromMs(parseTime(query.getStartTime()));
        param.setStartToMs(parseTime(query.getEndTime()));
        if (paged) {
            int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? DEFAULT_PAGE_NUM : query.getPageNum();
            int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? DEFAULT_PAGE_SIZE : query.getPageSize();
            param.setOffset((pageNum - 1) * pageSize);
            param.setLimit(pageSize);
        }
        return param;
    }

    private AgentCallStatsRowVO toRow(AgentCallLogDO d) {
        AgentCallStatsRowVO vo = new AgentCallStatsRowVO();
        vo.setId(d.getId());
        vo.setRequestId(d.getRequestId());
        vo.setUserId(d.getUserId());
        vo.setUsername(d.getUsername());
        vo.setAgentCode(d.getAgentCode());
        vo.setAgentName(d.getAgentName());
        vo.setSessionId(d.getSessionId());
        vo.setSessionType(d.getSessionType());
        vo.setTraceId(d.getTraceId());
        vo.setRuntimeRevision(d.getRuntimeRevision());
        copyExperiment(d, vo);
        vo.setQuestion(truncate(d.getQuestion()));
        vo.setAnswerPreview(truncate(d.getAnswer()));
        vo.setStartTime(formatTime(d.getStartTime()));
        vo.setEndTime(formatTime(d.getEndTime()));
        vo.setDurationMs(d.getDurationMs());
        vo.setModelMs(d.getModelMs());
        vo.setToolMs(d.getToolMs());
        vo.setMcpMs(d.getMcpMs());
        vo.setSkillMs(d.getSkillMs());
        vo.setInputTokens(d.getInputTokens());
        vo.setOutputTokens(d.getOutputTokens());
        vo.setTotalTokens(d.getTotalTokens());
        vo.setCachedTokens(d.getCachedTokens());
        vo.setModelReportedMs(d.getModelReportedMs());
        vo.setModelCostAmount(d.getModelCostAmount());
        vo.setModelCostCurrency(d.getModelCostCurrency());
        vo.setModelCostStatus(d.getModelCostStatus());
        vo.setModelSegmentCount(d.getModelSegmentCount());
        vo.setSettledCostSegmentCount(d.getSettledCostSegmentCount());
        vo.setUnsettledCostSegmentCount(d.getUnsettledCostSegmentCount());
        return vo;
    }

    private AgentCallStatsDetailVO toDetail(AgentCallLogDO d, List<AgentCallSegmentDO> segments) {
        AgentCallStatsDetailVO vo = new AgentCallStatsDetailVO();
        vo.setId(d.getId());
        vo.setRequestId(d.getRequestId());
        vo.setUserId(d.getUserId());
        vo.setUsername(d.getUsername());
        vo.setAgentCode(d.getAgentCode());
        vo.setAgentName(d.getAgentName());
        vo.setSessionId(d.getSessionId());
        vo.setSessionType(d.getSessionType());
        vo.setTraceId(d.getTraceId());
        vo.setRuntimeRevision(d.getRuntimeRevision());
        vo.setRuntimeContentHash(d.getRuntimeContentHash());
        copyExperiment(d, vo);
        vo.setVersionBinding(readVersionBinding(d.getVersionBindingJson()));
        vo.setReplaySnapshot(readReplaySnapshot(d.getReplaySnapshotJson()));
        vo.setQuestion(d.getQuestion());
        vo.setAnswer(d.getAnswer());
        vo.setStartTime(formatTime(d.getStartTime()));
        vo.setEndTime(formatTime(d.getEndTime()));
        vo.setDurationMs(d.getDurationMs());
        vo.setModelMs(d.getModelMs());
        vo.setToolMs(d.getToolMs());
        vo.setMcpMs(d.getMcpMs());
        vo.setSkillMs(d.getSkillMs());
        vo.setInputTokens(d.getInputTokens());
        vo.setOutputTokens(d.getOutputTokens());
        vo.setTotalTokens(d.getTotalTokens());
        vo.setCachedTokens(d.getCachedTokens());
        vo.setModelReportedMs(d.getModelReportedMs());
        vo.setModelCostAmount(d.getModelCostAmount());
        vo.setModelCostCurrency(d.getModelCostCurrency());
        vo.setModelCostStatus(d.getModelCostStatus());
        vo.setModelSegmentCount(d.getModelSegmentCount());
        vo.setSettledCostSegmentCount(d.getSettledCostSegmentCount());
        vo.setUnsettledCostSegmentCount(d.getUnsettledCostSegmentCount());
        List<AgentCallSegmentVO> segmentVOs = new ArrayList<>();
        if (!CollectionUtils.isEmpty(segments)) {
            for (AgentCallSegmentDO segment : segments) {
                segmentVOs.add(toSegment(segment));
            }
        }
        vo.setSegments(segmentVOs);
        return vo;
    }

    private AgentCallSegmentVO toSegment(AgentCallSegmentDO d) {
        AgentCallSegmentVO vo = new AgentCallSegmentVO();
        vo.setSeq(d.getSeq());
        vo.setKind(d.getKind());
        vo.setName(d.getName());
        vo.setStartTime(formatTime(d.getStartTime()));
        vo.setDurationMs(d.getDurationMs());
        vo.setInputTokens(d.getInputTokens());
        vo.setOutputTokens(d.getOutputTokens());
        vo.setCachedTokens(d.getCachedTokens());
        vo.setModelReportedMs(d.getModelReportedMs());
        vo.setProvider(d.getProvider());
        vo.setDeploymentId(d.getDeploymentId());
        vo.setModelName(d.getModelName());
        vo.setPriceId(d.getPriceId());
        vo.setCurrency(d.getCurrency());
        vo.setInputUnitPrice(d.getInputUnitPrice());
        vo.setOutputUnitPrice(d.getOutputUnitPrice());
        vo.setCachedUnitPrice(d.getCachedUnitPrice());
        vo.setPricingStatus(d.getPricingStatus());
        vo.setCostAmount(d.getCostAmount());
        vo.setCostCurrency(d.getCostCurrency());
        vo.setCostStatus(d.getCostStatus());
        vo.setSuccess(d.getSuccess());
        vo.setErrorMsg(d.getErrorMsg());
        return vo;
    }

    private AgentCallStatsSummaryVO toSummary(AgentCallSummaryDO d) {
        AgentCallStatsSummaryVO vo = new AgentCallStatsSummaryVO();
        if (d == null || d.getTotalCount() == null || d.getTotalCount() == 0L) {
            vo.setTotalCalls(0L);
            vo.setAvgDurationMs(0d);
            vo.setMaxDurationMs(0L);
            vo.setAvgModelMs(0d);
            vo.setAvgToolMs(0d);
            vo.setAvgMcpMs(0d);
            vo.setAvgSkillMs(0d);
            vo.setTotalTokens(0L);
            vo.setAvgTotalTokens(0d);
            vo.setInputTokens(0L);
            vo.setCachedTokens(0L);
            vo.setCacheHitRate(0d);
            return vo;
        }
        vo.setTotalCalls(d.getTotalCount());
        vo.setAvgDurationMs(toDouble(d.getAvgDurationMs()));
        vo.setMaxDurationMs(d.getMaxDurationMs() == null ? 0L : d.getMaxDurationMs());
        vo.setAvgModelMs(toDouble(d.getAvgModelMs()));
        vo.setAvgToolMs(toDouble(d.getAvgToolMs()));
        vo.setAvgMcpMs(toDouble(d.getAvgMcpMs()));
        vo.setAvgSkillMs(toDouble(d.getAvgSkillMs()));
        vo.setTotalTokens(d.getTotalTokens() == null ? 0L : d.getTotalTokens());
        vo.setAvgTotalTokens(toDouble(d.getAvgTotalTokens()));
        long inputTokens = d.getInputTokens() == null ? 0L : d.getInputTokens();
        long cachedTokens = d.getCachedTokens() == null ? 0L : d.getCachedTokens();
        vo.setInputTokens(inputTokens);
        vo.setCachedTokens(cachedTokens);
        // 命中率在后端算：分母是输入总量（缓存量是它的子集），这个口径一旦让各展示端各理解一遍就会错
        vo.setCacheHitRate(inputTokens == 0L ? 0d : (double) cachedTokens / (double) inputTokens);
        return vo;
    }

    private AgentCallTrendVO toTrend(AgentCallStatsTrendRow d) {
        AgentCallTrendVO vo = new AgentCallTrendVO();
        vo.setBucket(d.getBucket());
        vo.setCount(d.getCnt() == null ? 0L : d.getCnt());
        vo.setAvgDurationMs(toDouble(d.getAvgDurationMs()));
        vo.setAvgModelMs(toDouble(d.getAvgModelMs()));
        vo.setAvgToolMs(toDouble(d.getAvgToolMs()));
        vo.setAvgMcpMs(toDouble(d.getAvgMcpMs()));
        vo.setAvgSkillMs(toDouble(d.getAvgSkillMs()));
        vo.setTotalTokens(d.getTotalTokens() == null ? 0L : d.getTotalTokens());
        return vo;
    }

    /** {@code yyyy-MM-dd HH:mm:ss} 文本转毫秒时间戳；空白返回 null（不约束该边界）；非法格式记日志后忽略。 */
    private Long parseTime(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text.trim(), TIME_FORMATTER);
            return dateTime.atZone(ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            log.error("parse call stats time failed, code={}, text={}", "CALLSTATS-TIME-PARSE-FAIL", text, e);
            return null;
        }
    }

    /** 毫秒时间戳转 {@code yyyy-MM-dd HH:mm:ss}；null/<=0 返回 null。 */
    private String formatTime(Long millis) {
        if (millis == null || millis <= 0L) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE).format(TIME_FORMATTER);
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= PREVIEW_MAX_LENGTH ? text : text.substring(0, PREVIEW_MAX_LENGTH);
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0d : value.doubleValue();
    }

    private String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private EvalVersionBinding readVersionBinding(String json) {
        if (!StringUtils.hasText(json)) {
            return EvalVersionBinding.legacy("");
        }
        try {
            return OBJECT_MAPPER.readValue(json, EvalVersionBinding.class);
        } catch (Exception e) {
            log.error("parse call stats version binding failed, code={}",
                "CALLSTATS-LINEAGE-PARSE-FAIL", e);
            return EvalVersionBinding.legacy("");
        }
    }

    private AgentReplaySnapshot readReplaySnapshot(String json) {
        if (!StringUtils.hasText(json)) {
            return AgentReplaySnapshot.empty();
        }
        try {
            return OBJECT_MAPPER.readValue(json, AgentReplaySnapshot.class);
        } catch (Exception e) {
            log.error("agent replay snapshot parse failed, code={}",
                "CALLSTATS-REPLAY-SNAPSHOT-PARSE-FAIL", e);
            return AgentReplaySnapshot.empty();
        }
    }

    private List<String> captureWarnings(AgentCallStatsDetailVO detail) {
        AgentReplaySnapshot snapshot = detail.getReplaySnapshot() == null
            ? AgentReplaySnapshot.empty() : detail.getReplaySnapshot();
        List<String> warnings = new ArrayList<>();
        boolean hasModelSegment = detail.getSegments() != null && detail.getSegments().stream()
            .anyMatch(segment -> "MODEL".equals(segment.getKind()));
        boolean hasToolSegment = detail.getSegments() != null && detail.getSegments().stream()
            .anyMatch(segment -> !"MODEL".equals(segment.getKind()));
        if (hasModelSegment && snapshot.modelCalls().isEmpty()) {
            warnings.add("历史记录缺少模型参数快照");
        }
        if (hasToolSegment && snapshot.toolCalls().isEmpty()) {
            warnings.add("历史记录缺少工具摘要快照");
        }
        EvalVersionBinding binding = detail.getVersionBinding();
        if (binding == null || !StringUtils.hasText(binding.modelVersion())
            || !StringUtils.hasText(binding.promptVersion())
            || !StringUtils.hasText(binding.agentVersion())) {
            warnings.add("制品版本绑定不完整");
        }
        return List.copyOf(warnings);
    }

    private void copyExperiment(AgentCallLogDO source, AgentCallStatsRowVO target) {
        target.setExperimentId(source.getExperimentId());
        target.setExperimentRevision(source.getExperimentRevision());
        target.setExperimentArm(source.getExperimentArm());
        target.setExperimentDeploymentId(source.getExperimentDeploymentId());
        target.setExperimentBucket(source.getExperimentBucket());
    }

    private void copyExperiment(AgentCallLogDO source, AgentCallStatsDetailVO target) {
        target.setExperimentId(source.getExperimentId());
        target.setExperimentRevision(source.getExperimentRevision());
        target.setExperimentArm(source.getExperimentArm());
        target.setExperimentDeploymentId(source.getExperimentDeploymentId());
        target.setExperimentBucket(source.getExperimentBucket());
    }
}
