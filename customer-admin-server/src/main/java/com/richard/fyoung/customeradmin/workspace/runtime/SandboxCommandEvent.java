package com.richard.fyoung.customeradmin.workspace.runtime;

/** 交互式命令服务的内部事件；Controller 负责把 payload 序列化成对应 SSE data。 */
public record SandboxCommandEvent(String event, Object payload) {
}
