package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import java.util.List;

/** 文档 ACL。RESTRICTED 至少要配置一个主体类型、主体 ID 或渠道。 */
public record KnowledgeAclRequest(
    String mode,
    List<String> allowedSubjectTypes,
    List<String> allowedSubjectIds,
    List<String> allowedChannels) {
}
