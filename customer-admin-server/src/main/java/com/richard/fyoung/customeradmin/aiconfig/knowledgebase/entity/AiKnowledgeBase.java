package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RAG 知识库配置（一行 = 一个外部知识库：baseUrl + appId + apiKey）。
 * {@code apiKey} 为 AES/GCM 密文，永不通过接口原样返回给前端（{@code @JsonIgnore} 兜底，
 * 真正的回显值走 {@code KnowledgeBaseVO.apiKeyMasked} 掩码字段）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_knowledge_base")
public class AiKnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String kbName;
    /** RAG 服务基址（不含 {@code /api/v1/knowledge/search} 路径）。 */
    private String baseUrl;
    /** 知识库应用 ID（检索请求体 {@code app_id}）。 */
    private String appId;
    @JsonIgnore
    private String apiKey;
    private String contentType;
    /** 自定义请求头（JSON 对象字符串，空串=无）。 */
    private String extraHeaders;
    /** 单次检索返回条数（请求体 {@code top_n}）。 */
    private Integer topN;
    /** 相关度阈值：低于该值的召回丢弃；0=不过滤（外部 rerank 分数量级仅 0.1x）。 */
    private BigDecimal scoreThreshold;
    /** 0禁用 / 1启用。 */
    private Integer status;
    /** 0未测试 / 1成功 / 2失败。 */
    private Integer testStatus;
    private LocalDateTime testTime;
    private String remark;
    /** 当前最新不可变版本。Agent 关系冻结具体版本，不跟随该指针漂移。 */
    private Long currentVersionId;
    private Integer latestVersionNo;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
