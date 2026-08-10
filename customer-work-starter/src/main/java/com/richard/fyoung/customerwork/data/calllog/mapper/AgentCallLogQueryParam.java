package com.richard.fyoung.customerwork.data.calllog.mapper;

import lombok.Data;

/**
 * 主记录查询的 XML 传参对象（把领域 {@code AgentCallLogQuery} 摊平成 MyBatis 可读属性）。
 *
 * <p>用可变 POJO 而非 record：XML 里 {@code <if test="q.username != null">} 需按属性名取值，
 * 摊平成命名清晰的过滤字段，避免在 XML 里引用 record 访问器带来的可读性折损。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class AgentCallLogQueryParam {

    private String username;
    private String agentCode;
    private String requestId;
    private String sessionId;
    private Long startFromMs;
    private Long startToMs;
    private int offset;
    private int limit;
}
