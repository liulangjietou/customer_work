package com.richard.fyoung.customeradmin.ops.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 从知识盲区一键补知识的请求。
 *
 * <p>标题正文由运营填写而非拿盲区里的原问题照抄：用户的提问是口语化的，
 * 直接当知识条目入库会污染检索质量。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class FillKnowledgeGapRequest {

    /** 盲区问题的哈希，落在知识条目的来源标注里，便于日后审计"这条知识哪来的"。 */
    @NotBlank(message = "盲区标识不能为空")
    private String questionHash;

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    /** 命中关键词，逗号分隔；决定这条知识能不能被检索到。 */
    @NotBlank(message = "关键词不能为空")
    private String keyword;
}
