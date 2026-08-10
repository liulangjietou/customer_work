package com.richard.fyoung.customeradmin.contentguard.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.dto.RateLimitRuleSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.dto.RateLimitRuleVO;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordExtMapper;
import com.richard.fyoung.customerwork.safety.security.ratelimit.entity.RateLimitRuleEntity;
import com.richard.fyoung.customerwork.safety.security.ratelimit.mapper.RateLimitRuleMapper;
import com.richard.fyoung.customerwork.safety.sensitiveword.mapper.SensitiveWordHitLogMapper;
import com.richard.fyoung.customerwork.safety.sensitiveword.mapper.SensitiveWordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitRuleService} 单测：内存分页与关键字/启停筛选、规则名判重、非法枚举严格报错、
 * 启停只改必要字段。
 * @author owlzhangfq@gmail.com
 */
class RateLimitRuleServiceTest {

    private RateLimitRuleMapper ruleMapper;
    private RateLimitRuleService service;

    @BeforeEach
    void setUp() {
        ruleMapper = mock(RateLimitRuleMapper.class);
        ContentGuardGateway gateway = new ContentGuardGateway(mock(SensitiveWordMapper.class),
            mock(SensitiveWordExtMapper.class), ruleMapper, mock(SensitiveWordHitLogMapper.class), null);
        ContentGuardGatewayProvider provider = mock(ContentGuardGatewayProvider.class);
        when(provider.get()).thenReturn(gateway);
        service = new RateLimitRuleService(provider);
    }

    private RateLimitRuleEntity row(long id, String name, String path, int priority, boolean enabled) {
        RateLimitRuleEntity entity = new RateLimitRuleEntity();
        entity.setId(id);
        entity.setRuleName(name);
        entity.setPathPrefix(path);
        entity.setDimension("IP");
        entity.setLimitCount(10);
        entity.setAlgorithm("FIXED_WINDOW");
        entity.setWindowSeconds(60);
        entity.setPriority(priority);
        entity.setEnabled(enabled);
        entity.setCreatedAtMs(1L);
        entity.setUpdatedAtMs(2L);
        return entity;
    }

    private RateLimitRuleSaveRequest request(String name, String dimension, String algorithm) {
        RateLimitRuleSaveRequest request = new RateLimitRuleSaveRequest();
        request.setRuleName(name);
        request.setPathPrefix("/api/customer");
        request.setDimension(dimension);
        request.setLimitCount(10);
        request.setAlgorithm(algorithm);
        request.setWindowSeconds(60);
        request.setPriority(1);
        return request;
    }

    @Test
    void page_shouldFilterByKeywordOnNameOrPath() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
            row(1L, "chat-strict", "/api/customer/chat", 0, true),
            row(2L, "ticket", "/api/customer/ticket", 1, true)));
        PageQuery query = new PageQuery();
        query.setKeyword("chat");

        PageResult<RateLimitRuleVO> result = service.page(query);

        assertEquals(1L, result.getTotal());
        assertEquals("chat-strict", result.getList().get(0).getRuleName());
    }

    @Test
    void page_shouldFilterByEnabledStatus() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
            row(1L, "on", "/api/a", 0, true),
            row(2L, "off", "/api/b", 1, false)));
        PageQuery query = new PageQuery();
        query.setStatus(0);

        PageResult<RateLimitRuleVO> result = service.page(query);

        assertEquals(1L, result.getTotal());
        assertEquals("off", result.getList().get(0).getRuleName());
    }

    @Test
    void page_shouldSliceByPage() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(
            row(1L, "a", "/api/a", 0, true),
            row(2L, "b", "/api/b", 1, true),
            row(3L, "c", "/api/c", 2, true)));
        PageQuery query = new PageQuery();
        query.setPageNum(2);
        query.setPageSize(2);

        PageResult<RateLimitRuleVO> result = service.page(query);

        assertEquals(3L, result.getTotal(), "总数是筛选后的全量，不是本页条数");
        assertEquals(1, result.getList().size());
        assertEquals("c", result.getList().get(0).getRuleName());
    }

    @Test
    void create_shouldRejectDuplicatedRuleName() {
        when(ruleMapper.selectOne(any())).thenReturn(row(1L, "dup", "/api/a", 0, true));

        BizException e = assertThrows(BizException.class,
            () -> service.create(request("dup", "IP", "FIXED_WINDOW")));

        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
        verify(ruleMapper, never()).insert(any(RateLimitRuleEntity.class));
    }

    @Test
    void create_shouldRejectIllegalDimension() {
        BizException e = assertThrows(BizException.class,
            () -> service.create(request("r", "NOT_A_DIMENSION", "FIXED_WINDOW")));

        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    @Test
    void create_shouldRejectIllegalAlgorithm() {
        BizException e = assertThrows(BizException.class,
            () -> service.create(request("r", "IP", "NOT_AN_ALGORITHM")));

        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    @Test
    void toggle_shouldOnlyTouchEnabledAndTimestamp() {
        when(ruleMapper.selectById(1L)).thenReturn(row(1L, "r", "/api/a", 0, true));

        service.toggle(1L, false);

        ArgumentCaptor<RateLimitRuleEntity> captor = ArgumentCaptor.forClass(RateLimitRuleEntity.class);
        verify(ruleMapper).updateById(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getEnabled());
        assertEquals(null, captor.getValue().getPathPrefix(), "启停不该顺带改路径");
    }

    @Test
    void delete_shouldThrowWhenNotFound() {
        when(ruleMapper.deleteById(9L)).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.delete(9L));

        assertEquals(ResultCode.RESOURCE_NOT_FOUND, e.getResultCode());
    }
}
