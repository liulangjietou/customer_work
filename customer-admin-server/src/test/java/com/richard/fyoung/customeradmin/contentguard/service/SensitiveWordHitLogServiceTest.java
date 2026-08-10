package com.richard.fyoung.customeradmin.contentguard.service;

import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitLogPageQuery;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitLogVO;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordHitStatsVO;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardCountRow;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordExtMapper;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordHitLogExtMapper;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordHitLogQueryParam;
import com.richard.fyoung.customerwork.safety.security.ratelimit.mapper.RateLimitRuleMapper;
import com.richard.fyoung.customerwork.safety.sensitiveword.entity.SensitiveWordHitLogEntity;
import com.richard.fyoung.customerwork.safety.sensitiveword.mapper.SensitiveWordHitLogMapper;
import com.richard.fyoung.customerwork.safety.sensitiveword.mapper.SensitiveWordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SensitiveWordHitLogService} 单测：命中词串拆列表、趋势粒度按区间跨度自动选择、
 * 明细与统计共用同一套筛选条件。
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordHitLogServiceTest {

    private static final long HOUR_MS = 60 * 60 * 1000L;

    private SensitiveWordHitLogExtMapper extMapper;
    private SensitiveWordHitLogService service;

    @BeforeEach
    void setUp() {
        extMapper = mock(SensitiveWordHitLogExtMapper.class);
        ContentGuardGateway gateway = new ContentGuardGateway(mock(SensitiveWordMapper.class),
            mock(SensitiveWordExtMapper.class), mock(RateLimitRuleMapper.class),
            mock(SensitiveWordHitLogMapper.class), extMapper);
        ContentGuardGatewayProvider provider = mock(ContentGuardGatewayProvider.class);
        when(provider.get()).thenReturn(gateway);
        service = new SensitiveWordHitLogService(provider);
        when(extMapper.countByAction(any())).thenReturn(List.of());
        when(extMapper.countByDirection(any())).thenReturn(List.of());
        when(extMapper.topWords(any(), anyInt())).thenReturn(List.of());
        when(extMapper.trend(any(), any())).thenReturn(List.of());
    }

    private SensitiveWordHitLogEntity row() {
        SensitiveWordHitLogEntity entity = new SensitiveWordHitLogEntity();
        entity.setId(1L);
        entity.setDirection("INBOUND");
        entity.setAction("BLOCK");
        entity.setWords("词A,词B");
        entity.setCategories("CUSTOM,ABUSE");
        entity.setHitCount(2);
        entity.setAgentName("agent");
        entity.setSessionId("sess");
        entity.setSnippet("片段");
        entity.setCreatedAtMs(100L);
        return entity;
    }

    @Test
    void page_shouldSplitWordsAndCategories() {
        when(extMapper.countBy(any())).thenReturn(1L);
        when(extMapper.findPage(any())).thenReturn(List.of(row()));

        PageResult<SensitiveWordHitLogVO> result = service.page(new SensitiveWordHitLogPageQuery());

        SensitiveWordHitLogVO vo = result.getList().get(0);
        assertEquals(List.of("词A", "词B"), vo.getWords());
        assertEquals(List.of("CUSTOM", "ABUSE"), vo.getCategories());
    }

    @Test
    void page_shouldSkipQuery_whenNoRowMatches() {
        when(extMapper.countBy(any())).thenReturn(0L);

        service.page(new SensitiveWordHitLogPageQuery());

        verify(extMapper, never()).findPage(any());
    }

    @Test
    void stats_shouldUseHourlyGranularity_forShortRange() {
        SensitiveWordHitLogPageQuery query = new SensitiveWordHitLogPageQuery();
        query.setStartMs(0L);
        query.setEndMs(6 * HOUR_MS);

        SensitiveWordHitStatsVO stats = service.stats(query);

        assertEquals("hour", stats.getTrendGranularity());
        verify(extMapper).trend(any(), eq("%Y-%m-%d %H:00"));
    }

    @Test
    void stats_shouldUseDailyGranularity_forLongRange() {
        SensitiveWordHitLogPageQuery query = new SensitiveWordHitLogPageQuery();
        query.setStartMs(0L);
        query.setEndMs(30 * 24 * HOUR_MS);

        SensitiveWordHitStatsVO stats = service.stats(query);

        assertEquals("day", stats.getTrendGranularity(), "跨度过大必须降到按天，否则 X 轴点数爆炸");
        verify(extMapper).trend(any(), eq("%Y-%m-%d"));
    }

    @Test
    void stats_shouldUseDailyGranularity_whenRangeNotGiven() {
        SensitiveWordHitStatsVO stats = service.stats(new SensitiveWordHitLogPageQuery());

        assertEquals("day", stats.getTrendGranularity());
    }

    @Test
    void stats_shouldCarrySameFiltersAsList() {
        SensitiveWordHitLogPageQuery query = new SensitiveWordHitLogPageQuery();
        query.setDirection("INBOUND");
        query.setAction("BLOCK");
        query.setKeyword("词A");
        query.setSessionId("sess-1");

        service.stats(query);

        ArgumentCaptor<SensitiveWordHitLogQueryParam> captor =
            ArgumentCaptor.forClass(SensitiveWordHitLogQueryParam.class);
        verify(extMapper).countByAction(captor.capture());
        SensitiveWordHitLogQueryParam param = captor.getValue();
        assertEquals("INBOUND", param.getDirection());
        assertEquals("BLOCK", param.getAction());
        assertEquals("词A", param.getKeyword());
        assertEquals("sess-1", param.getSessionId());
    }

    @Test
    void stats_shouldMapCountRows() {
        ContentGuardCountRow countRow = new ContentGuardCountRow();
        countRow.setLabel("BLOCK");
        countRow.setTotal(3L);
        when(extMapper.countByAction(any())).thenReturn(List.of(countRow));
        when(extMapper.countBy(any())).thenReturn(3L);

        SensitiveWordHitStatsVO stats = service.stats(new SensitiveWordHitLogPageQuery());

        assertEquals(3L, stats.getTotal());
        assertEquals(1, stats.getByAction().size());
        assertEquals("BLOCK", stats.getByAction().get(0).getLabel());
        assertTrue(stats.getByDirection().isEmpty());
    }
}
