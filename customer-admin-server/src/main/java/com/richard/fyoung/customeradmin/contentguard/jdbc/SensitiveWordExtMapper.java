package com.richard.fyoung.customeradmin.contentguard.jdbc;

import com.richard.fyoung.customerwork.safety.sensitiveword.entity.SensitiveWordEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 敏感词读侧扩展 Mapper（纯手写 XML，非 MyBatis-Plus BaseMapper）。
 *
 * <p>starter 的 {@code SensitiveWordMapper} 只提供"取全部/取启用"两种读法——运行时构建自动机够用，
 * 但后台词库页要按词面模糊、类目、动作、启停多条件分页。故此处补一套读 SQL，写入仍复用 starter 的 Mapper。</p>
 *
 * <p>刻意放在 {@code .jdbc} 包（而非 {@code .mapper}）：admin 主 {@code @MapperScan("...**.mapper")}
 * 不扫到本接口，只由内容风控专用 {@code SqlSessionFactory} 注册，避免污染主 MyBatis 环境。
 * XML 在 {@code classpath:/contentguard/}，避开主 MP 默认的 {@code classpath*:/mapper/**} 扫描。</p>
 * @author owlzhangfq@gmail.com
 */
public interface SensitiveWordExtMapper {

    /** 多条件分页查询（id 倒序，最新在前）。 */
    List<SensitiveWordEntity> findPage(@Param("q") SensitiveWordQueryParam query);

    /** 符合条件的总数。 */
    long countBy(@Param("q") SensitiveWordQueryParam query);

    /** 按词面精确查一条（新增/改名时判重用；word 是唯一键）。 */
    SensitiveWordEntity findByWord(@Param("word") String word);
}
