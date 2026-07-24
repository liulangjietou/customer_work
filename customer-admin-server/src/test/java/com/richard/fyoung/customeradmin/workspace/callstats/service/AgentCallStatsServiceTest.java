package com.richard.fyoung.customeradmin.workspace.callstats.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AppAgentCallStatsGatewayProvider;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsDetailVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsPageVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsQuery;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsSummaryVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallTrendVO;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsExtMapper;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsQueryParam;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsTrendRow;
import com.richard.fyoung.customerwork.calllog.AgentCallLogStore;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallLogDO;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallSegmentDO;
import com.richard.fyoung.customerwork.calllog.entity.AgentCallSummaryDO;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallLogMapper;
import com.richard.fyoung.customerwork.calllog.mapper.AgentCallSegmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentCallStatsService} 单测：ADMIN/APP 双源路由、DO→VO 映射（时间格式化、预览截断）、
 * 详情命中/未命中、汇总空结果兜底、趋势粒度选择、删除委托、APP 源不可达时的错误透传。
 * @author owlzhangfq@gmail.com
 */
class AgentCallStatsServiceTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AgentCallStatsExtMapper adminExtMapper;
    private AgentCallLogMapper adminLogMapper;
    private AgentCallSegmentMapper adminSegmentMapper;
    private AgentCallLogStore adminStore;
    private AgentCallStatsGateway adminGateway;

    private AppAgentCallStatsGatewayProvider appProvider;
    private AgentCallStatsService service;

    @BeforeEach
    void setUp() {
        adminExtMapper = mock(AgentCallStatsExtMapper.class);
        adminLogMapper = mock(AgentCallLogMapper.class);
        adminSegmentMapper = mock(AgentCallSegmentMapper.class);
        adminStore = mock(AgentCallLogStore.class);
        adminGateway = new AgentCallStatsGateway(adminExtMapper, adminLogMapper, adminSegmentMapper, adminStore);
        appProvider = mock(AppAgentCallStatsGatewayProvider.class);
        service = new AgentCallStatsService(adminGateway, appProvider);
    }

    private String expectFormat(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(FMT);
    }

    // ===== 分页 + 映射 =====

    @Test
    void page_shouldMapRows_formatTime_andTruncatePreview() {
        long start = 1_700_000_000_000L;
        long end = start + 1234L;
        AgentCallLogDO d = new AgentCallLogDO();
        d.setId(9L);
        d.setRequestId("req-1");
        d.setUserId("coder");
        d.setUsername("alice");
        d.setAgentCode("coder");
        d.setAgentName("编码助手");
        d.setSessionId("s1");
        d.setSessionType("CHAT");
        d.setQuestion("Q".repeat(250));
        d.setAnswer("A".repeat(250));
        d.setStartTime(start);
        d.setEndTime(end);
        d.setDurationMs(1234L);
        d.setModelMs(1000L);
        d.setToolMs(200L);
        d.setMcpMs(30L);
        d.setSkillMs(4L);

        when(adminExtMapper.countBy(any())).thenReturn(1L);
        when(adminExtMapper.findPage(any())).thenReturn(List.of(d));

        AgentCallStatsQuery query = new AgentCallStatsQuery();
        query.setPageNum(2);
        query.setPageSize(10);
        AgentCallStatsPageVO vo = service.page(query);

        assertEquals(1L, vo.getTotal());
        assertEquals(1, vo.getRows().size());
        assertEquals(200, vo.getRows().get(0).getQuestion().length(), "问题应截断到 200");
        assertEquals(200, vo.getRows().get(0).getAnswerPreview().length(), "回答预览应截断到 200");
        assertEquals(expectFormat(start), vo.getRows().get(0).getStartTime());
        assertEquals(expectFormat(end), vo.getRows().get(0).getEndTime());
        assertEquals(1000L, vo.getRows().get(0).getModelMs());

        // 分页偏移：pageNum=2, pageSize=10 → offset=10, limit=10
        ArgumentCaptor<AgentCallStatsQueryParam> captor = ArgumentCaptor.forClass(AgentCallStatsQueryParam.class);
        verify(adminExtMapper).findPage(captor.capture());
        assertEquals(10, captor.getValue().getOffset());
        assertEquals(10, captor.getValue().getLimit());
    }

    @Test
    void page_shouldSkipQuery_whenTotalZero() {
        when(adminExtMapper.countBy(any())).thenReturn(0L);
        AgentCallStatsPageVO vo = service.page(new AgentCallStatsQuery());
        assertEquals(0L, vo.getTotal());
        assertTrue(vo.getRows().isEmpty());
    }

    // ===== 详情 =====

    @Test
    void detail_shouldReturnFullAnswerAndSegments() {
        AgentCallLogDO d = new AgentCallLogDO();
        d.setId(5L);
        d.setAnswer("A".repeat(250));
        d.setStartTime(1_700_000_000_000L);
        when(adminLogMapper.selectById(5L)).thenReturn(d);
        AgentCallSegmentDO seg = new AgentCallSegmentDO();
        seg.setSeq(1);
        seg.setKind("MODEL");
        seg.setName("qwen");
        seg.setStartTime(1_700_000_000_000L);
        seg.setDurationMs(800L);
        seg.setSuccess(true);
        when(adminSegmentMapper.findByCallLogId(5L)).thenReturn(List.of(seg));

        AgentCallStatsDetailVO vo = service.detail(5L, "ADMIN");

        assertEquals(250, vo.getAnswer().length(), "详情回答应为全文，不截断");
        assertEquals(1, vo.getSegments().size());
        assertEquals("MODEL", vo.getSegments().get(0).getKind());
        assertEquals(800L, vo.getSegments().get(0).getDurationMs());
    }

    @Test
    void detail_shouldThrowNotFound_whenAbsent() {
        when(adminLogMapper.selectById(404L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.detail(404L, "ADMIN"));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, e.getResultCode());
    }

    // ===== 汇总 =====

    @Test
    void summary_shouldReturnZeros_whenNoData() {
        when(adminExtMapper.summary(any())).thenReturn(null);
        AgentCallStatsSummaryVO vo = service.summary(new AgentCallStatsQuery());
        assertEquals(0L, vo.getTotalCalls());
        assertEquals(0d, vo.getAvgDurationMs());
        assertEquals(0L, vo.getMaxDurationMs());
    }

    @Test
    void summary_shouldMapAggregates() {
        AgentCallSummaryDO d = new AgentCallSummaryDO();
        d.setTotalCount(3L);
        d.setAvgDurationMs(new BigDecimal("1200.5"));
        d.setMaxDurationMs(3000L);
        d.setAvgModelMs(new BigDecimal("1000"));
        d.setAvgToolMs(new BigDecimal("150"));
        d.setAvgMcpMs(new BigDecimal("40"));
        d.setAvgSkillMs(new BigDecimal("10"));
        when(adminExtMapper.summary(any())).thenReturn(d);

        AgentCallStatsSummaryVO vo = service.summary(new AgentCallStatsQuery());
        assertEquals(3L, vo.getTotalCalls());
        assertEquals(1200.5d, vo.getAvgDurationMs());
        assertEquals(3000L, vo.getMaxDurationMs());
        assertEquals(1000d, vo.getAvgModelMs());
    }

    // ===== 趋势 =====

    @Test
    void trend_shouldUseHourFormat_andMapSegmentAverages() {
        AgentCallStatsTrendRow row = new AgentCallStatsTrendRow();
        row.setBucket("2026-07-24 10");
        row.setCnt(2L);
        row.setAvgDurationMs(new BigDecimal("500"));
        row.setAvgModelMs(new BigDecimal("400"));
        row.setAvgToolMs(new BigDecimal("80"));
        row.setAvgMcpMs(new BigDecimal("15"));
        row.setAvgSkillMs(new BigDecimal("5"));
        when(adminExtMapper.trend(any(), eq("%Y-%m-%d %H"))).thenReturn(List.of(row));

        AgentCallStatsQuery query = new AgentCallStatsQuery();
        query.setGranularity("hour");
        List<AgentCallTrendVO> vos = service.trend(query);

        assertEquals(1, vos.size());
        assertEquals("2026-07-24 10", vos.get(0).getBucket());
        assertEquals(2L, vos.get(0).getCount());
        assertEquals(400d, vos.get(0).getAvgModelMs());
        verify(adminExtMapper).trend(any(), eq("%Y-%m-%d %H"));
    }

    @Test
    void trend_shouldDefaultToDayFormat() {
        when(adminExtMapper.trend(any(), eq("%Y-%m-%d"))).thenReturn(List.of());
        service.trend(new AgentCallStatsQuery());
        verify(adminExtMapper).trend(any(), eq("%Y-%m-%d"));
    }

    // ===== 删除 =====

    @Test
    void delete_shouldDelegateToStore() {
        when(adminStore.delete(7L)).thenReturn(true);
        assertTrue(service.delete(7L, "ADMIN"));
        verify(adminStore).delete(7L);
    }

    // ===== APP 源路由 =====

    @Test
    void page_shouldRouteToAppGateway_whenSourceApp() {
        AgentCallStatsExtMapper appExt = mock(AgentCallStatsExtMapper.class);
        AgentCallStatsGateway appGateway = new AgentCallStatsGateway(appExt,
            mock(AgentCallLogMapper.class), mock(AgentCallSegmentMapper.class), mock(AgentCallLogStore.class));
        when(appProvider.get()).thenReturn(appGateway);
        when(appExt.countBy(any())).thenReturn(0L);

        AgentCallStatsQuery query = new AgentCallStatsQuery();
        query.setSource("APP");
        service.page(query);

        verify(appProvider).get();
        verify(appExt).countBy(any());
    }

    @Test
    void page_shouldPropagateBizException_whenAppUnreachable() {
        when(appProvider.get()).thenThrow(new BizException(ResultCode.CUSTOMER_WORK_UNAVAILABLE, "客服端调用日志库不可达：conn refused"));
        AgentCallStatsQuery query = new AgentCallStatsQuery();
        query.setSource("APP");
        BizException e = assertThrows(BizException.class, () -> service.page(query));
        assertEquals(ResultCode.CUSTOMER_WORK_UNAVAILABLE, e.getResultCode());
    }

    // ===== sessionType 过滤透传 =====

    @Test
    void summary_shouldPassSessionTypeFilter() {
        when(adminExtMapper.summary(any())).thenReturn(null);
        AgentCallStatsQuery query = new AgentCallStatsQuery();
        query.setSessionType("VIBE_CODING");
        service.summary(query);

        ArgumentCaptor<AgentCallStatsQueryParam> captor = ArgumentCaptor.forClass(AgentCallStatsQueryParam.class);
        verify(adminExtMapper).summary(captor.capture());
        assertEquals("VIBE_CODING", captor.getValue().getSessionType());
    }

    @Test
    void toParam_shouldConvertBlankFiltersToNull_andParseTime() {
        long millis = 1_700_000_000_000L;
        String startText = expectFormat(millis);
        when(adminExtMapper.summary(any())).thenReturn(null);

        AgentCallStatsQuery query = new AgentCallStatsQuery();
        query.setUsername("  ");
        query.setStartTime(startText);
        service.summary(query);

        ArgumentCaptor<AgentCallStatsQueryParam> captor = ArgumentCaptor.forClass(AgentCallStatsQueryParam.class);
        verify(adminExtMapper).summary(captor.capture());
        assertNull(captor.getValue().getUsername(), "空白用户名应转 null（不约束）");
        assertEquals(millis, captor.getValue().getStartFromMs());
        assertSame(adminExtMapper, adminGateway.extMapper());
    }
}
