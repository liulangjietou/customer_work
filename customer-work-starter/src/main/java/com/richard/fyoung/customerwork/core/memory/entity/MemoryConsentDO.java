package com.richard.fyoung.customerwork.core.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** {@code cw_memory_consent} 持久化对象。 */
@Data
@TableName("cw_memory_consent")
public class MemoryConsentDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String subjectType;
    private String subjectId;
    private String agentId;
    private String scopeId;
    private String status;
    private String consentVersion;
    private Long grantedAtMs;
    private Long withdrawnAtMs;
    private Long updatedAtMs;
}
