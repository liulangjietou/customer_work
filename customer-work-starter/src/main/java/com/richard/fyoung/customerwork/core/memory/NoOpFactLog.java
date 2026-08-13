package com.richard.fyoung.customerwork.core.memory;

import java.util.List;

/**
 * 空实现事实日志：持久化环境未激活（取不到 {@code FactLogMapper}）时的兜底。
 *
 * <p>刻意<b>不落盘</b>：本项目不再提供文件形态的事实日志（多副本各写各的、容器销毁即丢）。
 * 装配时已按 error 级别记过"降级"日志（见 {@link FactLogConfig}），此处静默丢弃即可——
 * 事实日志是旁路能力，缺它不该打断对话主链路，更不该为它引入一个会产生不一致数据的落盘路径。</p>
 * @author owlzhangfq@gmail.com
 */
public class NoOpFactLog implements FactLog {

    @Override
    public void append(String scopeId, String fact) {
        // 无持久化环境，丢弃
    }

    @Override
    public List<String> read(String scopeId) {
        return List.of();
    }

    @Override
    public List<FactRecord> readRecords(String scopeId) {
        return List.of();
    }
}
