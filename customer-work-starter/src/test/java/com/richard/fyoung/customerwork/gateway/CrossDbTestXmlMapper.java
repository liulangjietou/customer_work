package com.richard.fyoung.customerwork.gateway;

import org.apache.ibatis.annotations.Param;

/**
 * 测试用 Mapper：走"XML namespace 绑定"这条路（对应 contentguard/callstats 那种带 XML 的 Mapper），
 * 不做接口注册，验证 mapperLocations 解析确实完成了注册。
 * @author owlzhangfq@gmail.com
 */
public interface CrossDbTestXmlMapper {

    String selectNameById(@Param("id") long id);
}
