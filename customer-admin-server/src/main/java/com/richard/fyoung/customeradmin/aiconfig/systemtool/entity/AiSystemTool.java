package com.richard.fyoung.customeradmin.aiconfig.systemtool.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统工具目录。工具实现是代码定义的（{@code tool_code} 精确对应一个 Spring Bean 名），
 * 库里只存"该工具是否启用 + 展示用的名称/描述/备注"，不存工具逻辑本身，因此不支持 UI 新建/删除。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_system_tool")
public class AiSystemTool {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具编码（唯一），运行时按此值 {@code ApplicationContext.getBean(toolCode)} 取工具 Bean，不可修改。 */
    private String toolCode;
    private String toolName;
    private String description;
    /** 0禁用 / 1启用。 */
    private Integer enabled;
    private String remark;

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
