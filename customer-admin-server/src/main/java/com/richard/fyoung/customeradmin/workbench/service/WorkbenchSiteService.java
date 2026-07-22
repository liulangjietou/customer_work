package com.richard.fyoung.customeradmin.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteSaveRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteVO;
import com.richard.fyoung.customeradmin.workbench.entity.WorkbenchSite;
import com.richard.fyoung.customeradmin.workbench.mapper.WorkbenchSiteMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 内网工作台系统账号本：CRUD + 密码加密存储/留空不改/脱敏回显 + 明文密码按需解密（供前端复制）。
 *
 * <p>密码处理沿用 {@code SqlDatasourceService} 的手法（AES/GCM 密文入库，列表 VO 回显
 * {@code mask(decrypt(...))}；明文只在 {@link #getSecret} 敏感读接口按需解密）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class WorkbenchSiteService {

    private static final int ENABLED = 1;

    private final WorkbenchSiteMapper siteMapper;
    private final AesGcmCryptoUtil cryptoUtil;

    public WorkbenchSiteService(WorkbenchSiteMapper siteMapper, AesGcmCryptoUtil cryptoUtil) {
        this.siteMapper = siteMapper;
        this.cryptoUtil = cryptoUtil;
    }

    public PageResult<WorkbenchSiteVO> page(PageQuery query) {
        LambdaQueryWrapper<WorkbenchSite> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(WorkbenchSite::getName, query.getKeyword())
                .or().like(WorkbenchSite::getCategory, query.getKeyword()));
        }
        if (query.getStatus() != null) {
            wrapper.eq(WorkbenchSite::getEnabled, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), WorkbenchSite::getCreateTime);

        IPage<WorkbenchSite> page = siteMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toVo));
    }

    public void create(WorkbenchSiteSaveRequest request) {
        requireNameAvailable(request.name(), null);
        WorkbenchSite site = new WorkbenchSite();
        fillFromRequest(site, request);
        if (StringUtils.hasText(request.password())) {
            site.setPassword(cryptoUtil.encrypt(request.password()));
        }
        siteMapper.insert(site);
    }

    public void update(Long id, WorkbenchSiteSaveRequest request) {
        WorkbenchSite site = requireSite(id);
        requireNameAvailable(request.name(), id);
        fillFromRequest(site, request);
        // 密码留空=不修改，沿用原密文，避免每次编辑都要重输明文
        if (StringUtils.hasText(request.password())) {
            site.setPassword(cryptoUtil.encrypt(request.password()));
        }
        siteMapper.updateById(site);
    }

    public void delete(Long id) {
        requireSite(id);
        siteMapper.deleteById(id);
    }

    /** 解密返回明文密码（供前端"复制密码"用）。记录不存在或未配置密码时 fast fail。 */
    public String getSecret(Long id) {
        WorkbenchSite site = requireSite(id);
        if (!StringUtils.hasText(site.getPassword())) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "该站点未配置密码: " + id);
        }
        return cryptoUtil.decrypt(site.getPassword());
    }

    private void requireNameAvailable(String name, Long excludeId) {
        LambdaQueryWrapper<WorkbenchSite> wrapper = new LambdaQueryWrapper<WorkbenchSite>()
            .eq(WorkbenchSite::getName, name);
        if (excludeId != null) {
            wrapper.ne(WorkbenchSite::getId, excludeId);
        }
        if (siteMapper.exists(wrapper)) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "站点名称已存在: " + name);
        }
    }

    private void fillFromRequest(WorkbenchSite site, WorkbenchSiteSaveRequest request) {
        site.setName(request.name());
        site.setCategory(request.category());
        site.setUrl(request.url());
        site.setAccount(request.account());
        site.setRemark(request.remark());
        site.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : ENABLED);
    }

    private WorkbenchSiteVO toVo(WorkbenchSite site) {
        WorkbenchSiteVO vo = new WorkbenchSiteVO();
        vo.setId(site.getId());
        vo.setName(site.getName());
        vo.setCategory(site.getCategory());
        vo.setUrl(site.getUrl());
        vo.setAccount(site.getAccount());
        boolean hasPassword = StringUtils.hasText(site.getPassword());
        vo.setHasPassword(hasPassword);
        vo.setPasswordMasked(hasPassword ? AesGcmCryptoUtil.mask(cryptoUtil.decrypt(site.getPassword())) : "");
        vo.setRemark(site.getRemark());
        vo.setEnabled(site.getEnabled() != null && site.getEnabled() == ENABLED);
        vo.setCreateTime(site.getCreateTime());
        vo.setUpdateTime(site.getUpdateTime());
        return vo;
    }

    private WorkbenchSite requireSite(Long id) {
        WorkbenchSite site = siteMapper.selectById(id);
        if (site == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "站点不存在: " + id);
        }
        return site;
    }
}
