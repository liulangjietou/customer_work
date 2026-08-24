package com.richard.fyoung.customeradmin.aiconfig.secret.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.secret.domain.SecretMaterialStatus;
import com.richard.fyoung.customeradmin.aiconfig.secret.domain.SecretProviderType;
import com.richard.fyoung.customeradmin.aiconfig.secret.domain.SecretRefStatus;
import com.richard.fyoung.customeradmin.aiconfig.secret.dto.SecretMetadataVO;
import com.richard.fyoung.customeradmin.aiconfig.secret.entity.AiSecretMaterial;
import com.richard.fyoung.customeradmin.aiconfig.secret.entity.AiSecretRef;
import com.richard.fyoung.customeradmin.aiconfig.secret.mapper.AiSecretMaterialMapper;
import com.richard.fyoung.customeradmin.aiconfig.secret.mapper.AiSecretRefMapper;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SecretRef 统一读写入口。所有查询都同时限定 refId 与 ownerTenant，再显式关闭租户拦截器，
 * 从而既能读取 default 共享部署的凭据，又不会把 CrossTenantOperations 变成无条件旁路。
 */
@Service
public class SecretRefService {

    private static final String LOCAL_KEY_ID = "admin-aes-gcm";

    private final AiSecretRefMapper secretRefMapper;
    private final AiSecretMaterialMapper secretMaterialMapper;
    private final AesGcmCryptoUtil cryptoUtil;

    public SecretRefService(AiSecretRefMapper secretRefMapper,
                            AiSecretMaterialMapper secretMaterialMapper,
                            AesGcmCryptoUtil cryptoUtil) {
        this.secretRefMapper = secretRefMapper;
        this.secretMaterialMapper = secretMaterialMapper;
        this.cryptoUtil = cryptoUtil;
    }

    @Transactional(rollbackFor = Exception.class)
    public SecretWriteResult createLocal(String tenantId, String refName,
                                         String secretValue, LocalDateTime expiresAt) {
        return createLocal(tenantId, "model", refName, secretValue, expiresAt);
    }

