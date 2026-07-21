package com.richard.fyoung.customeradmin.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.message.dto.SiteMessageVO;
import com.richard.fyoung.customeradmin.message.entity.SiteMessage;
import com.richard.fyoung.customeradmin.message.mapper.SiteMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 通用站内消息服务：投递（供任意业务域调用）、当前用户的分页查询、未读数、标记已读。
 *
 * <p>投递入口 {@link #send} 不依赖 Web 上下文（接收人 userId 由调用方显式传入），可在后台异步
 * 线程里安全调用（如 AI 代码审查异步完成后的回调线程）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class SiteMessageService {

    private static final Logger log = LoggerFactory.getLogger(SiteMessageService.class);

    private final SiteMessageMapper siteMessageMapper;

    public SiteMessageService(SiteMessageMapper siteMessageMapper) {
        this.siteMessageMapper = siteMessageMapper;
    }

    /**
     * 投递一条站内消息（通用入口）。title/bizType 为必填业务约束，交由调用方保证；这里只做落库。
     *
     * @param userId  接收人（admin 用户 id）
     * @param title   标题
     * @param content 正文（可空）
     * @param bizType 业务类型（如 {@code CODE_REVIEW}）
     * @param bizId   业务主键（可空）
     * @param link    前端跳转路由（可空）
     */
    public void send(Long userId, String title, String content, String bizType, String bizId, String link) {
        SiteMessage message = new SiteMessage();
        message.setUserId(userId);
        message.setTitle(title);
        message.setContent(content);
        message.setBizType(bizType);
        message.setBizId(bizId);
        message.setLink(link);
        message.setReadFlag(SiteMessage.UNREAD);
        siteMessageMapper.insert(message);
        log.info("site message sent, userId={}, bizType={}, bizId={}", userId, bizType, bizId);
    }

    /**
     * 分页查询当前用户的站内消息：可选按已读标记过滤，未读优先、同组按创建时间倒序。
     *
     * @param userId   当前用户
     * @param readFlag 已读标记过滤（null 表示不过滤）
     */
    public PageResult<SiteMessageVO> page(Long userId, Integer readFlag, long pageNum, long pageSize) {
        LambdaQueryWrapper<SiteMessage> wrapper = new LambdaQueryWrapper<SiteMessage>()
            .eq(SiteMessage::getUserId, userId)
            .eq(readFlag != null, SiteMessage::getReadFlag, readFlag)
            .orderByAsc(SiteMessage::getReadFlag)
            .orderByDesc(SiteMessage::getCreateTime);
        IPage<SiteMessage> page = siteMessageMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.convert(SiteMessageVO::from));
    }

    /** 当前用户的未读消息数。 */
    public long unreadCount(Long userId) {
        return siteMessageMapper.selectCount(new LambdaQueryWrapper<SiteMessage>()
            .eq(SiteMessage::getUserId, userId)
            .eq(SiteMessage::getReadFlag, SiteMessage.UNREAD));
    }

    /** 标记单条消息已读：校验归属，非本人/不存在快速失败。已读幂等。 */
    public void markRead(Long id, Long userId) {
        SiteMessage message = siteMessageMapper.selectById(id);
        if (message == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "消息不存在: " + id);
        }
        if (!message.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权操作他人消息");
        }
        if (SiteMessage.READ == message.getReadFlag()) {
            return;
        }
        SiteMessage update = new SiteMessage();
        update.setId(id);
        update.setReadFlag(SiteMessage.READ);
        update.setReadTime(LocalDateTime.now());
        siteMessageMapper.updateById(update);
    }

    /** 标记当前用户全部未读消息为已读。 */
    public void markAllRead(Long userId) {
        LambdaUpdateWrapper<SiteMessage> wrapper = new LambdaUpdateWrapper<SiteMessage>()
            .eq(SiteMessage::getUserId, userId)
            .eq(SiteMessage::getReadFlag, SiteMessage.UNREAD)
            .set(SiteMessage::getReadFlag, SiteMessage.READ)
            .set(SiteMessage::getReadTime, LocalDateTime.now());
        siteMessageMapper.update(null, wrapper);
    }
}
