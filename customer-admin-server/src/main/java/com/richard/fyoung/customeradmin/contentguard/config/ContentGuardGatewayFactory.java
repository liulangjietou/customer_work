package com.richard.fyoung.customeradmin.contentguard.config;

import com.richard.fyoung.customeradmin.contentguard.jdbc.ContentGuardGateway;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordExtMapper;
import com.richard.fyoung.customeradmin.contentguard.jdbc.SensitiveWordHitLogExtMapper;
import com.richard.fyoung.customerwork.gateway.CrossDbGateway;
import com.richard.fyoung.customerwork.security.ratelimit.mapper.RateLimitRuleMapper;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordHitLogMapper;
import com.richard.fyoung.customerwork.sensitiveword.mapper.SensitiveWordMapper;

import java.util.List;

/**
 * 把客服端库的跨库环境装配成内容风控门面（{@link ContentGuardGateway}）。
 *
 * <p>建池/探测/专用 SqlSessionFactory 这套通用手法在 starter 的 {@code CrossDbGateways}，这里只声明
 * "本域要哪些 Mapper、加载哪些 XML"。</p>
 *
 * <p>{@link SensitiveWordHitLogMapper} 没有自己的 XML（只用 BaseMapper 的 CRUD），故走接口注册；
 * 其余三张有 XML，靠 namespace 绑定自动完成注册，<b>不能</b>再登记进接口列表（同名语句会冲突）。</p>
 * @author owlzhangfq@gmail.com
 */
final class ContentGuardGatewayFactory {

    /** starter jar 内的敏感词 Mapper XML（classpath*: 才能命中 jar 内资源）。 */
    private static final String STARTER_WORD_XML = "classpath*:customerwork/mapper/SensitiveWordMapper.xml";
    /** starter jar 内的限流规则 Mapper XML。 */
    private static final String STARTER_RULE_XML = "classpath*:customerwork/mapper/RateLimitRuleMapper.xml";
    /** admin 自带的读侧扩展 XML（放 /contentguard 下，避开主 MP 默认的 /mapper 扫描）。 */
    private static final String EXT_XML = "classpath*:contentguard/*.xml";

    /** 无 XML、只用 BaseMapper CRUD 的 Mapper 接口。 */
    static final List<Class<?>> MAPPER_CLASSES = List.of(SensitiveWordHitLogMapper.class);

    /** 需加载的 Mapper XML 位置。 */
    static final List<String> MAPPER_XML_LOCATIONS = List.of(STARTER_WORD_XML, STARTER_RULE_XML, EXT_XML);

    private ContentGuardGatewayFactory() {
    }

    /** 按跨库环境装配一套门面。 */
    static ContentGuardGateway build(CrossDbGateway gateway) {
        return new ContentGuardGateway(
            gateway.getMapper(SensitiveWordMapper.class),
            gateway.getMapper(SensitiveWordExtMapper.class),
            gateway.getMapper(RateLimitRuleMapper.class),
            gateway.getMapper(SensitiveWordHitLogMapper.class),
            gateway.getMapper(SensitiveWordHitLogExtMapper.class));
    }
}
