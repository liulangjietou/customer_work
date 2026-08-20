package com.richard.fyoung.customeradmin.common.constant;

/**
 * 树形结构（菜单树 / 权限树）的公共约定。
 *
 * @author owlzhangfq@gmail.com
 */
public final class TreeConstants {

    /**
     * 根节点的 {@code parent_id}。
     *
     * <p>建树时 {@code parentId == null} 与 {@code parentId == 0} 都当根处理——
     * 历史数据两种写法都有，权限树与菜单聚合必须按同一个值判定，否则同一批数据在两个页面上会挂出不同的层级。</p>
     */
    public static final long ROOT_PARENT_ID = 0L;

    private TreeConstants() {
    }
}
