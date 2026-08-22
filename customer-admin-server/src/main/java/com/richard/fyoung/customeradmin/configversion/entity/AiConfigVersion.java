package com.richard.fyoung.customeradmin.configversion.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置发布版本快照。
 *
 * <p>发布历史只增不改。安全回滚只从旧快照提取行为白名单，以当前权威运行资产重组后产生新发布；
 * 实例是否真实生效以可靠发布任务的 ACK 状态为准。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_config_version")
public class AiConfigVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** AGENT / MODEL，见 {@link ConfigType}。 */
    private String configType;
    /** 目标业务编码（agentCode / channelCode），跨环境稳定，是版本归组的依据。 */
    private String targetCode;
    /** 目标主键，可空——跨环境迁移后主键会变，故不作为归组依据。 */
    private Long targetId;
    /** 该目标下的版本序号，从 1 开始。 */
    private Integer version;
    /** 下发内容的完整快照（JSON）。 */
    private String content;
    /** 内容摘要，用于跳过"内容没变却重复发布"。 */
    private String contentHash;
    /** FULL / GRAY，见 {@link PublishScope}。 */
    private String publishScope;
    /** 灰度租户编码列表（JSON 数组）。 */
    private String grayTenants;
    private String dataId;
    /** PUBLISHED（已投递）/ SUPERSEDED（已有后续投递）/ FAILED。 */
    private String status;
    /** 回滚来源版本号；非回滚产生的版本为空。 */
    private Integer sourceVersion;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
