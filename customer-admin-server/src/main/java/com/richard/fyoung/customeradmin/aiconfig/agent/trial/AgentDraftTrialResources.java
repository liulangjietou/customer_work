package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.client.KnowledgeSearchClient;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.runtime.ManagedKnowledgeSearchService;
import com.richard.fyoung.customeradmin.aiconfig.knowledgebase.service.KnowledgeBaseVersionService;
import com.richard.fyoung.customeradmin.aiconfig.skill.service.SkillVersionService;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeBaseEndpoint;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeNode;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 读取试用已冻结的知识与 Skill；不依赖正式智能体关联、不生成文件投影、不执行附件。 */
@Service
public class AgentDraftTrialResources {
    private static final int MAX_TEXT_CHARS = 16000;
    private final KnowledgeBaseVersionService knowledge;
    private final ManagedKnowledgeSearchService managed;
    private final KnowledgeSearchClient external;
    private final AesGcmCryptoUtil crypto;
    private final SkillVersionService skills;

    public AgentDraftTrialResources(KnowledgeBaseVersionService knowledge, ManagedKnowledgeSearchService managed,
        KnowledgeSearchClient external, AesGcmCryptoUtil crypto, SkillVersionService skills) {
        this.knowledge = knowledge;
        this.managed = managed;
        this.external = external;
        this.crypto = crypto;
        this.skills = skills;
    }

    /** 冻结版本检索；外部库不完整返回必须显式失败，不能把故障记成正常未命中。 */
    public List<KnowledgeNode> search(FrozenAgentDraft draft, String query, AgentInvocationIdentity identity) {
        List<KnowledgeNode> nodes = new ArrayList<>();
        for (var resource : draft.knowledgeBases()) {
            var version = knowledge.requireVersion(resource.id(), resource.versionId());
            requireVersion(identity.tenantId(), version.getTenantId(), resource.contentHash(), version.getSnapshotHash());
            if (version.getDocumentCount() != null && version.getDocumentCount() > 0) {
                nodes.addAll(managed.search(resource.name(), version, query, identity));
            } else {
                var endpoint = new KnowledgeBaseEndpoint(resource.id(), resource.name(), version.getBaseUrl(),
                    version.getAppId(), crypto.decrypt(version.getApiKey()), version.getContentType(),
                    version.getExtraHeaders(), version.getTopN(), version.getScoreThreshold());
                var result = external.searchAllResult(List.of(endpoint), query);
                if (!result.complete()) throw new BizException(ResultCode.SYSTEM_ERROR, "本次试用知识检索未完成");
                nodes.addAll(result.nodes());
            }
        }
        return List.copyOf(nodes);
    }

    /** 仅按冻结清单匹配 Skill 及其精确附件路径；路径从不交给本地文件系统。 */
    public String skill(FrozenAgentDraft draft, String code, String path, AgentInvocationIdentity identity) {
        var resource = draft.skills().stream().filter(item -> item.name().equals(code)).findFirst()
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "Skill 不在本次试用清单中"));
        var version = skills.requireVersion(resource.id(), resource.versionId());
        requireVersion(identity.tenantId(), version.getTenantId(), resource.contentHash(), version.getContentHash());
        if (path == null || path.isBlank() || "SKILL.md".equals(path)) return bounded(version.getContent());
        var file = skills.files(resource.versionId()).stream().filter(item -> path.equals(item.getFilePath()))
            .filter(item -> identity.tenantId().equals(item.getTenantId())).findFirst()
            .orElseThrow(() -> new BizException(ResultCode.RESOURCE_NOT_FOUND, "附件不在本次 Skill 版本中"));
        try {
            return bounded(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.getContent())).toString());
        } catch (CharacterCodingException binary) {
            throw new BizException(ResultCode.PARAM_INVALID, "试用只支持读取 UTF-8 文本附件");
        }
    }

    private void requireVersion(String tenant, String actualTenant, String hash, String actualHash) {
        if (!tenant.equals(actualTenant) || !Objects.equals(hash, actualHash)) {
            throw new BizException(ResultCode.CONFIG_EDIT_CONFLICT, "试用资源版本不可见或已变化");
        }
    }

    private String bounded(String text) {
        if (text == null) return "";
        return text.length() <= MAX_TEXT_CHARS ? text
            : text.substring(0, MAX_TEXT_CHARS) + "\n[内容超过试用读取上限，已截断]";
    }
}
