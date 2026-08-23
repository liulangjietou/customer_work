package com.richard.fyoung.customeradmin.configversion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigVersionPageQuery;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigVersionVO;
import com.richard.fyoung.customeradmin.configversion.entity.AiConfigVersion;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.entity.PublishScope;
import com.richard.fyoung.customeradmin.configversion.mapper.AiConfigVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 配置版本快照的记录与检索。
 *
 * <p>只负责"记下发布了什么"，实际下发由 {@code CustomerWorkConfigPublisher} 负责——
 * 两者分开，是为了让"发布失败"也能留下一条 FAILED 记录：发布动作失败恰恰是最需要留痕的时刻。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class ConfigVersionService {

    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final String STATUS_FAILED = "FAILED";

    private final AiConfigVersionMapper versionMapper;
    private final ConfigSnapshotRedactor snapshotRedactor;

    public ConfigVersionService(AiConfigVersionMapper versionMapper) {
        this.versionMapper = versionMapper;
        this.snapshotRedactor = new ConfigSnapshotRedactor(new ObjectMapper());
    }

    /**
     * 记录一次成功发布。
     *
     * <p>内容与上一版完全相同时不再新增版本——重复发布同样的内容只会把版本历史刷满噪音，
     * 让"这次到底改了什么"变得难以辨认。</p>
     *
     * @return 新版本号；内容未变时返回既有版本号
     */
    @Transactional(rollbackFor = Exception.class)
    public int recordPublish(ConfigType type, String targetCode, Long targetId, String content,
                             String dataId, PublishScope scope, String grayTenants,
                             Integer sourceVersion, String remark) {
        String hash = sha256(content);
        Optional<AiConfigVersion> latest = findLatest(type, targetCode);
        if (latest.isPresent() && hash.equals(latest.get().getContentHash())
            && scope.name().equals(latest.get().getPublishScope())) {
            log.info("config content unchanged, skip new version, type={}, target={}, version={}",
                type, targetCode, latest.get().getVersion());
            return latest.get().getVersion();
        }

        int nextVersion = latest.map(v -> v.getVersion() + 1).orElse(1);
        // 旧版本标记为已有后续投递；实例真实生效状态仍以可靠发布任务 ACK 为准。
        latest.ifPresent(prev -> {
            if (STATUS_PUBLISHED.equals(prev.getStatus())) {
                AiConfigVersion update = new AiConfigVersion();
                update.setId(prev.getId());
                update.setStatus(STATUS_SUPERSEDED);
                versionMapper.updateById(update);
            }
        });

        AiConfigVersion entity = new AiConfigVersion();
        entity.setConfigType(type.name());
        entity.setTargetCode(targetCode);
        entity.setTargetId(targetId);
        entity.setVersion(nextVersion);
        entity.setContent(content);
        entity.setContentHash(hash);
        entity.setPublishScope(scope.name());
        entity.setGrayTenants(grayTenants);
        entity.setDataId(dataId == null ? "" : dataId);
        entity.setStatus(STATUS_PUBLISHED);
        entity.setSourceVersion(sourceVersion);
        entity.setRemark(remark);
        versionMapper.insert(entity);

        log.info("config version recorded, type={}, target={}, version={}, scope={}, rollbackFrom={}",
            type, targetCode, nextVersion, scope, sourceVersion);
        return nextVersion;
    }

    /**
     * 记录一次失败的发布尝试。
     *
     * <p>失败也要留痕：排查"线上为什么还是旧配置"时，一条 FAILED 记录比什么都没有有用得多。
     * 失败版本不参与"取代上一版"；实例真实运行版本仍以可靠任务 ACK 为准。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordFailure(ConfigType type, String targetCode, Long targetId,
                              String content, String reason) {
        int nextVersion = findLatest(type, targetCode).map(v -> v.getVersion() + 1).orElse(1);
        AiConfigVersion entity = new AiConfigVersion();
        entity.setConfigType(type.name());
        entity.setTargetCode(targetCode);
        entity.setTargetId(targetId);
        entity.setVersion(nextVersion);
        entity.setContent(content == null ? "" : content);
        entity.setContentHash(sha256(content));
        entity.setPublishScope(PublishScope.FULL.name());
        entity.setStatus(STATUS_FAILED);
        entity.setRemark(reason);
        versionMapper.insert(entity);
    }

    public PageResult<ConfigVersionVO> page(ConfigVersionPageQuery query) {
        LambdaQueryWrapper<AiConfigVersion> wrapper = new LambdaQueryWrapper<AiConfigVersion>()
            .eq(StringUtils.hasText(query.getConfigType()), AiConfigVersion::getConfigType,
                query.getConfigType() == null ? null : ConfigType.parse(query.getConfigType()).name())
            .eq(StringUtils.hasText(query.getTargetCode()), AiConfigVersion::getTargetCode, query.getTargetCode())
            .orderByDesc(AiConfigVersion::getCreateTime);

        Page<AiConfigVersion> page = versionMapper.selectPage(
            Page.of(query.getPageNum(), query.getPageSize()), wrapper);

        PageResult<ConfigVersionVO> result = new PageResult<>();
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setTotal(page.getTotal());
        // 列表不返回 content：一条快照可能是几十 KB 的 JSON，列表里带着它既慢又没人看
        result.setList(page.getRecords().stream().map(v -> toVO(v, false)).toList());
        return result;
    }

    /** 版本详情仅返回结构化脱敏快照；内部安全回滚读取原始事实后也只能经白名单提取器使用。 */
    public ConfigVersionVO detail(Long id) {
        AiConfigVersion entity = versionMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        ConfigVersionVO vo = toVO(entity, true);
        vo.setContent(snapshotRedactor.redact(entity.getContent()));
        return vo;
    }

    /** 某目标的全部版本（版本选择下拉与对比用）。 */
    public List<ConfigVersionVO> listByTarget(ConfigType type, String targetCode) {
        return versionMapper.selectList(new LambdaQueryWrapper<AiConfigVersion>()
                .eq(AiConfigVersion::getConfigType, type.name())
                .eq(AiConfigVersion::getTargetCode, targetCode)
                .orderByDesc(AiConfigVersion::getVersion))
            .stream().map(v -> toVO(v, false)).toList();
    }

    /** 最新已投递版本；PUBLISHED 不代表实例已 ACK APPLIED。 */
    public Optional<AiConfigVersion> findCurrent(ConfigType type, String targetCode) {
        return Optional.ofNullable(versionMapper.selectOne(new LambdaQueryWrapper<AiConfigVersion>()
            .eq(AiConfigVersion::getConfigType, type.name())
            .eq(AiConfigVersion::getTargetCode, targetCode)
            .eq(AiConfigVersion::getStatus, STATUS_PUBLISHED)
            .orderByDesc(AiConfigVersion::getVersion)
            .last("LIMIT 1")));
    }

    public AiConfigVersion requireVersion(Long id) {
        AiConfigVersion entity = versionMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }
        return entity;
    }

    private Optional<AiConfigVersion> findLatest(ConfigType type, String targetCode) {
        return Optional.ofNullable(versionMapper.selectOne(new LambdaQueryWrapper<AiConfigVersion>()
            .eq(AiConfigVersion::getConfigType, type.name())
            .eq(AiConfigVersion::getTargetCode, targetCode)
            .orderByDesc(AiConfigVersion::getVersion)
            .last("LIMIT 1")));
    }

    private ConfigVersionVO toVO(AiConfigVersion entity, boolean withContent) {
        ConfigVersionVO vo = new ConfigVersionVO();
        BeanUtils.copyProperties(entity, vo);
        if (!withContent) {
            vo.setContent(null);
        }
        return vo;
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，缺失属不可恢复的环境错误
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
