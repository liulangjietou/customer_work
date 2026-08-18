package com.richard.fyoung.customeradmin.subjectquota.config;

import com.richard.fyoung.customeradmin.subjectquota.jdbc.SubjectQuotaGateway;
import com.richard.fyoung.customerwork.data.user.mapper.UserMapper;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaHitMapper;
import com.richard.fyoung.customerwork.safety.subjectquota.mapper.SubjectQuotaLevelMapper;

import java.util.List;

/**
 * 把客服端库的跨库环境装配成主体配额门面。
 *
 * <p>{@link UserMapper} 没有自己的 XML（只用 BaseMapper 的 CRUD），故走接口注册；
 * 另两个有 XML，靠 namespace 绑定自动注册，<b>不能</b>再登记进接口列表——同名语句会冲突。
 * 这条规则在内容风控那边踩过，照抄即可。</p>
 * @author owlzhangfq@gmail.com
 */
final class SubjectQuotaGatewayFactory {

    /** starter jar 内的等级 Mapper XML（classpath*: 才能命中 jar 内资源）。 */
    private static final String STARTER_LEVEL_XML = "classpath*:customerwork/mapper/SubjectQuotaLevelMapper.xml";
    /** starter jar 内的命中记录 Mapper XML。 */
    private static final String STARTER_HIT_XML = "classpath*:customerwork/mapper/SubjectQuotaHitMapper.xml";

    /** 无 XML、只用 BaseMapper CRUD 的 Mapper 接口。 */
    static final List<Class<?>> MAPPER_CLASSES = List.of(UserMapper.class);

    static final List<String> MAPPER_XML_LOCATIONS = List.of(STARTER_LEVEL_XML, STARTER_HIT_XML);

    private SubjectQuotaGatewayFactory() {
    }

    static SubjectQuotaGateway build(CrossDbGateway gateway) {
        return new SubjectQuotaGateway(
            gateway.getMapper(SubjectQuotaLevelMapper.class),
            gateway.getMapper(SubjectQuotaHitMapper.class),
            gateway.getMapper(UserMapper.class));
    }
}
