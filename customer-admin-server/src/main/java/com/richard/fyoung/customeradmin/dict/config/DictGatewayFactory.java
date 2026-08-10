package com.richard.fyoung.customeradmin.dict.config;

import com.richard.fyoung.customeradmin.dict.jdbc.DictGateway;
import com.richard.fyoung.customerwork.data.dict.mapper.DictItemMapper;
import com.richard.fyoung.customerwork.data.dict.mapper.DictTypeMapper;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;

import java.util.List;

/**
 * 把客服端库的跨库环境装配成字典门面（{@link DictGateway}）。
 *
 * <p>建池/探测/专用 SqlSessionFactory 这套通用手法在 starter 的 {@code CrossDbGateways}，这里只声明
 * "本域要哪些 Mapper"。两个 Mapper 都是无 XML 的纯 {@code BaseMapper}，走接口注册即可，不加载任何 XML。</p>
 * @author owlzhangfq@gmail.com
 */
final class DictGatewayFactory {

    /** 无 XML、只用 BaseMapper CRUD 的 Mapper 接口。 */
    static final List<Class<?>> MAPPER_CLASSES = List.of(DictTypeMapper.class, DictItemMapper.class);

    /** 本域不需要任何 Mapper XML。 */
    static final List<String> MAPPER_XML_LOCATIONS = List.of();

    private DictGatewayFactory() {
    }

    /** 按跨库环境装配一套门面。 */
    static DictGateway build(CrossDbGateway gateway) {
        return new DictGateway(
            gateway.getMapper(DictTypeMapper.class),
            gateway.getMapper(DictItemMapper.class));
    }
}