    /** 为模型、MCP 等不同资产建立同一套 SecretRef；refCode 只承担可检索身份，不携带密钥。 */
    @Transactional(rollbackFor = Exception.class)
    public SecretWriteResult createLocal(String tenantId, String refCodePrefix, String refName,
                                         String secretValue, LocalDateTime expiresAt) {
        if (!StringUtils.hasText(secretValue)) {
            throw new BizException(ResultCode.PARAM_MISSING, "凭据值不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        Long operator = currentUserId();
        AiSecretRef ref = new AiSecretRef();
        ref.setTenantId(tenantId);
        ref.setRefCode(normalizePrefix(refCodePrefix) + "-"
            + UUID.randomUUID().toString().replace("-", ""));
        ref.setRefName(refName);
        ref.setProviderType(SecretProviderType.LOCAL_AES.name());
        ref.setCurrentVersion(1);
        ref.setStatus(SecretRefStatus.ACTIVE.name());
        ref.setExpiresAt(expiresAt);
        ref.setLastRotatedAt(now);
        ref.setLastRotatedBy(operator);
        CrossTenantOperations.run(() -> secretRefMapper.insert(ref));

        String cipherText = cryptoUtil.encrypt(secretValue);
        insertMaterial(tenantId, ref.getId(), 1, cipherText, operator, now);
        return new SecretWriteResult(ref.getId(), 1, cipherText, toMetadata(ref));
    }

    @Transactional(rollbackFor = Exception.class)
    public SecretWriteResult rotateOrCreate(AiModelConfig model, String secretValue,
                                            LocalDateTime expiresAt) {
        if (model.getSecretRefId() == null) {
            return createLocal(model.getTenantId(), model.getModelName() + " 凭据", secretValue, expiresAt);
        }
        return rotateLocal(model.getSecretRefId(), model.getTenantId(), secretValue, expiresAt);
    }

    @Transactional(rollbackFor = Exception.class)
    public SecretWriteResult rotateLocal(Long refId, String tenantId,
                                         String secretValue, LocalDateTime expiresAt) {
        if (!StringUtils.hasText(secretValue)) {
            throw new BizException(ResultCode.PARAM_MISSING, "凭据值不能为空");
        }
        AiSecretRef ref = requireRefForUpdate(refId, tenantId);
        if (!SecretProviderType.LOCAL_AES.name().equals(ref.getProviderType())) {
            throw new BizException(ResultCode.PARAM_INVALID, "当前凭据后端不支持本地轮换");
        }

        int nextVersion = ref.getCurrentVersion() + 1;
        LocalDateTime now = LocalDateTime.now();
        Long operator = currentUserId();
        CrossTenantOperations.run(() -> secretMaterialMapper.update(null,
            new UpdateWrapper<AiSecretMaterial>()
                .eq("tenant_id", tenantId)
                .eq("secret_ref_id", refId)
                .eq("status", SecretMaterialStatus.ACTIVE.name())
                .set("status", SecretMaterialStatus.SUPERSEDED.name())));

        String cipherText = cryptoUtil.encrypt(secretValue);
        insertMaterial(tenantId, refId, nextVersion, cipherText, operator, now);
        ref.setCurrentVersion(nextVersion);
        ref.setStatus(SecretRefStatus.ACTIVE.name());
        ref.setExpiresAt(expiresAt);
        ref.setLastRotatedAt(now);
        ref.setLastRotatedBy(operator);
        CrossTenantOperations.run(() -> secretRefMapper.updateById(ref));
        return new SecretWriteResult(refId, nextVersion, cipherText, toMetadata(ref));
    }

    /** 优先读取 SecretRef；ref 缺失时回退旧 api_key 密文。 */
    public String resolveCipherText(Long refId, String tenantId, String legacyCipherText) {
        if (refId == null) {
            if (!StringUtils.hasText(legacyCipherText)) {
                throw new BizException(ResultCode.PARAM_MISSING, "模型部署未配置凭据");
            }
            return legacyCipherText;
        }
        AiSecretRef ref = requireUsableRef(refId, tenantId);
        AiSecretMaterial material = CrossTenantOperations.execute(() -> secretMaterialMapper.selectOne(
            new QueryWrapper<AiSecretMaterial>()
                .eq("tenant_id", tenantId)
                .eq("secret_ref_id", refId)
                .eq("version", ref.getCurrentVersion())
                .eq("status", SecretMaterialStatus.ACTIVE.name())));
        if (material == null || !StringUtils.hasText(material.getCipherText())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "当前凭据版本不存在");
        }
        return material.getCipherText();
    }

    public String resolvePlaintext(AiModelConfig model) {
        return cryptoUtil.decrypt(resolveCipherText(
            model.getSecretRefId(), model.getTenantId(), model.getApiKey()));
    }

    /** 解析任意 SecretRef 当前版本；调用方负责把明文限制在单次构建/调用生命周期。 */
    public String resolvePlaintext(Long refId, String tenantId) {
        return cryptoUtil.decrypt(resolveCipherText(refId, tenantId, null));
    }

    /** 资产已移除全部敏感字段时吊销旧引用，历史材料保留用于审计但不能再解析。 */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long refId, String tenantId) {
        if (refId == null) {
            return;
        }
        AiSecretRef ref = requireRefForUpdate(refId, tenantId);
        ref.setStatus(SecretRefStatus.REVOKED.name());
        CrossTenantOperations.run(() -> secretRefMapper.updateById(ref));
        CrossTenantOperations.run(() -> secretMaterialMapper.update(null,
            new UpdateWrapper<AiSecretMaterial>()
                .eq("tenant_id", tenantId)
                .eq("secret_ref_id", refId)
                .eq("status", SecretMaterialStatus.ACTIVE.name())
                .set("status", SecretMaterialStatus.REVOKED.name())));
    }

