package com.richard.fyoung.customerwork.infra.ws;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.DistributedProperties;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 下行推送广播器装配：与限流计数、会话锁同一套「进程内默认 + Redis 可选」模式。
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class WsDownstreamBroadcasterConfig {

    private static final Logger log = LoggerFactory.getLogger(WsDownstreamBroadcasterConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public WsDownstreamBroadcaster wsDownstreamBroadcaster(
            CustomerWorkProperties properties, ObjectProvider<RedissonClient> redissonProvider) {
        DistributedProperties cfg = properties.getDistributed();
        if (!StoreModes.isRedis(cfg.getWsDownstreamMode())) {
            return new NoOpWsDownstreamBroadcaster();
        }
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            // 与限流计数同样的取舍：配了 redis 却没有客户端时让位给单副本行为而不是启动失败，
            // 下行推送是旁路能力，不该拖垮主链路的可启动性；配置没生效由 error 日志暴露
            log.error("ws-downstream-mode=redis but no RedissonClient available, "
                + "fallback to single-node behaviour, code={}", "WS-BROADCAST-CLIENT-MISSING");
            return new NoOpWsDownstreamBroadcaster();
        }
        return new RedissonWsDownstreamBroadcaster(redisson, cfg.getWsDownstreamTopic());
    }
}
