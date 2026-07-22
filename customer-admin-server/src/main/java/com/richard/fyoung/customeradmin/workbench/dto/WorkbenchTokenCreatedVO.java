package com.richard.fyoung.customeradmin.workbench.dto;

import lombok.Data;

/**
 * 令牌创建成功的一次性响应：{@code token} 明文仅在此刻返回一次，之后库里只留哈希，无法再取回。
 * @author owlzhangfq@gmail.com
 */
@Data
public class WorkbenchTokenCreatedVO {
    private Long id;
    private String name;
    /** 令牌明文，仅创建时返回一次，请立即保存。 */
    private String token;
}
