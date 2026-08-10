package com.richard.fyoung.customerwork.data.attachment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.data.attachment.entity.ChatAttachmentDO;

/**
 * 对话附件 Mapper。仅需基础 CRUD 与按会话查询，全部走 {@link BaseMapper} + {@code QueryWrapper}，
 * 无自定义 SQL、无 XML。
 * @author owlzhangfq@gmail.com
 */
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachmentDO> {
}
