package com.richard.fyoung.customeradmin.workspace.callstats.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AgentCallStatsSource#parse} 宽松解析单测：空/非法回落 ADMIN，大小写不敏感。
 * @author owlzhangfq@gmail.com
 */
class AgentCallStatsSourceTest {

    @Test
    void parse_shouldFallbackToAdmin_forNullBlankOrInvalid() {
        assertEquals(AgentCallStatsSource.ADMIN, AgentCallStatsSource.parse(null));
        assertEquals(AgentCallStatsSource.ADMIN, AgentCallStatsSource.parse("  "));
        assertEquals(AgentCallStatsSource.ADMIN, AgentCallStatsSource.parse("xyz"));
    }

    @Test
    void parse_shouldBeCaseInsensitive() {
        assertEquals(AgentCallStatsSource.APP, AgentCallStatsSource.parse("app"));
        assertEquals(AgentCallStatsSource.APP, AgentCallStatsSource.parse(" APP "));
        assertEquals(AgentCallStatsSource.ADMIN, AgentCallStatsSource.parse("Admin"));
    }
}
