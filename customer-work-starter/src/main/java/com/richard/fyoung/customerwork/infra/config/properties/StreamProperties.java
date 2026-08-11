package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式（SSE）传输配置。
 *
 * <p>缓解框架 #1741：SSE 流在下游长时间无数据时无法自行关闭，导致连接泄漏。
 * 通过空闲超时把长时间不产元素的流转成一条友好收尾消息后正常结束。</p>
 */
@Data
public class StreamProperties {
    /**
     * SSE 流空闲超时（秒）：相邻两个元素间隔超过该值即触发超时兜底收尾。
     * 默认 120；{@code <=0} 表示禁用空闲超时。
     */
    private long idleTimeoutSeconds = 120;
}
