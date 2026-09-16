package com.richard.fyoung.customeradmin.aiconfig.agent.dto;

/** 草稿与其幂等试用标识共用的小写 UUID 入口契约。 */
public final class AgentDraftIds {
    public static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private AgentDraftIds() { }
}
