package com.richard.fyoung.customeradmin.message.dto;

import com.richard.fyoung.customeradmin.message.entity.SiteMessage;

import java.time.LocalDateTime;

/**
 * 站内消息展示对象：只暴露前端消息中心需要的字段（不含接收人/更新时间等内部字段）。
 *
 * @param id        消息 id
 * @param title     标题
 * @param content   正文
 * @param bizType   业务类型（如 {@code CODE_REVIEW}）
 * @param bizId     业务主键
 * @param link      前端跳转路由（可空）
 * @param readFlag  已读标记：0未读/1已读
 * @param createTime 创建时间
 * @author owlzhangfq@gmail.com
 */
public record SiteMessageVO(
        Long id,
        String title,
        String content,
        String bizType,
        String bizId,
        String link,
        Integer readFlag,
        LocalDateTime createTime) {

    public static SiteMessageVO from(SiteMessage message) {
        return new SiteMessageVO(
            message.getId(),
            message.getTitle(),
            message.getContent(),
            message.getBizType(),
            message.getBizId(),
            message.getLink(),
            message.getReadFlag(),
            message.getCreateTime());
    }
}
