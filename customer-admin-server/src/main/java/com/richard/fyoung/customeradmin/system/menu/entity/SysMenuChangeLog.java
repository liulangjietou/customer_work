package com.richard.fyoung.customeradmin.system.menu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜单变更审计流水：记录每次增/删/改/拖拽移动的操作人、时间、变更前后节点快照（JSON）。
 * 只用于排查"谁什么时候改了什么"，不做整树快照、不支持一键回滚。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("sys_menu_change_log")
public class SysMenuChangeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long menuId;
    /** CREATE / UPDATE / DELETE / MOVE。 */
    private String action;
    private String beforeSnapshot;
    private String afterSnapshot;
    private Long operatorId;
    private String operatorName;
    private LocalDateTime createTime;
}
