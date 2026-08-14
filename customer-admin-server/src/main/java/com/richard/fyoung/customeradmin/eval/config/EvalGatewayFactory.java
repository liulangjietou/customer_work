package com.richard.fyoung.customeradmin.eval.config;

import com.richard.fyoung.customerwork.capability.eval.EvalRunStore;
import com.richard.fyoung.customerwork.capability.eval.MybatisEvalRunStore;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalRunMapper;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;

import java.util.List;

/**
 * 把客服端库的跨库环境装配成评测记录门面。
 *
 * <p>门面类型直接用 starter 的 {@link EvalRunStore}，而不是另建一个 admin 版 Gateway：
 * DO↔领域对象的转换、JSON 列的解析、损坏行的降级，这些逻辑 {@link MybatisEvalRunStore} 里都有了，
 * admin 再抄一份就多了一处会漂移的实现——两边对同一行数据解析出不同结果，是最难查的那类 bug。
 * admin 侧因此只负责"读出来怎么展示"，不碰"存进去是什么"。</p>
 *
 * <p>{@link EvalRunMapper} 有自己的 XML（取最值查询），靠 namespace 绑定自动注册，
 * <b>不能</b>再登记进接口列表——同名语句会冲突。</p>
 * @author owlzhangfq@gmail.com
 */
final class EvalGatewayFactory {

    /** starter jar 内的评测 Mapper XML（classpath*: 才能命中 jar 内资源）。 */
    private static final String STARTER_EVAL_XML = "classpath*:customerwork/mapper/EvalRunMapper.xml";

    /** 无需额外接口注册：EvalRunMapper 由 XML namespace 自动绑定。 */
    static final List<Class<?>> MAPPER_CLASSES = List.of();

    static final List<String> MAPPER_XML_LOCATIONS = List.of(STARTER_EVAL_XML);

    private EvalGatewayFactory() {
    }

    static EvalRunStore build(CrossDbGateway gateway) {
        return new MybatisEvalRunStore(gateway.getMapper(EvalRunMapper.class));
    }
}
