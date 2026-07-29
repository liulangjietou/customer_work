package com.richard.fyoung.customeradmin.dict.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.richard.fyoung.customeradmin.dict.jdbc.DictGateway;
import com.richard.fyoung.customerwork.dict.mapper.DictItemMapper;
import com.richard.fyoung.customerwork.dict.mapper.DictTypeMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;

/**
 * 为客服端库数据源装配一套字典门面（{@link DictGateway}）。
 *
 * <p>手法与 {@code ContentGuardGatewayFactory} 一致：现场构建<b>专用</b> {@link SqlSessionFactory}
 * （不暴露为 Spring Bean，免得 MP 自动装配的主 {@code SqlSessionFactory} 退避）。两个 Mapper 都是
 * 无 XML 的纯 {@code BaseMapper}，直接 {@code addMapper} 注册接口即可，无需加载任何 Mapper XML。</p>
 * @author owlzhangfq@gmail.com
 */
final class DictGatewayFactory {

    private DictGatewayFactory() {
    }

    /** 按数据源装配一套门面。工厂构建阶段不连库，首次查询时才真正取连接。 */
    static DictGateway build(DataSource dataSource) {
        try {
            MybatisConfiguration configuration = new MybatisConfiguration();
            // 列名下划线、DO 字段驼峰，必须开启映射（created_at_ms -> createdAtMs）
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(DictTypeMapper.class);
            configuration.addMapper(DictItemMapper.class);

            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.afterPropertiesSet();
            SqlSessionFactory factory = factoryBean.getObject();
            SqlSessionTemplate template = new SqlSessionTemplate(factory);
            return new DictGateway(
                template.getMapper(DictTypeMapper.class),
                template.getMapper(DictItemMapper.class));
        } catch (Exception e) {
            throw new IllegalStateException("failed to build dict gateway", e);
        }
    }
}
