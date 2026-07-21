package com.richard.fyoung.customeradmin.message.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用站内消息（贫血 DO）：一条消息一个接收人，任意业务域通过
 * {@link com.richard.fyoung.customeradmin.message.service.SiteMessageService#send} 投递，
 * {@code bizType} 区分来源（如 {@code CODE_REVIEW}），前端消息中心统一拉取。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_site_message")
public class SiteMessage {

    /** 已读标记：未读。 */
    public static final int UNREAD = 0;
    /** 已读标记：已读。 */
    public static final int READ = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人（admin 用户 id）。 */
    private Long userId;

    private String title;
    private String content;

    /** 业务类型（如 {@code CODE_REVIEW}），供前端按来源过滤/图标区分。 */
    private String bizType;
    /** 业务主键（如审查任务 id），前端跳转时携带。 */
    private String bizId;
    /** 前端跳转路由（可空）。 */
    private String link;

    /** 已读标记：{@link #UNREAD} / {@link #READ}。 */
    private Integer readFlag;
    /** 标记已读的时间，未读时为空。 */
    private LocalDateTime readTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