    public SecretMetadataVO metadata(Long refId, String tenantId) {
        if (refId == null) {
            return null;
        }
        AiSecretRef ref = findRef(refId, tenantId);
        return ref == null ? null : toMetadata(ref);
    }

    public Map<Long, SecretMetadataVO> metadataBatch(Collection<AiModelConfig> models) {
        if (CollectionUtils.isEmpty(models)) {
            return Collections.emptyMap();
        }
        List<Long> ids = models.stream()
            .map(AiModelConfig::getSecretRefId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> tenants = models.stream().map(AiModelConfig::getTenantId).distinct().toList();
        List<AiSecretRef> refs = CrossTenantOperations.execute(() -> secretRefMapper.selectList(
            new QueryWrapper<AiSecretRef>().in("id", ids).in("tenant_id", tenants)));
        return refs.stream().collect(Collectors.toMap(AiSecretRef::getId, this::toMetadata,
            (left, right) -> left));
    }

    private AiSecretRef requireUsableRef(Long refId, String tenantId) {
        AiSecretRef ref = findRef(refId, tenantId);
        if (ref == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "凭据引用不存在");
        }
        if (ref.getExpiresAt() != null && !ref.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BizException(ResultCode.PARAM_INVALID, "模型凭据已过期");
        }
        if (!SecretRefStatus.ACTIVE.name().equals(ref.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "模型凭据当前不可用");
        }
        return ref;
    }

    private AiSecretRef requireRefForUpdate(Long refId, String tenantId) {
        AiSecretRef ref = CrossTenantOperations.execute(() -> secretRefMapper.selectOne(
            new QueryWrapper<AiSecretRef>()
                .eq("id", refId)
                .eq("tenant_id", tenantId)
                .last("FOR UPDATE")));
        if (ref == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "凭据引用不存在");
        }
        return ref;
    }

    private AiSecretRef findRef(Long refId, String tenantId) {
        return CrossTenantOperations.execute(() -> secretRefMapper.selectOne(
            new QueryWrapper<AiSecretRef>().eq("id", refId).eq("tenant_id", tenantId)));
    }

    private void insertMaterial(String tenantId, Long refId, int version, String cipherText,
                                Long operator, LocalDateTime now) {
        AiSecretMaterial material = new AiSecretMaterial();
        material.setTenantId(tenantId);
        material.setSecretRefId(refId);
        material.setVersion(version);
        material.setCipherText(cipherText);
        material.setKeyId(LOCAL_KEY_ID);
        material.setStatus(SecretMaterialStatus.ACTIVE.name());
        material.setCreateBy(operator);
        material.setCreateTime(now);
        CrossTenantOperations.run(() -> secretMaterialMapper.insert(material));
    }

    private SecretMetadataVO toMetadata(AiSecretRef ref) {
        String effectiveStatus = ref.getExpiresAt() != null
            && !ref.getExpiresAt().isAfter(LocalDateTime.now())
            ? SecretRefStatus.EXPIRED.name() : ref.getStatus();
        return new SecretMetadataVO(ref.getId(), ref.getRefCode(), ref.getProviderType(),
            ref.getCurrentVersion(), effectiveStatus, ref.getExpiresAt(), ref.getLastRotatedAt(),
            ref.getLastRotatedBy());
    }

    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (SaTokenException e) {
            return null;
        }
    }

    private String normalizePrefix(String refCodePrefix) {
        if (!StringUtils.hasText(refCodePrefix)) {
            return "secret";
        }
        String normalized = refCodePrefix.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
        return StringUtils.hasText(normalized) ? normalized : "secret";
    }

    /** 仅供模型服务双写旧列；不得进入 Controller 返回值。 */
    public record SecretWriteResult(Long refId,
                                    int version,
                                    String cipherText,
                                    SecretMetadataVO metadata) {
    }
}
