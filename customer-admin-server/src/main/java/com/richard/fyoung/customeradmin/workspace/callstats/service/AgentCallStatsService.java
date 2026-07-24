package com.richard.fyoung.customeradmin.workspace.callstats.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AppAgentCallStatsGatewayProvider;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallSegmentVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsDetailVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsPageVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsQuery;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsRowVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsSource;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsSummaryVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallTrendVO;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsQueryParam;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsTrendRow;
import com.richard.fyoung.customerwork.calllog.TrendGranularity;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallLogDO;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallSegmentDO;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallSummaryDO;
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

    /** 列表视图问题/回答预览截断长度。 */
    private static final int PREVIEW_MAX_LENGTH = 200;
    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final String GRANULARITY_HOUR = "hour";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.systemDefault();

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

    /** 汇总统计。 */
    public AgentCallStatsSummaryVO summary(AgentCallStatsQuery query) {
        AgentCallStatsGateway gateway = gatewayOf(query.getSource());
        AgentCallSummaryDO summaryDO = gateway.extMapper().summary(toParam(query, false));
        return toSummary(summaryDO);
    }

    /** 趋势聚合（按天/小时，含各段平均耗时）。 */
    public List<AgentCallTrendVO> trend(AgentCallStatsQuery query) {
        AgentCallStatsGateway gateway = gatewayOf(query.getSource());
        TrendGranularity granularity = GRANULARITY_HOUR.equalsIgnoreCase(query.getGranularity())
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

    /** 删除一条调用（主记录 + 分段级联，复用 starter Store）。 */
    public boolean delete(long id, String source) {
        AgentCallStatsGateway gateway = gatewayOf(source);
        boolean deleted = gateway.store().delete(id);
        log.info("agent call stats deleted, source={}, id={}, deleted={}",
            AgentCallStatsSource.parse(source), id, deleted);
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
}
