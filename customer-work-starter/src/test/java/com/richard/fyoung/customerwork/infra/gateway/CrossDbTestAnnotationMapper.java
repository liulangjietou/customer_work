package com.richard.fyoung.customerwork.infra.gateway;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 测试用 Mapper：走"接口注册"这条路（对应 dict 那种无 XML 的 Mapper）。
 * @author owlzhangfq@gmail.com
 */
public interface CrossDbTestAnnotationMapper {

    @Select("select item_name from cross_db_test_item where id = #{id}")
    String selectNameById(@Param("id") long id);
}
