package com.richard.fyoung.customeradmin.menu.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点：静态菜单来自 {@code sys_permission}（type=1），动态智能体节点
 * （{@code agentCode}/{@code capabilities}/{@code dynamic=true}）由 {@code MenuAggregationService}
 * 在批次三补齐拼进 workspace 节点，不落库。
 * @author owlzhangfq@gmail.com
 */
@Data
public class MenuNode {
    private Long id;
    private String name;
    private String path;
    private String icon;
    /** library=图标库图标名 / image=上传图片URL；默认 library，兼容历史未回填的静态菜单数据。 */
    private String iconType = "library";
    private String permCode;
    private Integer sort;
    /** 动态智能体节点专用：批次三补齐。 */
    private String agentCode;
    private List<String> capabilities;
    private boolean dynamic = false;
    private List<MenuNode> children = new ArrayList<>();
}
