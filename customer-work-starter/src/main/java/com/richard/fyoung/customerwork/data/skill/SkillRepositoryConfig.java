package com.richard.fyoung.customerwork.data.skill;

import com.richard.fyoung.customerwork.data.skill.mapper.SkillFileMapper;
import com.richard.fyoung.customerwork.data.skill.mapper.SkillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MySQL 技能仓库装配：只在持久化环境已激活（Mapper 可取）时注册 {@link MysqlSkillMaterializer}。
 *
 * <p>不像记忆链路那样"降级"——技能仓库的降级发生在使用侧（{@code CustomerServiceAgentFactory}
 * 取不到物化器就改读磁盘存量），这里只负责"有 Mapper 才建 Bean"，避免在没有持久化环境的宿主里
 * 注册一个必然抛异常的 Bean。</p>
 *
 * <p>Mapper 缺席时刻意<b>返回 null</b> 而不是加 {@code @ConditionalOnBean(SkillMapper.class)}：
 * Mapper 由 {@code @MapperScan} 注册，与条件求值的先后顺序不稳定，条件注解会时灵时不灵；
 * 返回 null 时 Spring 登记为 NullBean，使用侧的 {@code ObjectProvider#getIfAvailable} 照常拿到 null。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SkillRepositoryConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillRepositoryConfig.class);

    @Bean
    @ConditionalOnMissingBean(MysqlSkillMaterializer.class)
    public MysqlSkillMaterializer mysqlSkillMaterializer(ObjectProvider<SkillMapper> skillMapperProvider,
                                                          ObjectProvider<SkillFileMapper> skillFileMapperProvider) {
        SkillMapper skillMapper = skillMapperProvider.getIfAvailable();
        SkillFileMapper skillFileMapper = skillFileMapperProvider.getIfAvailable();
        if (skillMapper == null || skillFileMapper == null) {
            log.info("mysql skill materializer not wired (persistence env inactive)");
            return null;
        }
        log.info("mysql skill materializer ready (tables=cw_skill/cw_skill_file)");
        return new MysqlSkillMaterializer(skillMapper, skillFileMapper);
    }
}
