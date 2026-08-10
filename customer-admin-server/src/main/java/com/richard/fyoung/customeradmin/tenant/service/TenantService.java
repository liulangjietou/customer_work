package com.richard.fyoung.customeradmin.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.dto.TenantPageQuery;
import com.richard.fyoung.customeradmin.tenant.dto.TenantSaveRequest;
import com.richard.fyoung.customeradmin.tenant.dto.TenantVO;
import com.richard.fyoung.customeradmin.tenant.entity.SysTenant;
import com.richard.fyoung.customeradmin.tenant.entity.TenantStatus;
import com.richard.fyoung.customeradmin.tenant.mapper.SysTenantMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 租户主数据与生命周期。
 *
 * <p>本服务只管租户这一实体本身；新租户的角色与管理员账号初始化交给
 * {@link TenantProvisionService}——那是"开通"动作，涉及 RBAC 多张表，与租户 CRUD 是两件事。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class TenantService {

    /** 系统保留租户：不允许改编码、不允许冻结/退租/删除，否则存量数据会失去归属或平台自身被锁死。 */
    private static final Set<String> RESERVED_TENANTS = Set.of(TenantContext.DEFAULT, TenantContext.PLATFORM);

    private final SysTenantMapper tenantMapper;
    private final TenantProvisionService provisionService;

    public TenantService(SysTenantMapper tenantMapper, TenantProvisionService provisionService) {
        this.tenantMapper = tenantMapper;
        this.provisionService = provisionService;
    }

    public PageResult<TenantVO> page(TenantPageQuery query) {
        LambdaQueryWrapper<SysTenant> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w.like(SysTenant::getTenantName, query.getKeyword())
                .or().like(SysTenant::getTenantCode, query.getKeyword()));
        }
        if (StringUtils.hasText(query.getTenantStatus())) {
            wrapper.eq(SysTenant::getStatus, TenantStatus.parse(query.getTenantStatus()).name());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), SysTenant::getCreateTime);

        Page<SysTenant> page = tenantMapper.selectPage(
            Page.of(query.getPageNum(), query.getPageSize()), wrapper);

        PageResult<TenantVO> result = new PageResult<>();
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        result.setTotal(page.getTotal());
        result.setList(page.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    /** 全部可用租户（运营方切换视角的下拉数据源）。 */
    public List<TenantVO> listActive() {
        return tenantMapper.selectList(new LambdaQueryWrapper<SysTenant>()
                .eq(SysTenant::getStatus, TenantStatus.ACTIVE.name())
                .orderByAsc(SysTenant::getTenantCode))
            .stream().map(this::toVO).toList();
    }

    public TenantVO get(Long id) {
        return toVO(requireById(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(TenantSaveRequest request) {
        if (findByCode(request.getTenantCode()) != null) {
            throw new BizException(ResultCode.TENANT_CODE_DUPLICATE);
        }
        SysTenant entity = new SysTenant();
        BeanUtils.copyProperties(request, entity);
        entity.setId(null);
        entity.setStatus(TenantStatus.ACTIVE.name());
        tenantMapper.insert(entity);

        provisionService.provision(entity.getTenantCode());
        log.info("tenant created, code={}, name={}", entity.getTenantCode(), entity.getTenantName());
        return entity.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(TenantSaveRequest request) {
        SysTenant existing = requireById(request.getId());
        // 编码是各业务表 tenant_id 的取值来源，改它等于让该租户的存量数据集体失去归属
        if (!existing.getTenantCode().equals(request.getTenantCode())) {
            throw new BizException(ResultCode.TENANT_CODE_IMMUTABLE);
        }
        SysTenant entity = new SysTenant();
        BeanUtils.copyProperties(request, entity);
        entity.setStatus(existing.getStatus());
        tenantMapper.updateById(entity);
        log.info("tenant updated, code={}", entity.getTenantCode());
    }

    /** 冻结/恢复/退租统一入口：只改状态不动数据。 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, TenantStatus target) {
        SysTenant existing = requireById(id);
        assertNotReserved(existing.getTenantCode());

        SysTenant update = new SysTenant();
        update.setId(id);
        update.setStatus(target.name());
        tenantMapper.updateById(update);
        log.info("tenant status changed, code={}, status={}", existing.getTenantCode(), target);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysTenant existing = requireById(id);
        assertNotReserved(existing.getTenantCode());
        // 逻辑删除：租户下的业务数据仍在库里，物理清理属于退租数据主权流程，不在这里做
        tenantMapper.deleteById(id);
        log.info("tenant deleted (logical), code={}", existing.getTenantCode());
    }

    /**
     * 登录与接口调用前的租户可用性校验：不存在、已冻结、已退租、已过期一律拒绝。
     *
     * <p>这是"租户级熔断"的唯一落点——运营方冻结一个租户后，该租户的所有登录立刻失效。</p>
     */
    public void assertAccessible(String tenantCode) {
        // 平台自身不受租户生命周期约束，否则运营方会把自己锁在门外
        if (TenantContext.PLATFORM.equals(tenantCode)) {
            return;
        }
        SysTenant tenant = findByCode(tenantCode);
        if (tenant == null) {
            throw new BizException(ResultCode.TENANT_NOT_FOUND);
        }
        if (!TenantStatus.parse(tenant.getStatus()).allowsAccess()) {
            throw new BizException(ResultCode.TENANT_SUSPENDED);
        }
        if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(java.time.LocalDateTime.now())) {
            throw new BizException(ResultCode.TENANT_SUSPENDED, "租户已到期，请联系运营方续约");
        }
    }

    /** 租户编码是否存在且可用（运营方切换视角时校验目标租户）。 */
    public boolean existsAccessible(String tenantCode) {
        if (TenantContext.PLATFORM.equals(tenantCode)) {
            return true;
        }
        SysTenant tenant = findByCode(tenantCode);
        return tenant != null && TenantStatus.parse(tenant.getStatus()).allowsAccess();
    }

    public SysTenant findByCode(String tenantCode) {
        if (!StringUtils.hasText(tenantCode)) {
            return null;
        }
        return tenantMapper.selectOne(
            new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getTenantCode, tenantCode));
    }

    private SysTenant requireById(Long id) {
        SysTenant tenant = id == null ? null : tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BizException(ResultCode.TENANT_NOT_FOUND);
        }
        return tenant;
    }

    private void assertNotReserved(String tenantCode) {
        if (RESERVED_TENANTS.contains(tenantCode)) {
            throw new BizException(ResultCode.TENANT_RESERVED_PROTECTED);
        }
    }

    private TenantVO toVO(SysTenant entity) {
        TenantVO vo = new TenantVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setReserved(RESERVED_TENANTS.contains(entity.getTenantCode()));
        return vo;
    }
}
