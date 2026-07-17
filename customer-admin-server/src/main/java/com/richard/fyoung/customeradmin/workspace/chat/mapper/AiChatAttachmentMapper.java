package com.richard.fyoung.customeradmin.workspace.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customeradmin.workspace.chat.entity.AiChatAttachment;

/**
 * 对话附件 Mapper（落 admin 库 {@code ai_chat_attachment}）。仅需基础 CRUD 与按会话查询，
 * 全部走 {@link BaseMapper} + {@code QueryWrapper}，无自定义 SQL、无 XML。
 * 由 {@code CustomerAdminServerApplication} 的 {@code @MapperScan("...**.mapper")} 自动扫描。
 * @author owlzhangfq@gmail.com
 */
public interface AiChatAttachmentMapper extends BaseMapper<AiChatAttachment> {
}
