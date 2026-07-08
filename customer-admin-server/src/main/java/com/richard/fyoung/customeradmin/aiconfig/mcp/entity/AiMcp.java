package com.richard.fyoung.customeradmin.aiconfig.mcp.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 配置。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_mcp")
public class AiMcp {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String mcpName;
    /** stdio / sse。 */
    private String mcpType;
    /** 连接配置（命令/URL/参数等，JSON）。 */
    private String config;
    private String description;
    /** 0禁用 / 1启用。 */
    private Integer status;

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
