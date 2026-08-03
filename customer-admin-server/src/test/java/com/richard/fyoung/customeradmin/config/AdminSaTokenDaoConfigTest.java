package com.richard.fyoung.customeradmin.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoRedisJackson;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link AdminSaTokenDaoConfig} 单测：手动装配必须替插件把 {@code init} 调掉。
 *
 * <p>这一层防的是"忘了调 init"这类只在运行期第一次鉴权才炸的空指针——插件把连接工厂的注入点
 * 设计成 {@code @Autowired} 方法，脱离自动装配后没人替我们调。</p>
 * @author owlzhangfq@gmail.com
 */
class AdminSaTokenDaoConfigTest {

    @Test
    void saTokenDao_shouldBeRedisBackedAndInitialized() {
        RedisConnectionFactory connectionFactory = Mockito.mock(RedisConnectionFactory.class);

        SaTokenDao dao = new AdminSaTokenDaoConfig().saTokenDao(connectionFactory);

        SaTokenDaoRedisJackson redisDao = assertInstanceOf(SaTokenDaoRedisJackson.class, dao);
        // init 成功的可观测标志：两个 RedisTemplate 建好且绑到传入的连接工厂上
        assertNotNull(redisDao.stringRedisTemplate);
        assertNotNull(redisDao.objectRedisTemplate);
        assertSame(connectionFactory, redisDao.stringRedisTemplate.getConnectionFactory());
        assertSame(connectionFactory, redisDao.objectRedisTemplate.getConnectionFactory());
    }
}
