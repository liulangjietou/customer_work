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

    /** 凭据引用归属租户；显式携带以支持 SecretRef 解析与跨租户迁移。 */
    private String tenantId;

    private String mcpName;
    /** stdio / sse / http。 */
    private String mcpType;
    /** 连接配置（命令/URL/参数等，JSON）。 */
    private String config;
    /** config 中敏感叶子的加密材料引用；config 本身只保留不可执行占位符。 */
    private Long secretRefId;
    private String description;
    /** 0禁用 / 1启用。 */
    private Integer status;
    /** 0未测试 / 1成功 / 2失败。 */
    private Integer testStatus;
    private LocalDateTime testTime;
    /** 允许调用该 MCP 工具的主体类型，逗号分隔；服务端执行期强制判定。 */
    private String allowedSubjectTypes;

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
