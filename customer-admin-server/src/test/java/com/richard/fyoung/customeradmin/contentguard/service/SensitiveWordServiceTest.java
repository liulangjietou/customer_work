package com.richard.fyoung.customeradmin.contentguard.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.config.ContentGuardGatewayProvider;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordPageQuery;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordVO;
import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordExtMapper;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordQueryParam;
import com.richard.fyoung.customerwork.security.ratelimit.mapper.RateLimitRuleMapper;
import com.richard.fyoung.customerwork.sensitiveword.entity.SensitiveWordEntity;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordHitLogMapper;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SensitiveWordService} 单测：分页映射、词面判重、词面去空白、非法枚举报错、
 * 启停只改必要字段、导入超限拦截与默认值填充、导出格式。
 * @author owlzhangfq@gmail.com
 */
class SensitiveWordServiceTest {

    private SensitiveWordMapper wordMapper;
    private SensitiveWordExtMapper extMapper;
    private SensitiveWordService service;

    @BeforeEach
    void setUp() {
        wordMapper = mock(SensitiveWordMapper.class);
        extMapper = mock(SensitiveWordExtMapper.class);
        ContentGuardGateway gateway = new ContentGuardGateway(wordMapper, extMapper,
            mock(RateLimitRuleMapper.class), mock(SensitiveWordHitLogMapper.class), null);
        ContentGuardGatewayProvider provider = mock(ContentGuardGatewayProvider.class);
        when(provider.get()).thenReturn(gateway);
        service = new SensitiveWordService(provider);
    }

    private SensitiveWordEntity row(long id, String word) {
        SensitiveWordEntity entity = new SensitiveWordEntity();
        entity.setId(id);
        entity.setWord(word);
        entity.setCategory("CUSTOM");
        entity.setAction("BLOCK");
        entity.setEnabled(true);
        entity.setCreatedAtMs(1L);
        entity.setUpdatedAtMs(2L);
        return entity;
    }

    private SensitiveWordSaveRequest request(String word, String category, String action) {
        SensitiveWordSaveRequest request = new SensitiveWordSaveRequest();
        request.setWord(word);
        request.setCategory(category);
        request.setAction(action);
        return request;
    }

    @Test
    void page_shouldMapRowsAndCarryFilters() {
        SensitiveWordPageQuery query = new SensitiveWordPageQuery();
        query.setPageNum(2);
        query.setPageSize(5);
        query.setKeyword("测试");
        query.setCategory("CUSTOM");
        query.setStatus(1);
        when(extMapper.countBy(any())).thenReturn(7L);
        when(extMapper.findPage(any())).thenReturn(List.of(row(1L, "测试词")));

        PageResult<SensitiveWordVO> result = service.page(query);

        assertEquals(7L, result.getTotal());
        assertEquals("测试词", result.getList().get(0).getWord());
        ArgumentCaptor<SensitiveWordQueryParam> captor = ArgumentCaptor.forClass(SensitiveWordQueryParam.class);
        verify(extMapper).findPage(captor.capture());
        assertEquals(5, captor.getValue().getLimit());
        assertEquals(5, captor.getValue().getOffset(), "第 2 页 offset 应为 (2-1)*5");
        assertEquals(Boolean.TRUE, captor.getValue().getEnabled(), "status=1 应转成 enabled=true");
    }

    @Test
    void page_shouldSkipQuery_whenNoRowMatches() {
        when(extMapper.countBy(any())).thenReturn(0L);

        PageResult<SensitiveWordVO> result = service.page(new SensitiveWordPageQuery());

        assertTrue(result.getList().isEmpty());
        verify(extMapper, never()).findPage(any());
    }

    @Test
    void create_shouldRejectDuplicatedWord() {
        when(extMapper.findByWord("测试词")).thenReturn(row(1L, "测试词"));

        BizException e = assertThrows(BizException.class,
            () -> service.create(request("测试词", "CUSTOM", "BLOCK")));

        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
        verify(wordMapper, never()).insert(any(SensitiveWordEntity.class));
    }

