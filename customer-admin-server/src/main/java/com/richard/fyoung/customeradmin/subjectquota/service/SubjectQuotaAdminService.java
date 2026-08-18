package com.richard.fyoung.customeradmin.subjectquota.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.subjectquota.config.SubjectQuotaGatewayProvider;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaHitVO;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaLevelSaveRequest;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaLevelVO;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaUserVO;
import com.richard.fyoung.customeradmin.subjectquota.jdbc.SubjectQuotaGateway;
import com.richard.fyoung.customerwork.data.user.entity.UserDO;
import com.richard.fyoung.customerwork.data.user.mapper.UserMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.MybatisSubjectQuotaHitStore;
import com.richard.fyoung.customerwork.safety.subjectquota.MybatisSubjectQuotaLevelStore;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectExceedAction;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHit;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHitRank;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaHitStore;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevel;
import com.richard.fyoung.customerwork.safety.subjectquota.SubjectQuotaLevelStore;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台的主体配额维护：直接读写客服端库的三张表，客服端轮询等级快照即生效。
 *
 * <p>复用 starter 的 {@link MybatisSubjectQuotaLevelStore} / {@link MybatisSubjectQuotaHitStore}
 * 而不是重写一套 CRUD——同一张表、同一套解析（超限处置的脏值兜底、窗口的默认值），
 * 重写只会多出一份要同步维护的代码，且两边对同一行算出不同结果是最难查的 bug。</p>
 *
 * <p><b>生效延迟是设计的一部分</b>：等级定义由客服端按指纹轮询（默认 60 秒）换快照，
 * 用户等级绑定由 {@code SubjectLevelResolver} 的本地缓存兜住（默认 60 秒）。
 * 改完不是立刻生效，页面上必须把这件事写给运营看，否则会被当成故障反复重试。</p>
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
public class SubjectQuotaAdminService {

    /** 命中查询的默认回看时长与条数上限，避免一次拉穿整张表。 */
    private static final int MAX_HIT_LIMIT = 500;
    private static final long HOUR_MS = 3600_000L;

    private final SubjectQuotaGatewayProvider gatewayProvider;

    public SubjectQuotaAdminService(SubjectQuotaGatewayProvider gatewayProvider) {
        this.gatewayProvider = gatewayProvider;
    }

    // ---------- 等级 ----------

    public List<SubjectQuotaLevelVO> listLevels(String tenantId) {
        return levelStore().findByTenant(tenantId).stream()
            .map(SubjectQuotaAdminService::toVO)
            .toList();
    }

    public void saveLevel(String tenantId, SubjectQuotaLevelSaveRequest request) {
        SubjectQuotaLevel level = new SubjectQuotaLevel(
            null,
            tenantId,
            request.getLevelCode(),
            request.getLevelName(),
            QuotaSubjectType.parse(request.getSubjectType()),
            request.getWindowSeconds() == null
                ? SubjectQuotaLevel.DEFAULT_WINDOW_SECONDS : request.getWindowSeconds(),
            request.getTokenLimit() == null ? 0L : request.getTokenLimit(),
            request.getRequestLimit() == null ? 0 : request.getRequestLimit(),
            SubjectExceedAction.parse(request.getExceedAction()),
            request.getEnabled() == null || request.getEnabled(),
            request.getRemark());
        levelStore().save(level);
        log.info("subject quota level saved, tenant={}, level={}, window={}s, token={}, request={}, action={}",
            tenantId, level.levelCode(), level.effectiveWindowSeconds(), level.tokenLimit(),
            level.requestLimit(), level.exceedAction());
    }

    public void deleteLevel(String tenantId, String levelCode) {
        levelStore().delete(tenantId, levelCode);
        log.info("subject quota level deleted, tenant={}, level={}", tenantId, levelCode);
    }

    // ---------- 用户等级分配 ----------

    /**
     * 用户列表（带当前等级）。
     *
     * <p>手写 LIMIT 而非 {@code selectPage}：跨库门面用的是独立的 SqlSessionFactory，
     * 那里没有装分页插件，{@code selectPage} 会静默返回全表（照 {@code SensitiveWordHitLogService} 的先例）。</p>
     */
    public PageResult<SubjectQuotaUserVO> pageUsers(String tenantId, PageQuery query) {
        UserMapper mapper = gatewayProvider.get().userMapper();
        long size = Math.max(1, query.getPageSize());
        long offset = Math.max(0, (query.getPageNum() - 1) * size);

        long total = mapper.selectCount(userWrapper(tenantId, query.getKeyword()));
        List<SubjectQuotaUserVO> list = new ArrayList<>();
        if (total > 0) {
            List<UserDO> rows = mapper.selectList(userWrapper(tenantId, query.getKeyword())
                .orderByDesc("created_at_ms")
                .last("LIMIT " + size + " OFFSET " + offset));
            for (UserDO row : rows) {
                list.add(toVO(row));
            }
        }
        PageResult<SubjectQuotaUserVO> result = new PageResult<>();
        result.setPageNum(query.getPageNum());
        result.setPageSize(size);
        result.setTotal(total);
        result.setList(list);
        return result;
    }

    /**
     * 分配用户等级。
     *
     * <p>显式 {@code SET level_code = ?} 而不是 {@code updateById}：后者只更新非空字段，
     * 用它把等级置空（取消特批）会静默失败，表现为"后台看着已经取消、线上还按特批放行"。</p>
     */
    public void assignUserLevel(String tenantId, String userId, String levelCode) {
        String normalized = levelCode == null || levelCode.isBlank() ? null : levelCode.trim();
        assertLevelExists(tenantId, normalized);
        // UpdateWrapper.set 对 null 会生成 SET level_code = ? 并绑定 null，这正是"取消特批"要的效果；
        // 租户条件不能省：少了它就能改到别的租户的用户
        int updated = gatewayProvider.get().userMapper().update(null,
            new UpdateWrapper<UserDO>()
                .set("level_code", normalized)
                .eq("tenant_id", tenantId)
                .eq("id", userId));
        log.info("subject quota user level assigned, tenant={}, user={}, level={}, updated={}",
            tenantId, userId, normalized, updated);
    }

