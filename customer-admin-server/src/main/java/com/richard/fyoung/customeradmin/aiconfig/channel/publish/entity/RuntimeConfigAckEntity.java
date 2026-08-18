package com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 单个客服实例对某个 revision 的应用回执。 */
@Data
@TableName("ai_runtime_config_ack")
public class RuntimeConfigAckEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String revision;
    private String contentHash;
    private String instanceId;
    private String status;
    private String reason;
    private Long appliedAtMs;
    private Long createdAtMs;
    private Long updatedAtMs;
}
