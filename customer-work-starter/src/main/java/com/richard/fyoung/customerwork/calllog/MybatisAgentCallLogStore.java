package com.richard.fyoung.customerwork.calllog;

import com.richard.fyoung.customerwork.calllog.entity.AgentCallLogDO;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallSegmentDO;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallSummaryDO;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallTrendDO;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallLogMapper;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallLogQueryParam;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallSegmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 智能体调用日志存储（{@code customer-work.call-log.store-mode=jdbc} 时启用）。
 *
 * <p>{@link #save} 先落主表回填自增主键，再逐段落明细表（一次调用的分段量级很小，逐条 insert 足够）；
 * 查询/汇总/趋势委托 Mapper 的手写 SQL。建表由统一的 SchemaInitializer 负责，本类不建表。读侧异常一律
 * 降级为空结果 + error 日志，不上抛（报表查询失败不该拖垮页面）；写侧异常上抛（由异步 Sink 侧兜底日志）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisAgentCallLogStore implements AgentCallLogStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisAgentCallLogStore.class);

    private final AgentCallLogMapper callLogMapper;
    private final AgentCallSegmentMapper segmentMapper;

    public MybatisAgentCallLogStore(AgentCallLogMapper callLogMapper,
                                    AgentCallSegmentMapper segmentMapper) {
        this.callLogMapper = callLogMapper;
        this.segmentMapper = segmentMapper;
    }

    @Override
    public AgentCallRecord save(AgentCallRecord record) {
        try {
            AgentCallLogDO logDO = toLogDO(record);
            callLogMapper.insert(logDO);
            Long id = logDO.getId();
            if (record.segments() != null) {
                for (AgentCallSegment segment : record.segments()) {
                    segmentMapper.insert(toSegmentDO(id, segment));
                }
            }
            return record.withId(id);
        } catch (Exception e) {
            log.error("agent call log save failed, code={}, requestId={}",
                "CALLLOG-SAVE-FAIL", record.requestId(), e);
            throw new IllegalStateException("failed to save agent call log: " + record.requestId(), e);
        }
    }

    @Override
    public List<AgentCallRecord> findPage(AgentCallLogQuery query) {
        try {
            return callLogMapper.findPage(toParam(query)).stream()
                .map(this::toRecordWithoutSegments)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("agent call log page query failed, code={}", "CALLLOG-PAGE-FAIL", e);
            return List.of();
        }
    }

    @Override
    public long count(AgentCallLogQuery query) {
        try {
            return callLogMapper.countBy(toParam(query));
        } catch (Exception e) {
            log.error("agent call log count failed, code={}", "CALLLOG-COUNT-FAIL", e);
            return 0L;
        }
    }

    @Override
    public List<AgentCallSegment> findSegments(long callLogId) {
        try {
            return segmentMapper.findByCallLogId(callLogId).stream()
                .map(this::toSegment)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("agent call segment query failed, code={}, callLogId={}",
                "CALLLOG-SEGMENT-QUERY-FAIL", callLogId, e);
            return List.of();
        }
    }

    @Override
    public boolean delete(long id) {
        try {
            segmentMapper.deleteByCallLogId(id);
            return callLogMapper.deleteById(id) > 0;
        } catch (Exception e) {
            log.error("agent call log delete failed, code={}, id={}", "CALLLOG-DELETE-FAIL", id, e);
            return false;
        }
    }

    @Override
    public AgentCallLogSummary summary(AgentCallLogQuery query) {
        try {
            AgentCallSummaryDO do1 = callLogMapper.summary(toParam(query));
            if (do1 == null || do1.getTotalCount() == null || do1.getTotalCount() == 0L) {
                return AgentCallLogSummary.empty();
            }
            return new AgentCallLogSummary(
                do1.getTotalCount(),
                toDouble(do1.getAvgDurationMs()),
                do1.getMaxDurationMs() == null ? 0L : do1.getMaxDurationMs(),
                toDouble(do1.getAvgModelMs()),
                toDouble(do1.getAvgToolMs()),
                toDouble(do1.getAvgMcpMs()),
                toDouble(do1.getAvgSkillMs()),
                do1.getTotalTokens() == null ? 0L : do1.getTotalTokens(),
                toDouble(do1.getAvgTotalTokens()));
        } catch (Exception e) {
            log.error("agent call log summary failed, code={}", "CALLLOG-SUMMARY-FAIL", e);
            return AgentCallLogSummary.empty();
        }
    }

    @Override
    public List<AgentCallTrendPoint> trend(AgentCallLogQuery query, TrendGranularity granularity) {
        try {
            return callLogMapper.trend(toParam(query), granularity.mysqlFormat()).stream()
                .map(d -> new AgentCallTrendPoint(d.getBucket(),
                    d.getCnt() == null ? 0L : d.getCnt(), toDouble(d.getAvgDurationMs()),
                    d.getTotalTokens() == null ? 0L : d.getTotalTokens()))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("agent call log trend failed, code={}", "CALLLOG-TREND-FAIL", e);
            return List.of();
        }
    }

    private AgentCallLogQueryParam toParam(AgentCallLogQuery query) {
        AgentCallLogQueryParam param = new AgentCallLogQueryParam();
        param.setUsername(query.username());
        param.setAgentCode(query.agentCode());
        param.setRequestId(query.requestId());
        param.setSessionId(query.sessionId());
        param.setStartFromMs(query.startFromMs());
        param.setStartToMs(query.startToMs());
        param.setOffset(Math.max(query.offset(), 0));
        param.setLimit(Math.max(query.limit(), 0));
        return param;
    }

    private AgentCallLogDO toLogDO(AgentCallRecord r) {
        AgentCallLogDO d = new AgentCallLogDO();
        d.setRequestId(r.requestId());
        d.setUserId(r.userId());
        d.setUsername(r.username());
        d.setAgentCode(r.agentCode());
        d.setAgentName(r.agentName());
        d.setSessionId(r.sessionId());
        d.setSessionType(r.sessionType() == null ? null : r.sessionType().name());
        d.setQuestion(r.question());
        d.setAnswer(r.answer());
        d.setStartTime(r.startTimeMs());
        d.setEndTime(r.endTimeMs());
        d.setDurationMs(r.durationMs());
        d.setModelMs(r.modelMs());
        d.setToolMs(r.toolMs());
        d.setMcpMs(r.mcpMs());
        d.setSkillMs(r.skillMs());
        d.setSegmentCount(r.segmentCount());
        d.setInputTokens(r.inputTokens());
        d.setOutputTokens(r.outputTokens());
        d.setTotalTokens(r.totalTokens());
        d.setSuccess(r.success());
        d.setErrorMsg(r.errorMsg());
        return d;
    }

    private AgentCallSegmentDO toSegmentDO(Long callLogId, AgentCallSegment s) {
        AgentCallSegmentDO d = new AgentCallSegmentDO();
        d.setCallLogId(callLogId);
        d.setSeq(s.seq());
        d.setKind(s.kind() == null ? null : s.kind().name());
        d.setName(s.name());
        d.setStartTime(s.startTimeMs());
        d.setDurationMs(s.durationMs());
        d.setInputTokens(s.inputTokens());
        d.setOutputTokens(s.outputTokens());
        d.setSuccess(s.success());
        d.setErrorMsg(s.errorMsg());
        return d;
    }

    /** 主记录反序列化（不含明细段，段按需经 {@link #findSegments} 拉取）。 */
    private AgentCallRecord toRecordWithoutSegments(AgentCallLogDO d) {
        return new AgentCallRecord(
            d.getId(), d.getRequestId(), d.getUserId(), d.getUsername(), d.getAgentCode(),
            d.getAgentName(), d.getSessionId(),
            d.getSessionType() == null ? null : AgentCallSessionType.valueOf(d.getSessionType()),
            d.getQuestion(), d.getAnswer(),
            d.getStartTime() == null ? 0L : d.getStartTime(),
            d.getEndTime() == null ? 0L : d.getEndTime(),
            d.getDurationMs() == null ? 0L : d.getDurationMs(),
            d.getModelMs() == null ? 0L : d.getModelMs(),
            d.getToolMs() == null ? 0L : d.getToolMs(),
            d.getMcpMs() == null ? 0L : d.getMcpMs(),
            d.getSkillMs() == null ? 0L : d.getSkillMs(),
            d.getSegmentCount() == null ? 0 : d.getSegmentCount(),
            d.getInputTokens(), d.getOutputTokens(), d.getTotalTokens(),
            d.getSuccess() != null && d.getSuccess(), d.getErrorMsg(), List.of());
    }

    private AgentCallSegment toSegment(AgentCallSegmentDO d) {
        return new AgentCallSegment(
            d.getSeq() == null ? 0 : d.getSeq(),
            d.getKind() == null ? AgentCallKind.TOOL : AgentCallKind.valueOf(d.getKind()),
            d.getName(),
            d.getStartTime() == null ? 0L : d.getStartTime(),
            d.getDurationMs() == null ? 0L : d.getDurationMs(),
            d.getSuccess() != null && d.getSuccess(),
            d.getErrorMsg(),
            d.getInputTokens(), d.getOutputTokens());
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0d : value.doubleValue();
    }
}
