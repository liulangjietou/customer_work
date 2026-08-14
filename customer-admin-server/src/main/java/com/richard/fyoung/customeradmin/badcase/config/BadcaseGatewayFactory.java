package com.richard.fyoung.customeradmin.badcase.config;

import com.richard.fyoung.customerwork.capability.badcase.BadcaseService;
import com.richard.fyoung.customerwork.capability.badcase.MybatisBadcaseStore;
import com.richard.fyoung.customerwork.capability.badcase.mapper.BadcaseMapper;
import com.richard.fyoung.customerwork.capability.eval.MybatisEvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.mapper.EvalCaseMapper;
import com.richard.fyoung.customerwork.infra.gateway.CrossDbGateway;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;

import java.util.List;

/**
 * 把客服端库的跨库环境装配成 badcase 回流服务。
 *
 * <p>门面类型直接用 starter 的 {@link BadcaseService}：筛选流转的规则（重复采纳要拒绝、
 * 已处理的不能再忽略、编号冲突要提前拦）全在那里，admin 抄一份就多了一处会漂移的实现。
 * 后台只负责"点哪个按钮"，不负责"点了之后怎么算"。</p>
 *
 * <p>三张表都在客服端库：badcase 队列、评测用例、知识库 FAQ——回流的两个出口都在那边，
 * 所以整条链路跨一次库就够，不必绕 HTTP（与评测触发不同，那个必须跑在客服端才有真实模型链）。</p>
 *
 * <p>聊天留痕传 {@code null}：回查对话上下文只发生在<b>登记</b>那一刻（客服端侧），
 * 后台读到的 badcase 里上下文已经落好了。</p>
 * @author owlzhangfq@gmail.com
 */
final class BadcaseGatewayFactory {

    private static final String STARTER_BADCASE_XML = "classpath*:customerwork/mapper/BadcaseMapper.xml";
    private static final String STARTER_EVAL_CASE_XML = "classpath*:customerwork/mapper/EvalCaseMapper.xml";
    private static final String STARTER_KNOWLEDGE_XML = "classpath*:customerwork/mapper/KnowledgeMapper.xml";

    /** 三张表的 Mapper 都有 XML，靠 namespace 自动绑定，不能再登记进接口列表（同名语句会冲突）。 */
    static final List<Class<?>> MAPPER_CLASSES = List.of();

    static final List<String> MAPPER_XML_LOCATIONS =
        List.of(STARTER_BADCASE_XML, STARTER_EVAL_CASE_XML, STARTER_KNOWLEDGE_XML);

    private BadcaseGatewayFactory() {
    }

    static BadcaseService build(CrossDbGateway gateway) {
        return new BadcaseService(
            new MybatisBadcaseStore(gateway.getMapper(BadcaseMapper.class)),
            new MybatisEvalCaseStore(gateway.getMapper(EvalCaseMapper.class)),
            null,
            gateway.getMapper(KnowledgeMapper.class));
    }
}
