package com.richard.fyoung.customeradmin.system.permission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.menu.service.MenuVersionHolder;
import com.richard.fyoung.customeradmin.system.menu.dto.MenuReorderRequest;
import com.richard.fyoung.customeradmin.system.menu.service.MenuChangeLogService;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionSaveRequest;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionVO;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customerwork.lock.DistributedLockExecutor;
import com.richard.fyoung.customerwork.lock.LockAcquireTimeoutException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限/菜单树管理（需求文档"二、菜单规划"静态菜单来源）。
 *
 * <p>菜单管理页面的"改动即时生效，发布=广播通知"模型：{@link #create}/{@link #update}/
 * {@link #delete}/{@link #reorder} 都直接落库、立刻对"重新拉一次树"的人可见；但不会主动
 * {@link MenuVersionHolder#bump()}——真正让其它在线用户前端轮询感知到变化、自动刷新菜单，
 * 要等管理员编辑完一批改动后显式调 {@link #publish()} 广播一次，避免半成品改动中途闪现给
 * 其他在线用户。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class PermissionService {

    private static final long ROOT_PARENT_ID = 0L;
    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_DELETE = "DELETE";
    private static final String ACTION_MOVE = "MOVE";
    private static final String DEFAULT_ICON_TYPE = "library";
    /** 菜单树全局互斥锁：拖拽排序影响面可能跨越树上任意层级，粒度按"整棵树"而非单个节点，
     * 避免两个管理员分别调整不相交子树时，各自基于过期的兄弟节点 sort 值算出的新序号仍然冲突。 */
    private static final String REORDER_LOCK_KEY = "admin:menu:reorder:lock";
    /** 不等待，立即失败（fast fail）：宁可让第二个管理员的拖拽操作直接提示"稍后重试"，
     * 也不让请求线程排队阻塞。 */
    private static final Duration REORDER_LOCK_WAIT = Duration.ZERO;
    /** 持锁上限，覆盖一次拖拽排序涉及节点数的更新耗时；超时自动释放，防止持锁方异常退出后锁不释放。 */
    private static final Duration REORDER_LOCK_LEASE = Duration.ofSeconds(10);

    private final SysPermissionMapper permissionMapper;
    private final MenuChangeLogService changeLogService;
    private final MenuVersionHolder menuVersionHolder;
    private final DistributedLockExecutor lockExecutor;
    private final PermissionService self;

    public PermissionService(SysPermissionMapper permissionMapper, MenuChangeLogService changeLogService,
                             MenuVersionHolder menuVersionHolder, DistributedLockExecutor lockExecutor,
                             @Lazy PermissionService self) {
        this.permissionMapper = permissionMapper;
        this.changeLogService = changeLogService;
        this.menuVersionHolder = menuVersionHolder;
        this.lockExecutor = lockExecutor;
        this.self = self;
    }

    /** 全量权限树，按 sort 升序。 */
    public List<PermissionVO> tree() {
        List<SysPermission> all = permissionMapper.selectList(null);
        Map<Long, PermissionVO> nodeById = new LinkedHashMap<>();
        for (SysPermission p : all) {
            nodeById.put(p.getId(), toVo(p));
        }
        List<PermissionVO> roots = new java.util.ArrayList<>();
        for (SysPermission p : all) {
            PermissionVO node = nodeById.get(p.getId());
            if (p.getParentId() == null || p.getParentId() == ROOT_PARENT_ID) {
                roots.add(node);
            } else {
                PermissionVO parent = nodeById.get(p.getParentId());
                if (parent != null) {
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);   // 父节点缺失（数据异常）时兜底挂根，不丢数据
                }
            }
        }
        sortTree(roots);
        return roots;
    }

    public void create(PermissionSaveRequest request) {
        if (permissionMapper.exists(new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getPermCode, request.permCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "权限标识已存在");
        }
        SysPermission p = new SysPermission();
        fillFromRequest(p, request);
        permissionMapper.insert(p);
        changeLogService.record(p.getId(), ACTION_CREATE, null, p);
    }

    public void update(Long id, PermissionSaveRequest request) {
        SysPermission before = requirePermission(id);
        SysPermission after = copyOf(before);
        fillFromRequest(after, request);
        permissionMapper.updateById(after);
        changeLogService.record(id, ACTION_UPDATE, before, after);
    }

    public void delete(Long id) {
        SysPermission before = requirePermission(id);
        boolean hasChildren = permissionMapper.exists(
            new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getParentId, id));
        if (hasChildren) {
            throw new BizException(ResultCode.PARAM_INVALID, "该节点下还有子节点，请先删除子节点");
        }
        permissionMapper.deleteById(id);
        changeLogService.record(id, ACTION_DELETE, before, null);
    }

    /**
     * 拖拽排序入口：先抢 {@link #REORDER_LOCK_KEY} 分布式锁再落库，串行化多个管理员的并发拖拽，
     * 避免后提交的一批更新基于过期的兄弟节点 sort 值覆盖先提交的结果（乱序合并）。拿不到锁直接
     * 转换为 {@link ResultCode#MENU_REORDER_CONFLICT} 业务错误，不排队等待。
     *
     * <p>真正落库经 {@link #self} 转一次自注入代理调用 {@link #applyReorder}——{@code @Transactional}
     * 是 Spring AOP 代理拦截的，本类内部直接 {@code this.applyReorder(...)} 属于自调用会绕过代理导致
     * 事务不生效；同时这个转发顺序也保证了锁释放严格发生在事务提交之后：{@link DistributedLockExecutor#execute}
     * 的 finally 解锁在 {@code action.get()}（即 {@code self.applyReorder(...)} 这次代理调用，事务已随之
     * 提交完毕）返回之后才执行，不会出现"锁已释放但改动还没提交"的窗口期。</p>
     */
    public void reorder(MenuReorderRequest request) {
        try {
            lockExecutor.execute(REORDER_LOCK_KEY, REORDER_LOCK_WAIT, REORDER_LOCK_LEASE,
                () -> self.applyReorder(request));
        } catch (LockAcquireTimeoutException e) {
            throw new BizException(ResultCode.MENU_REORDER_CONFLICT);
        }
    }

    /** 拖拽排序落库：逐条更新受影响节点的 parentId/sort；只对 parentId 真变化的节点记 MOVE 流水（同层纯调顺序不算移动）。 */
    @Transactional
    public void applyReorder(MenuReorderRequest request) {
        for (MenuReorderRequest.Item item : request.items()) {
            SysPermission before = requirePermission(item.id());
            boolean parentChanged = !before.getParentId().equals(item.parentId());
            SysPermission after = copyOf(before);
            after.setParentId(item.parentId());
            after.setSort(item.sort());
            permissionMapper.updateById(after);
            if (parentChanged) {
                changeLogService.record(item.id(), ACTION_MOVE, before, after);
            }
        }
    }

    /** 广播菜单版本变化，让其它在线用户的前端轮询感知到并自动刷新（"发布"按钮语义）。 */
    public void publish() {
        menuVersionHolder.bump();
    }

    private void fillFromRequest(SysPermission p, PermissionSaveRequest request) {
        p.setParentId(request.parentId() == null ? ROOT_PARENT_ID : request.parentId());
        p.setPermName(request.permName());
        p.setPermCode(request.permCode());
        p.setType(request.type());
        p.setPath(request.path());
        p.setIcon(request.icon());
        p.setIconType(request.iconType() == null ? DEFAULT_ICON_TYPE : request.iconType());
        p.setSort(request.sort() == null ? 0 : request.sort());
    }

    private SysPermission copyOf(SysPermission source) {
        SysPermission copy = new SysPermission();
        copy.setId(source.getId());
        copy.setParentId(source.getParentId());
        copy.setPermName(source.getPermName());
        copy.setPermCode(source.getPermCode());
        copy.setType(source.getType());
        copy.setPath(source.getPath());
        copy.setIcon(source.getIcon());
        copy.setIconType(source.getIconType());
        copy.setSort(source.getSort());
        return copy;
    }

    private void sortTree(List<PermissionVO> nodes) {
        nodes.sort(Comparator.comparing(PermissionVO::getSort, Comparator.nullsLast(Integer::compareTo)));
        for (PermissionVO node : nodes) {
            sortTree(node.getChildren());
        }
    }

    private PermissionVO toVo(SysPermission p) {
        PermissionVO vo = new PermissionVO();
        vo.setId(p.getId());
        vo.setParentId(p.getParentId());
        vo.setPermName(p.getPermName());
        vo.setPermCode(p.getPermCode());
        vo.setType(p.getType());
        vo.setPath(p.getPath());
        vo.setIcon(p.getIcon());
        vo.setIconType(p.getIconType());
        vo.setSort(p.getSort());
        return vo;
    }

    private SysPermission requirePermission(Long id) {
        SysPermission p = permissionMapper.selectById(id);
        if (p == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "权限节点不存在: " + id);
        }
        return p;
    }
}