    /**
     * 校验目标等级确实存在（空值表示回默认档，不校验）。
     *
     * <p>不校验的话，配错一个字母的等级码会让这个人静默落回默认档——页面上显示着"已设为 vip"，
     * 线上按 free 拦，且没有任何地方会报错。</p>
     *
     * <p>本租户与平台默认租户都算数：运行时的 {@code SubjectLevelResolver} 就是这么回落的
     * （租户没配某档时用平台的同名档）。这里只认本租户，会把一个运行时完全合法的等级判成不存在。</p>
     */
    private void assertLevelExists(String tenantId, String levelCode) {
        if (levelCode == null) {
            return;
        }
        if (hasLevel(tenantId, levelCode)) {
            return;
        }
        if (!TenantContext.DEFAULT.equals(tenantId) && hasLevel(TenantContext.DEFAULT, levelCode)) {
            return;
        }
        throw new BizException(ResultCode.PARAM_INVALID, "等级不存在：" + levelCode);
    }

    private boolean hasLevel(String tenantId, String levelCode) {
        return levelStore().findByTenant(tenantId).stream()
            .anyMatch(level -> level.levelCode().equals(levelCode));
    }

    // ---------- 命中记录 ----------

    /** 最近 N 小时的命中明细（时间倒序）。 */
    public List<SubjectQuotaHitVO> listHits(String tenantId, int hours, int limit) {
        return hitStore().findRecent(tenantId, sinceMs(hours), cappedLimit(limit)).stream()
            .map(SubjectQuotaAdminService::toVO)
            .toList();
    }

    /** 最近 N 小时的命中排行（"谁在刷"）。 */
    public List<SubjectQuotaHitRank> rankHits(String tenantId, int hours, int limit) {
        return hitStore().rank(tenantId, sinceMs(hours), cappedLimit(limit));
    }

    // ---------- 内部 ----------

    /** 每次取新的 Store：门面本身是惰性缓存的，这里只是把它包成 Store 语义，无额外开销。 */
    private SubjectQuotaLevelStore levelStore() {
        return new MybatisSubjectQuotaLevelStore(gatewayProvider.get().levelMapper());
    }

    private SubjectQuotaHitStore hitStore() {
        return new MybatisSubjectQuotaHitStore(gatewayProvider.get().hitMapper());
    }

    /**
     * 用户查询条件。
     *
     * <p>租户列用字符串列名而不是 {@code UserDO} 字段：跨库门面没有租户拦截器，
     * 而 DO 本身也不持有 {@code tenantId}（starter 侧靠拦截器自动补），只能显式写列名。
     * 漏掉这个条件就是跨租户看到别人的用户名单。</p>
     */
    private static QueryWrapper<UserDO> userWrapper(String tenantId, String keyword) {
        QueryWrapper<UserDO> wrapper = new QueryWrapper<UserDO>().eq("tenant_id", tenantId);
        if (keyword != null && !keyword.isBlank()) {
            String like = keyword.trim();
            wrapper.and(w -> w.like("username", like).or().like("nickname", like));
        }
        return wrapper;
    }

    private static long sinceMs(int hours) {
        int effective = hours <= 0 ? 24 : hours;
        return System.currentTimeMillis() - effective * HOUR_MS;
    }

    private static int cappedLimit(int limit) {
        return limit <= 0 ? 100 : Math.min(limit, MAX_HIT_LIMIT);
    }

    private static SubjectQuotaLevelVO toVO(SubjectQuotaLevel level) {
        SubjectQuotaLevelVO vo = new SubjectQuotaLevelVO();
        vo.setTenantId(level.tenantId());
        vo.setLevelCode(level.levelCode());
        vo.setLevelName(level.levelName());
        vo.setSubjectType(level.subjectType().name());
        vo.setWindowSeconds(level.effectiveWindowSeconds());
        vo.setTokenLimit(level.tokenLimit());
        vo.setRequestLimit(level.requestLimit());
        vo.setExceedAction(level.exceedAction().name());
        vo.setEnabled(level.enabled());
        vo.setRemark(level.remark());
        return vo;
    }

    private static SubjectQuotaUserVO toVO(UserDO row) {
        SubjectQuotaUserVO vo = new SubjectQuotaUserVO();
        vo.setUserId(row.getId());
        vo.setUsername(row.getUsername());
        vo.setNickname(row.getNickname());
        vo.setLevelCode(row.getLevelCode());
        vo.setStatus(row.getStatus());
        vo.setCreatedAtMs(row.getCreatedAtMs());
        return vo;
    }

    private static SubjectQuotaHitVO toVO(SubjectQuotaHit hit) {
        SubjectQuotaHitVO vo = new SubjectQuotaHitVO();
        vo.setSubjectType(hit.subjectType().name());
        vo.setSubjectId(hit.subjectId());
        vo.setLevelCode(hit.levelCode());
        vo.setLimitKind(hit.limitKind() == null ? null : hit.limitKind().name());
        vo.setUsed(hit.used());
        vo.setLimitValue(hit.limitValue());
        vo.setWindowSeconds(hit.windowSeconds());
        vo.setAction(hit.action() == null ? null : hit.action().name());
        vo.setResource(hit.resource());
        vo.setCreatedAtMs(hit.createdAtMs());
        return vo;
    }
}
