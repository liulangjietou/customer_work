package com.richard.fyoung.customeradmin.workspace.callstats.controller;

import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsDetailVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsPageVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsQuery;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallReplayManifestVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallStatsSummaryVO;
import com.richard.fyoung.customeradmin.workspace.callstats.dto.AgentCallTrendVO;
import com.richard.fyoung.customeradmin.workspace.callstats.service.AgentCallStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentCallStatsController} 单测：各端点薄委托 Service，并用统一 {@link Result} 包裹（code=0）。
 * source 默认值/透传、路径参数 id 透传。鉴权注解由 Sa-Token 拦截，非单测范畴。
 * @author owlzhangfq@gmail.com
 */
class AgentCallStatsControllerTest {

    private AgentCallStatsService service;
    private AgentCallStatsController controller;

    @BeforeEach
    void setUp() {
        service = mock(AgentCallStatsService.class);
        controller = new AgentCallStatsController(service);
    }

    @Test
    void page_shouldWrapServiceResult() {
        AgentCallStatsPageVO vo = new AgentCallStatsPageVO(0L, List.of());
        AgentCallStatsQuery query = new AgentCallStatsQuery();
        when(service.page(query)).thenReturn(vo);

        Result<AgentCallStatsPageVO> result = controller.page(query);
        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertSame(vo, result.getData());
    }

    @Test
    void summary_shouldWrapServiceResult() {
        AgentCallStatsSummaryVO vo = new AgentCallStatsSummaryVO();
        AgentCallStatsQuery query = new AgentCallStatsQuery();
        when(service.summary(query)).thenReturn(vo);
        assertSame(vo, controller.summary(query).getData());
    }

    @Test
    void trend_shouldWrapServiceResult() {
        List<AgentCallTrendVO> vos = List.of(new AgentCallTrendVO());
        AgentCallStatsQuery query = new AgentCallStatsQuery();
        when(service.trend(query)).thenReturn(vos);
        assertSame(vos, controller.trend(query).getData());
    }

    @Test
    void detail_shouldPassIdAndSource() {
        AgentCallStatsDetailVO vo = new AgentCallStatsDetailVO();
        when(service.detail(5L, "APP")).thenReturn(vo);
        assertSame(vo, controller.detail(5L, "APP").getData());
        verify(service).detail(5L, "APP");
    }

    @Test
    void replayManifest_shouldPassIdAndSource() {
        AgentCallReplayManifestVO vo = new AgentCallReplayManifestVO(
            2, "INSPECT_ONLY", false, "blocked", "APP", 6L, "trace", "request",
            "agent", "CHAT", "question", "answer", "2026-01-01 00:00:00",
            "revision", "hash", null, null, null, null, null, null, List.of());
        when(service.replayManifest(6L, "APP")).thenReturn(vo);
        assertSame(vo, controller.replayManifest(6L, "APP").getData());
        verify(service).replayManifest(6L, "APP");
    }

    @Test
    void delete_shouldPassIdAndSource() {
        when(service.delete(eq(9L), any())).thenReturn(true);
        Result<Boolean> result = controller.delete(9L, "ADMIN");
        assertTrue(result.getData());
        verify(service).delete(9L, "ADMIN");
    }
}
