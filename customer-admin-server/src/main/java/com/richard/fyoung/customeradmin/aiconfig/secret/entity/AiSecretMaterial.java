package com.richard.fyoung.customeradmin.aiconfig.secret.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/** LOCAL_AES 凭据的不可变版本。 */
@Data
@TableName("ai_secret_material")
public class AiSecretMaterial {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long secretRefId;
    private Integer version;
    @JsonIgnore
    private String cipherText;
    private String keyId;
    private String status;
    private Long createBy;
    private LocalDateTime createTime;
}
