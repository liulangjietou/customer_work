package com.richard.fyoung.customeradmin.badcase.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 把 badcase 采纳为知识库条目的请求。
 *
 * <p>标题与正文由运营填写而非从原始对话照抄：用户的提问是口语化的、AI 的错误回复更不能要，
 * 直接入库会污染检索质量——本该补上的那条知识，写法要像知识而不是像聊天记录。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class AdoptKnowledgeRequest {

    /** 条目标题（知识库内唯一）。 */
    @NotBlank(message = "标题不能为空")
    private String title;

    /** 条目正文：这次该怎么答才对。 */
    @NotBlank(message = "内容不能为空")
    private String content;

    /** 命中关键词，逗号分隔；决定这条知识能不能被检索到。 */
    @NotBlank(message = "关键词不能为空")
    private String keyword;
}
