package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分布式锁配置：{@code DistributedLockExecutor} 底层 RedissonClient 连接的 Redis。 */
@Data
public class DistributedLockProperties {
    private final Redis redis = new Redis();

    @Data
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
    }
}