    @Test
    void create_shouldTrimWord() {
        service.create(request("  两侧有空格  ", "CUSTOM", "BLOCK"));

        ArgumentCaptor<SensitiveWordEntity> captor = ArgumentCaptor.forClass(SensitiveWordEntity.class);
        verify(wordMapper).insert(captor.capture());
        assertEquals("两侧有空格", captor.getValue().getWord(), "粘贴带来的空白必须去掉，否则看起来一样却匹配不上");
    }

    @Test
    void create_shouldRejectIllegalCategory() {
        BizException e = assertThrows(BizException.class,
            () -> service.create(request("词", "NOT_EXIST", "BLOCK")));

        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
    }

    @Test
    void update_shouldRejectWhenWordTakenByAnotherRow() {
        when(wordMapper.selectById(1L)).thenReturn(row(1L, "原词"));
        when(extMapper.findByWord("别人的词")).thenReturn(row(2L, "别人的词"));

        assertThrows(BizException.class, () -> service.update(1L, request("别人的词", "CUSTOM", "BLOCK")));
        verify(wordMapper, never()).updateById(any(SensitiveWordEntity.class));
    }

    @Test
    void update_shouldPassWhenWordUnchangedOnSameRow() {
        when(wordMapper.selectById(1L)).thenReturn(row(1L, "原词"));
        when(extMapper.findByWord("原词")).thenReturn(row(1L, "原词"));

        service.update(1L, request("原词", "ABUSE", "MASK"));

        ArgumentCaptor<SensitiveWordEntity> captor = ArgumentCaptor.forClass(SensitiveWordEntity.class);
        verify(wordMapper).updateById(captor.capture());
        assertEquals("MASK", captor.getValue().getAction());
    }

    @Test
    void toggle_shouldOnlyTouchEnabledAndTimestamp() {
        when(wordMapper.selectById(1L)).thenReturn(row(1L, "词"));

        service.toggle(1L, false);

        ArgumentCaptor<SensitiveWordEntity> captor = ArgumentCaptor.forClass(SensitiveWordEntity.class);
        verify(wordMapper).updateById(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getEnabled());
        assertEquals(null, captor.getValue().getWord(), "启停不该顺带改词面");
        assertTrue(captor.getValue().getUpdatedAtMs() > 0, "必须刷新更新时间，它是客服端轮询的版本指纹来源");
    }

    @Test
    void delete_shouldThrowWhenNotFound() {
        when(wordMapper.deleteById(eq(9L))).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.delete(9L));

        assertEquals(ResultCode.RESOURCE_NOT_FOUND, e.getResultCode());
    }

    @Test
    void importWords_shouldFillDefaultsAndSkipBlankLines() {
        int count = service.importWords(List.of("只有词面", "  ", "带类目,ABUSE", "全字段,COMPETITOR,MASK"));

        assertEquals(3, count, "空白行应跳过");
        ArgumentCaptor<SensitiveWordEntity> captor = ArgumentCaptor.forClass(SensitiveWordEntity.class);
        verify(wordMapper, org.mockito.Mockito.times(3)).upsert(captor.capture());
        List<SensitiveWordEntity> saved = captor.getAllValues();
        assertEquals("CUSTOM", saved.get(0).getCategory(), "缺省类目应为 CUSTOM");
        assertEquals("BLOCK", saved.get(0).getAction(), "缺省动作应为 BLOCK");
        assertEquals("ABUSE", saved.get(1).getCategory());
        assertEquals("BLOCK", saved.get(1).getAction());
        assertEquals("MASK", saved.get(2).getAction());
    }

    @Test
    void importWords_shouldRejectOversizedBatch() {
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            lines.add("词" + i);
        }

        BizException e = assertThrows(BizException.class, () -> service.importWords(lines));

        assertEquals(ResultCode.PARAM_INVALID, e.getResultCode());
        verify(wordMapper, never()).upsert(any(SensitiveWordEntity.class));
    }

    @Test
    void exportWords_shouldUseImportFormat() {
        when(wordMapper.selectList(null)).thenReturn(List.of(row(1L, "词A")));

        List<String> lines = service.exportWords();

        assertEquals(List.of("词A,CUSTOM,BLOCK"), lines, "导出格式必须能原样再导入");
    }
}
