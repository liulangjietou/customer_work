package com.richard.fyoung.customeradmin.subjectquota.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台用户分档视图（只暴露分配等级需要的字段）。
 *
 * <p>与 {@link SubjectQuotaUserVO}（客服端终端用户）刻意分成两个类型：{@code sys_user} 与
 * {@code cw_user} 的主键类型、状态取值都不一样，硬塞进一个 VO 只会让前端处处判断"这是哪种用户"。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class AdminQuotaUserVO {

    private Long userId;
    private String username;
    private String nickname;
    /** 当前绑定等级；空表示走配置里的 default-admin-level。 */
    private String levelCode;
    /** 0 禁用 / 1 启用。 */
    private Integer status;
    private LocalDateTime createTime;
}
