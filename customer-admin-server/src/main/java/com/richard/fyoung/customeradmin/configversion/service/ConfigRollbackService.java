package com.richard.fyoung.customeradmin.configversion.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.entity.AiConfigVersion;
import com.richard.fyoung.customeradmin.configversion.entity.PublishScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 配置回滚与灰度发布。
 *
 * <p><b>回滚是"把旧内容作为新版本再发一次"，不是删掉新版本</b>：删除会让发布历史出现空洞，
 * 事后无法回答"某个时间点线上跑的是哪一版"。只增不删的历史也让回滚本身可被再回滚。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class ConfigRollbackService {

    private final ConfigVersionService versionService;
    private final CustomerWorkConfigPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfigRollbackService(ConfigVersionService versionService,
                                 CustomerWorkConfigPublisher publisher) {
        this.versionService = versionService;
        this.publisher = publisher;
    }

    /**
     * 回滚到指定版本：取该版本的内容快照重新下发，并记为一个新版本。
     *
     * @param versionId 目标版本主键
     * @param remark    回滚说明（建议写清为什么回滚，事后翻历史时这句话最有用）
     * @return 新产生的版本号
     */
    public int rollback(Long versionId, String remark) {
        assertPublishEnabled();
        AiConfigVersion target = versionService.requireVersion(versionId);
        if (target.getContent() == null || target.getContent().isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "该版本没有可回滚的内容快照");
        }

        publisher.publishJson(target.getTargetCode(), target.getTargetId(), target.getContent(),
            null, PublishScope.FULL, null, target.getVersion(),
            remark == null || remark.isBlank() ? "回滚至 v" + target.getVersion() : remark);

        return versionService.findCurrent(
                com.richard.fyoung.customeradmin.configversion.entity.ConfigType.parse(target.getConfigType()),
                target.getTargetCode())
            .map(AiConfigVersion::getVersion)
            .orElse(target.getVersion());
    }

    /**
     * 灰度发布：把指定版本的内容只下发给名单内的租户。
     *
     * <p>逐租户写各自的 dataId（{@code <主dataId>-tenant-<租户码>}）。客服端按自己的租户读，
     * 读不到就回落主 dataId——因此名单外的租户继续用全量版本，客服端不需要理解"灰度"这个概念。</p>
     *
     * @return 实际下发的租户数
     */
    public int grayRelease(Long versionId, List<String> tenantCodes, String remark) {
        assertPublishEnabled();
        if (CollectionUtils.isEmpty(tenantCodes)) {
            throw new BizException(ResultCode.PARAM_MISSING, "灰度发布必须指定至少一个租户");
        }
        AiConfigVersion target = versionService.requireVersion(versionId);
        if (target.getContent() == null || target.getContent().isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "该版本没有可下发的内容快照");
        }

        String grayTenantsJson = serializeTenants(tenantCodes);
        int published = 0;
        for (String tenantCode : tenantCodes) {
            if (tenantCode == null || tenantCode.isBlank()) {
                continue;
            }
            // 逐租户下发，单个失败不阻断其余：灰度本就是分批放量，一个租户失败不该让整批回不去
            try {
                publisher.publishToDataId(publisher.grayDataId(tenantCode.trim()), target.getContent());
                published++;
            } catch (Exception e) {
                log.error("gray release failed for tenant, code={}, tenant={}, version={}",
                    "CONFIG-GRAY-PUBLISH-FAIL", tenantCode, target.getVersion(), e);
            }
        }
        if (published == 0) {
            throw new BizException(ResultCode.RUNTIME_PUBLISH_FAILED, "灰度发布全部失败，请检查 Nacos 连通性");
        }

        versionService.recordPublish(
            com.richard.fyoung.customeradmin.configversion.entity.ConfigType.parse(target.getConfigType()),
            target.getTargetCode(), target.getTargetId(), target.getContent(),
            publisher.grayDataId(tenantCodes.get(0)), PublishScope.GRAY, grayTenantsJson,
            target.getVersion(), remark);

        log.info("gray release done, target={}, version={}, tenants={}/{}",
            target.getTargetCode(), target.getVersion(), published, tenantCodes.size());
        return published;
    }

    /** 解析灰度租户列表（存的是 JSON 数组）。 */
    public List<String> parseGrayTenants(String grayTenantsJson) {
        if (grayTenantsJson == null || grayTenantsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(grayTenantsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.error("parse gray tenants failed, code={}, raw={}", "CONFIG-GRAY-PARSE-FAIL", grayTenantsJson, e);
            return List.of();
        }
    }

    private String serializeTenants(List<String> tenantCodes) {
        try {
            return objectMapper.writeValueAsString(tenantCodes);
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_INVALID, "灰度租户列表序列化失败");
        }
    }

    private void assertPublishEnabled() {
        if (!publisher.isEnabled()) {
            throw new BizException(ResultCode.RUNTIME_PUBLISH_DISABLED);
        }
    }
}
