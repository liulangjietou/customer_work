package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity.AiKnowledgeDocumentRevision;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/** 检索和原文预览共用的文档 ACL，所有已配置的身份维度必须同时满足。 */
public class KnowledgeDocumentAccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentAccessPolicy.class);
    private static final String ACL_PARSE_ERROR_CODE = "KB-ACL-PARSE-FAILED";
    private final ObjectMapper objectMapper;

    public KnowledgeDocumentAccessPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** PUBLIC 仍受调用层的租户和资源归属约束，未知或损坏的 ACL 一律拒绝。 */
    public boolean allowed(AiKnowledgeDocumentRevision revision, AgentInvocationIdentity identity) {
        if (KnowledgeAclMode.PUBLIC.name().equals(revision.getAclMode())) {
            return true;
        }
        if (!KnowledgeAclMode.RESTRICTED.name().equals(revision.getAclMode())
            || identity == null || identity.subjectType() == null) {
            return false;
        }
        return matchesCsv(revision.getAllowedSubjectTypes(), identity.subjectType().name())
            && matchesJson(revision.getAllowedSubjectIds(), identity.subjectId(), revision.getId(),
                "subjectIds")
            && matchesJson(revision.getAllowedChannels(), identity.channelCode(), revision.getId(),
                "channels");
    }

    private boolean matchesCsv(String configured, String actual) {
        if (!StringUtils.hasText(configured)) {
            return true;
        }
        return StringUtils.hasText(actual) && Arrays.stream(configured.split(","))
            .map(String::trim).anyMatch(actual::equalsIgnoreCase);
    }

    private boolean matchesJson(String configured, String actual, Long revisionId, String field) {
        if (!StringUtils.hasText(configured) || "[]".equals(configured.trim())) {
            return true;
        }
        if (!StringUtils.hasText(actual)) {
            return false;
        }
        try {
            List<String> values = objectMapper.readValue(configured,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return values.stream().filter(Objects::nonNull).anyMatch(actual::equalsIgnoreCase);
        } catch (Exception e) {
            log.error("knowledge ACL parse failed, errorCode={}, revisionId={}, field={}",
                ACL_PARSE_ERROR_CODE, revisionId, field, e);
            return false;
        }
    }
}
