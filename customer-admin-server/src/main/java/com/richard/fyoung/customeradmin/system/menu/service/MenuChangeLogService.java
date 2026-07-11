package com.richard.fyoung.customeradmin.system.menu.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.system.menu.dto.MenuChangeLogVO;
import com.richard.fyoung.customeradmin.system.menu.entity.SysMenuChangeLog;
import com.richard.fyoung.customeradmin.system.menu.mapper.SysMenuChangeLogMapper;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 菜单变更审计流水：{@link com.richard.fyoung.customeradmin.system.permission.service.PermissionService}
 * 每次增/删/改/拖拽移动菜单节点后调用本服务落一条流水，供"菜单管理"页面排查"谁什么时候改了什么"。
 * 只做流水记录与分页查询，不做整树快照、不支持一键回滚（见需求确认）。
 * @author owlzhangfq@gmail.com
 */
@Service
public class MenuChangeLogService {

    private static final Logger log = LoggerFactory.getLogger(MenuChangeLogService.class);

    private final SysMenuChangeLogMapper changeLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MenuChangeLogService(SysMenuChangeLogMapper changeLogMapper) {
        this.changeLogMapper = changeLogMapper;
    }

    /** 记录一条变更流水；序列化/落库失败不影响主流程（审计是辅助能力，不能拖垮菜单编辑本身）。 */
    public void record(Long menuId, String action, SysPermission before, SysPermission after) {
        try {
            SysMenuChangeLog entity = new SysMenuChangeLog();
            entity.setMenuId(menuId);
            entity.setAction(action);
            entity.setBeforeSnapshot(before == null ? null : objectMapper.writeValueAsString(before));
            entity.setAfterSnapshot(after == null ? null : objectMapper.writeValueAsString(after));
            if (StpUtil.isLogin()) {
                entity.setOperatorId(StpUtil.getLoginIdAsLong());
                entity.setOperatorName(StpUtil.getTokenSession().getString("username"));
            }
            entity.setCreateTime(LocalDateTime.now());
            changeLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("record menu change log failed, code={}, menuId={}", "MENU-CHANGE-LOG-RECORD-FAIL", menuId, e);
        }
    }

    /** 按 menuId 过滤（不传则查全量），按时间倒序分页。 */
    public PageResult<MenuChangeLogVO> page(Long menuId, PageQuery query) {
        LambdaQueryWrapper<SysMenuChangeLog> wrapper = new LambdaQueryWrapper<>();
        if (menuId != null) {
            wrapper.eq(SysMenuChangeLog::getMenuId, menuId);
        }
        wrapper.orderByDesc(SysMenuChangeLog::getCreateTime);
        IPage<SysMenuChangeLog> page = changeLogMapper.selectPage(
            new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        IPage<MenuChangeLogVO> voPage = page.convert(this::toVo);
        return PageResult.of(voPage);
    }

    private MenuChangeLogVO toVo(SysMenuChangeLog entity) {
        MenuChangeLogVO vo = new MenuChangeLogVO();
        vo.setId(entity.getId());
        vo.setMenuId(entity.getMenuId());
        vo.setAction(entity.getAction());
        vo.setBeforeSnapshot(entity.getBeforeSnapshot());
        vo.setAfterSnapshot(entity.getAfterSnapshot());
        vo.setOperatorName(entity.getOperatorName());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
