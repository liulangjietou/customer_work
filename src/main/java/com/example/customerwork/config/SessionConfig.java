package com.example.customerwork.config;

import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 会话持久化配置（对应深度解析一文"问题一：会话状态在重启/扩缩容后丢失"的解法）。
 *
 * <p>提供一个 {@link Session} Bean 作为状态外置存储。{@code CustomerServiceService} 在每轮
 * 对话结束后用 {@code agent.saveTo(session, key)} 落盘，请求到来时用
 * {@code agent.loadIfExists(session, key)} 按 sessionId 恢复——同一会话跨请求、
 * 乃至（json 模式下）跨进程重启都能恢复完整上下文。</p>
 *
 * <p>分布式多实例生产环境，把 mode 扩展为 redis/mysql 并返回框架内置的
 * {@code RedisSession} / {@code MysqlSession} 即可，调用方代码无需改动。</p>
 */
@Configuration
public class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    @Bean
    public Session agentSession(CustomerWorkProperties properties) {
        CustomerWorkProperties.Session cfg = properties.getSession();
        String mode = cfg.getMode() == null ? "memory" : cfg.getMode().trim().toLowerCase();

        if ("json".equals(mode)) {
            Path dir = Path.of(cfg.getDirectory());
            log.info("会话持久化：JsonSession（落盘目录 {}），单实例重启可恢复", dir.toAbsolutePath());
            return new JsonSession(dir);
        }

        log.info("会话持久化：InMemorySession（进程内，重启丢失）。生产请改用 json/redis/mysql");
        return new InMemorySession();
    }
}
