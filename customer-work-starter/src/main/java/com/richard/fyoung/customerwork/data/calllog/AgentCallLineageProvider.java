package com.richard.fyoung.customerwork.data.calllog;

/** 在调用开始时采集当前实际生效的配置与制品版本。 */
@FunctionalInterface
public interface AgentCallLineageProvider {

    AgentCallLineage capture();
}
