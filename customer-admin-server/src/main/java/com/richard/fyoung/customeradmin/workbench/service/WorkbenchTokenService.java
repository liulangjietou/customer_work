package com.richard.fyoung.customeradmin.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workbench.WorkbenchConstants;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreateRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenCreatedVO;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchTokenVO;
import com.richard.fyoung.customeradmin.workbench.entity.WorkbenchToken;
import com.richard.fyoung.customeradmin.workbench.mapper.WorkbenchTokenMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 内网工作台个人访问令牌服务：创建（明文只返回一次）、列举、吊销、校验。
 *
 * <p>令牌明文 = {@code wbt_} + 32 字节安全随机的 URL-safe Base64；库里只存 SHA-256 哈希，
 * 校验时对入参同样哈希后比对，无法从库反推明文。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class WorkbenchTokenService {

    private static final int RANDOM_BYTE_LEN = 32;
    private static final int NOT_REVOKED = 0;
    private static final int REVOKED = 1;

    private final WorkbenchTokenMapper tokenMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public WorkbenchTokenService(WorkbenchTokenMapper tokenMapper) {
        this.tokenMapper = tokenMapper;
    }

    /** 创建令牌，返回含明文的一次性 VO（明文之后无法再取回）。 */
    public WorkbenchTokenCreatedVO createToken(Long userId, WorkbenchTokenCreateRequest request) {
        byte[] randomBytes = new byte[RANDOM_BYTE_LEN];
        secureRandom.nextBytes(randomBytes);
        String rawToken = WorkbenchConstants.TOKEN_PREFIX
            + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        WorkbenchToken token = new WorkbenchToken();
        token.setUserId(userId);
        token.setName(request.name());
        token.setTokenHash(sha256Hex(rawToken));
        token.setTokenPrefix(rawToken.substring(0, WorkbenchConstants.TOKEN_DISPLAY_PREFIX_LEN));
        token.setExpireTime(request.expireDays() != null
            ? LocalDateTime.now().plusDays(request.expireDays()) : null);
        token.setRevoked(NOT_REVOKED);
        tokenMapper.insert(token);

        WorkbenchTokenCreatedVO vo = new WorkbenchTokenCreatedVO();
        vo.setId(token.getId());
        vo.setName(token.getName());
        vo.setToken(rawToken);
        return vo;
    }

    /** 当前用户的令牌列表（未删除），按创建时间倒序。 */
    public List<WorkbenchTokenVO> listByUser(Long userId) {
        LambdaQueryWrapper<WorkbenchToken> wrapper = new LambdaQueryWrapper<WorkbenchToken>()
            .eq(WorkbenchToken::getUserId, userId)
            .orderByDesc(WorkbenchToken::getCreateTime);
        return tokenMapper.selectList(wrapper).stream().map(this::toVo).collect(Collectors.toList());
    }

    /** 吊销令牌：仅令牌所属用户可操作。 */
    public void revoke(Long userId, Long id) {
        WorkbenchToken token = tokenMapper.selectById(id);
        if (token == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "令牌不存在: " + id);
        }
        if (!token.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权吊销他人令牌");
        }
        token.setRevoked(REVOKED);
        tokenMapper.updateById(token);
    }

    /**
     * 校验令牌明文，返回所属 userId；无效（不存在/已吊销/已过期）抛 {@link BizException}。
     * 命中则刷新 last_used_time。
     */
    public Long validate(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new BizException(ResultCode.UNAUTHORIZED, "缺少访问令牌");
        }
        LambdaQueryWrapper<WorkbenchToken> wrapper = new LambdaQueryWrapper<WorkbenchToken>()
            .eq(WorkbenchToken::getTokenHash, sha256Hex(rawToken));
        WorkbenchToken token = tokenMapper.selectOne(wrapper);
        if (token == null || Integer.valueOf(REVOKED).equals(token.getRevoked())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "令牌无效或已吊销");
        }
        if (token.getExpireTime() != null && token.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BizException(ResultCode.TOKEN_EXPIRED, "令牌已过期");
        }
        token.setLastUsedTime(LocalDateTime.now());
        tokenMapper.updateById(token);
        return token.getUserId();
    }

    private WorkbenchTokenVO toVo(WorkbenchToken token) {
        WorkbenchTokenVO vo = new WorkbenchTokenVO();
        vo.setId(token.getId());
        vo.setName(token.getName());
        vo.setTokenPrefix(token.getTokenPrefix());
        vo.setExpireTime(token.getExpireTime());
        vo.setLastUsedTime(token.getLastUsedTime());
        vo.setRevoked(Integer.valueOf(REVOKED).equals(token.getRevoked()));
        vo.setCreateTime(token.getCreateTime());
        return vo;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
