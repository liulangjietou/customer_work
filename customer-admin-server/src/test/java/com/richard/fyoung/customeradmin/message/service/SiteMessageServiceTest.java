package com.richard.fyoung.customeradmin.message.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.message.dto.SiteMessageVO;
import com.richard.fyoung.customeradmin.message.entity.SiteMessage;
import com.richard.fyoung.customeradmin.message.mapper.SiteMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SiteMessageService} 单测：投递落库、未读数、分页 VO 映射、标记已读的归属校验与幂等。
 * {@link SiteMessageMapper} 用 mock 代替（不依赖真实库）。
 * @author owlzhangfq@gmail.com
 */
class SiteMessageServiceTest {

    private SiteMessageMapper siteMessageMapper;
    private SiteMessageService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SiteMessage.class);
    }

    @BeforeEach
    void setUp() {
        siteMessageMapper = mock(SiteMessageMapper.class);
        service = new SiteMessageService(siteMessageMapper);
    }

    @Test
    void send_shouldInsertUnreadMessage_withAllFields() {
        service.send(7L, "标题", "正文", "CODE_REVIEW", "99", "/workspace/coder?reviewTask=99");

        ArgumentCaptor<SiteMessage> captor = ArgumentCaptor.forClass(SiteMessage.class);
        verify(siteMessageMapper).insert(captor.capture());
        SiteMessage saved = captor.getValue();
        assertEquals(7L, saved.getUserId());
        assertEquals("标题", saved.getTitle());
        assertEquals("CODE_REVIEW", saved.getBizType());
        assertEquals("99", saved.getBizId());
        assertEquals("/workspace/coder?reviewTask=99", saved.getLink());
        assertEquals(SiteMessage.UNREAD, saved.getReadFlag(), "新消息应为未读");
    }

    @Test
    void unreadCount_shouldDelegateToMapperCount() {
        when(siteMessageMapper.selectCount(any())).thenReturn(3L);

        assertEquals(3L, service.unreadCount(7L));
    }

    @Test
    void page_shouldMapEntitiesToVO() {
        SiteMessage message = new SiteMessage();
        message.setId(1L);
        message.setUserId(7L);
        message.setTitle("t");
        message.setBizType("CODE_REVIEW");
        message.setReadFlag(SiteMessage.UNREAD);
        Page<SiteMessage> page = new Page<>(1, 10);
        page.setRecords(List.of(message));
        page.setTotal(1);
        when(siteMessageMapper.selectPage(any(IPage.class), any())).thenReturn(page);

        PageResult<SiteMessageVO> result = service.page(7L, null, 1, 10);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("t", result.getList().get(0).title());
        assertEquals("CODE_REVIEW", result.getList().get(0).bizType());
    }

    @Test
    void markRead_shouldThrowNotFound_whenMessageMissing() {
        when(siteMessageMapper.selectById(1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.markRead(1L, 7L));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
    }

    @Test
    void markRead_shouldThrowForbidden_whenNotOwner() {
        SiteMessage message = new SiteMessage();
        message.setId(1L);
        message.setUserId(999L);
        message.setReadFlag(SiteMessage.UNREAD);
        when(siteMessageMapper.selectById(1L)).thenReturn(message);

        BizException ex = assertThrows(BizException.class, () -> service.markRead(1L, 7L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        verify(siteMessageMapper, never()).updateById(any(SiteMessage.class));
    }

    @Test
    void markRead_shouldUpdateReadFlagAndTime_whenOwnedAndUnread() {
        SiteMessage message = new SiteMessage();
        message.setId(1L);
        message.setUserId(7L);
        message.setReadFlag(SiteMessage.UNREAD);
        when(siteMessageMapper.selectById(1L)).thenReturn(message);

        service.markRead(1L, 7L);

        ArgumentCaptor<SiteMessage> captor = ArgumentCaptor.forClass(SiteMessage.class);
        verify(siteMessageMapper).updateById(captor.capture());
        assertEquals(SiteMessage.READ, captor.getValue().getReadFlag());
        assertNotNull(captor.getValue().getReadTime());
    }

    @Test
    void markRead_shouldBeIdempotent_whenAlreadyRead() {
        SiteMessage message = new SiteMessage();
        message.setId(1L);
        message.setUserId(7L);
        message.setReadFlag(SiteMessage.READ);
        when(siteMessageMapper.selectById(1L)).thenReturn(message);

        service.markRead(1L, 7L);

        verify(siteMessageMapper, never()).updateById(any(SiteMessage.class));
    }

    @Test
    void markAllRead_shouldIssueBulkUpdate() {
        service.markAllRead(7L);

        verify(siteMessageMapper).update(eq(null), any());
    }
}
