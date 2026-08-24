package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain;

/** 文档访问控制模式。PUBLIC 对租户内可信主体开放，RESTRICTED 必须命中显式 ACL。 */
public enum KnowledgeAclMode {
    PUBLIC,
    RESTRICTED
}
