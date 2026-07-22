package com.richard.fyoung.customeradmin.workbench.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内网工作台系统账号本。{@code password} 为 AES/GCM 密文，永不通过接口原样返回
 * （{@code @JsonIgnore} 兜底，回显走 {@code WorkbenchSiteVO.passwordMasked}，
 * 明文只在 {@code /secret} 敏感读接口按需解密）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("workbench_site")
public class WorkbenchSite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    /** 分类：如 git/jenkins/oa。 */
    private String category;
    private String url;
    private String account;
    @JsonIgnore
    private String password;
    private String remark;
    /** 0禁用 / 1启用。 */
    private Integer enabled;

    // ===== 自动登录配置（供 ScriptCat 通用脚本使用，全部可空/带默认，留空走脚本内启发式）=====
    /** 用户名输入框 CSS 选择器，留空用启发式。 */
    private String usernameSelector;
    /** 密码输入框 CSS 选择器，留空用 input[type=password]。 */
    private String passwordSelector;
    /** 登录按钮 CSS 选择器，留空用启发式。 */
    private String submitSelector;
    /** 填充模式：auto=原生 setter 一次性 / typing=逐字模拟（顽固 React 如 Kibana）。 */
    private String fillMode;
    /** 提交方式：click=点按钮 / formSubmit=表单提交。 */
    private String submitMode;
    /** 进页面后开始查找元素的延迟毫秒。 */
    private Integer initDelayMs;
    /** 填完到点击提交的延迟毫秒。 */
    private Integer submitDelayMs;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
