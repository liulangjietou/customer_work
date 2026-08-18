package com.richard.fyoung.customeradmin.subjectquota.dto;

import lombok.Data;

/**
 * 用户等级分配视图（只暴露分配等级需要的字段）。
 *
 * <p>刻意不带密码哈希、手机号等账户字段：这个页面的用途是"给谁配哪一档"，
 * 顺手把账户全字段查出来渲染，只会让一个配额页面变成又一个用户资料泄露面。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class SubjectQuotaUserVO {

    private String userId;
    private String username;
    private String nickname;
    /** 当前绑定等级；空表示走配置里的默认档。 */
    private String levelCode;
    private String status;
    private Long createdAtMs;
}
