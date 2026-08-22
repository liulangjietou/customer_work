package com.richard.fyoung.customeradmin.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessDeliveryPlan;
import com.richard.fyoung.customeradmin.tenant.access.TenantChannelDisableService;
import com.richard.fyoung.customeradmin.tenant.access.dto.TenantAccessDeliveryVO;
import com.richard.fyoung.customeradmin.tenant.access.service.TenantAccessPublishTaskService;
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
import java.util.Objects;

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

    private final SysTenantMapper tenantMapper;
    private final TenantProvisionService provisionService;
    private final TenantAccessPublishTaskService accessPublishTaskService;
    private final SessionRevocationService sessionRevocationService;
    private final TenantChannelDisableService channelDisableService;

    public TenantService(SysTenantMapper tenantMapper, TenantProvisionService provisionService,
                         TenantAccessPublishTaskService accessPublishTaskService,
                         SessionRevocationService sessionRevocationService,
                         TenantChannelDisableService channelDisableService) {
        this.tenantMapper = tenantMapper;
        this.provisionService = provisionService;
        this.accessPublishTaskService = accessPublishTaskService;
        this.sessionRevocationService = sessionRevocationService;
        this.channelDisableService = channelDisableService;
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

    /** 全部可用租户（控制面用户切换视角的下拉数据源）。 */
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
        entity.setTenantCode(TenantContext.normalizedTenantKey(request.getTenantCode()));
        entity.setStatus(TenantStatus.ACTIVE.name());
        entity.setAccessEpoch(0L);
        tenantMapper.insert(entity);

        provisionService.provision(entity.getTenantCode());
        accessPublishTaskService.enqueue(snapshot(entity), TenantAccessDeliveryPlan.provision());
        log.info("tenant created, code={}, name={}", entity.getTenantCode(), entity.getTenantName());
        return entity.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(TenantSaveRequest request) {
        SysTenant existing = requireById(request.getId());
        // 编码是各业务表 tenant_id 的取值来源，改它等于让该租户的存量数据集体失去归属
        if (!TenantContext.sameTenant(existing.getTenantCode(), request.getTenantCode())) {
            throw new BizException(ResultCode.TENANT_CODE_IMMUTABLE);
        }
        SysTenant entity = new SysTenant();
        BeanUtils.copyProperties(request, entity);
        entity.setTenantCode(existing.getTenantCode());
        entity.setStatus(existing.getStatus());
        tenantMapper.updateById(entity);
        boolean expiryChanged = !TenantContext.isDefaultTenant(existing.getTenantCode())
            && !Objects.equals(existing.getExpireTime(), request.getExpireTime());
        if (expiryChanged) {
            requireChanged(tenantMapper.incrementAccessEpoch(existing.getId()));
            SysTenant changed = requireById(existing.getId());
            accessPublishTaskService.enqueue(snapshot(changed), TenantAccessDeliveryPlan.expiryChange());
            sessionRevocationService.revokeTenantAfterCommit(existing.getTenantCode());
        }
        log.info("tenant updated, code={}", entity.getTenantCode());
    }

    /** 冻结/恢复/退租统一入口：只改状态不动数据。 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, TenantStatus target) {
        SysTenant existing = requireById(id);
        assertNotReserved(existing.getTenantCode());
        TenantStatus current = TenantStatus.parse(existing.getStatus());
        if (current == target) {
            return;
        }
        if (current == TenantStatus.TERMINATED) {
            throw new BizException(ResultCode.PARAM_INVALID, "已退租租户不能恢复或再次变更状态");
        }
        requireChanged(tenantMapper.updateStatusAndIncrementAccessEpoch(id, target.name()));
        SysTenant changed = requireById(id);
        TenantAccessDeliveryPlan plan = target == TenantStatus.TERMINATED
            ? TenantAccessDeliveryPlan.offboard(
                channelDisableService.disableForOffboarding(existing.getTenantCode()))
            : TenantAccessDeliveryPlan.statusChange();
        accessPublishTaskService.enqueue(snapshot(changed), plan);
        sessionRevocationService.revokeTenantAfterCommit(existing.getTenantCode());
        log.info("tenant status changed, code={}, status={}", existing.getTenantCode(), target);
    }

    /** 不改生命周期状态，仅轮换访问版本并撤销该租户全部后台会话。 */
    @Transactional(rollbackFor = Exception.class)
    public void revokeSessions(Long id) {
        SysTenant existing = requireById(id);
        assertNotReserved(existing.getTenantCode());
        requireChanged(tenantMapper.incrementAccessEpoch(id));
        SysTenant changed = requireById(id);
        accessPublishTaskService.enqueue(snapshot(changed), TenantAccessDeliveryPlan.sessionRevoke());
        sessionRevocationService.revokeTenantAfterCommit(existing.getTenantCode());
        log.info("tenant sessions revocation requested, code={}, accessEpoch={}",
            changed.getTenantCode(), changed.getAccessEpoch());
    }

    public TenantAccessDeliveryVO latestAccessDelivery(Long id) {
        SysTenant tenant = requireById(id);
        return accessPublishTaskService.latest(tenant.getTenantCode());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysTenant existing = requireById(id);
        assertNotReserved(existing.getTenantCode());
        if (TenantStatus.parse(existing.getStatus()) == TenantStatus.TERMINATED) {
            return;
        }
        requireChanged(tenantMapper.updateStatusAndIncrementAccessEpoch(id, TenantStatus.TERMINATED.name()));
        SysTenant terminated = requireById(id);
        int channelsDisabled = channelDisableService.disableForOffboarding(existing.getTenantCode());
        accessPublishTaskService.enqueue(snapshot(terminated),
            TenantAccessDeliveryPlan.offboard(channelsDisabled));
        // 租户主记录承担退租状态机与交付审计，必须保留；业务数据也不在此处级联删除。
        sessionRevocationService.revokeTenantAfterCommit(existing.getTenantCode());
        log.info("tenant offboarded, code={}", existing.getTenantCode());
    }

    /**
     * 登录与接口调用前的租户可用性校验：不存在、已冻结、已退租、已过期一律拒绝。
     *
     * <p>这是"租户级熔断"的唯一落点——控制面冻结一个租户后，该租户的所有登录立刻失效。</p>
     */
    public void assertAccessible(String tenantCode) {
        requireAccessibleSnapshot(tenantCode);
    }

    /** 返回校验后的权威访问快照；default 保留租户继续遵守既有恒可用语义。 */
    public TenantAccessSnapshot requireAccessibleSnapshot(String tenantCode) {
        if (TenantContext.isDefaultTenant(tenantCode)) {
            return new TenantAccessSnapshot(TenantContext.DEFAULT, TenantStatus.ACTIVE.name(), 0L, null);
        }
        SysTenant tenant = findByCode(tenantCode);
        if (tenant == null) {
            throw new BizException(ResultCode.TENANT_NOT_FOUND);
        }
        if (!TenantStatus.parse(tenant.getStatus()).allowsAccess()) {
            throw new BizException(ResultCode.TENANT_SUSPENDED);
        }
        if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(java.time.LocalDateTime.now())) {
            throw new BizException(ResultCode.TENANT_SUSPENDED, "租户已到期，请联系管理员续约");
        }
        return snapshot(tenant);
    }

    /** 租户编码是否存在且可用（控制面用户切换视角时校验目标租户）。 */
    public boolean existsAccessible(String tenantCode) {
        return resolveAccessibleCode(tenantCode) != null;
    }

    /**
     * 返回数据库保存的权威租户编码；不存在、不可用或已过期时返回 {@code null}。
     * 外部资源命名空间依赖租户编码原始大小写，不能把请求里的数据库等价别名直接写进会话。
     */
    public String resolveAccessibleCode(String tenantCode) {
        if (TenantContext.isDefaultTenant(tenantCode)) {
            return TenantContext.DEFAULT;
        }
        SysTenant tenant = findByCode(tenantCode);
        if (tenant == null || !TenantStatus.parse(tenant.getStatus()).allowsAccess()) {
            return null;
        }
        if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(java.time.LocalDateTime.now())) {
            return null;
        }
        return tenant.getTenantCode();
    }

    /** 控制面切换视角时同时取得应写入会话的 access epoch。 */
    public TenantAccessSnapshot resolveAccessibleSnapshot(String tenantCode) {
        String resolved = resolveAccessibleCode(tenantCode);
        return resolved == null ? null : requireAccessibleSnapshot(resolved);
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
        if (TenantContext.isDefaultTenant(tenantCode)) {
            throw new BizException(ResultCode.TENANT_RESERVED_PROTECTED);
        }
    }

    private TenantAccessSnapshot snapshot(SysTenant tenant) {
        long accessEpoch = tenant.getAccessEpoch() == null ? 0L : tenant.getAccessEpoch();
        return new TenantAccessSnapshot(
            tenant.getTenantCode(), tenant.getStatus(), accessEpoch, tenant.getExpireTime());
    }

    private void requireChanged(int changed) {
        if (changed != 1) {
            throw new BizException(ResultCode.TENANT_NOT_FOUND);
        }
    }

    private TenantVO toVO(SysTenant entity) {
        TenantVO vo = new TenantVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setReserved(TenantContext.isDefaultTenant(entity.getTenantCode()));
        return vo;
    }
}
